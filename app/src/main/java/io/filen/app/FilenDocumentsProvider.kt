package io.filen.app

import android.app.AuthenticationRequiredException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import uniffi.filen_mobile_native_cache.FfiDir
import uniffi.filen_mobile_native_cache.FfiFile
import uniffi.filen_mobile_native_cache.FfiNonRootObject
import uniffi.filen_mobile_native_cache.FfiObject
import uniffi.filen_mobile_native_cache.FilenMobileCacheState
import uniffi.filen_mobile_native_cache.ProgressCallback
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import uniffi.filen_mobile_native_cache.CacheException
import uniffi.filen_mobile_native_cache.ItemType
import uniffi.filen_mobile_native_cache.SearchQueryArgs
import uniffi.filen_mobile_native_cache.SearchQueryResponseEntry
import uniffi.filen_mobile_native_cache.SearchUpdateCallback
import uniffi.filen_mobile_native_cache.ThumbnailResult
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val DIR_UPDATE_INTERVAL = 15_000L // 15 seconds
const val ROOT_UPDATE_INTERVAL = 60_000L // 1 minute

private val FfiNonRootObject.uuid: String
	get() = when (this) {
		is FfiNonRootObject.File -> v1.uuid
		is FfiNonRootObject.Dir -> v1.uuid
	}

private val FfiNonRootObject.displayName: String
	get() = when (this) {
		is FfiNonRootObject.File -> v1.meta?.name ?: v1.uuid
		is FfiNonRootObject.Dir -> v1.meta?.name ?: v1.uuid
	}

// Items are identified by their whole-life id: for files the server-minted
// stable id (the plain uuid is re-minted on every content edit and version
// restore), for directories the uuid itself (stable == uuid on the wire, by
// design). Name paths are never document ids anymore — they went stale on
// every rename/move. The Rust cache resolves the `stable/` namespace for
// every operation.
private fun documentIdFor(obj: FfiNonRootObject): String =
	when (obj) {
		is FfiNonRootObject.File -> "stable/" + obj.v1.stableUuid
		is FfiNonRootObject.Dir -> "stable/" + obj.v1.uuid
	}

private const val TAG = "FilenDocumentsProvider"

// The provider's own cache state authenticates lazily on its first op and is briefly throttled,
// so the first background search can throw Disabled/AuthenticationRequired; retry past that.
private const val MAX_SEARCH_REFRESH_ATTEMPTS = 10
private const val SEARCH_REFRESH_RETRY_DELAY_MS = 1_000L
private const val TRANSFERS_CHANNEL = "transfers_channel"
private const val TRANSFERS_GROUP = "io.filen.app.TRANSFERS"
private const val TRANSFERS_SUMMARY_ID = 0
private const val LAUNCHER_ICON = "ic_launcher"
private const val ROOT_TITLE = "Filen"

class FilenDocumentsProvider : DocumentsProvider() {

	companion object {
		init {
			System.loadLibrary("filen_mobile_native_cache")
		}

		@JvmStatic
		external fun initJavaVM()
	}

	// very frustrating that this is nullable,
	// but we cannot initialize it in the constructor because the context is not available yet
	// thanks android!
	private var state: FilenMobileCacheState? = null
	private var rootUuid: String? = null
		get() {
			if (field != null) return field
			field = cache { it.rootUuid() }
			return field
		}
	private val AUTHORITY = "io.filen.app.documentsprovider"

	// Backstop: an exception escaping a fire-and-forget coroutine in this scope would otherwise
	// reach the default handler and kill the whole app process — a network blip during a
	// background refresh must never be fatal in a documents provider.
	private val scope = CoroutineScope(
		Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
			Log.e(TAG, "Uncaught exception in provider background scope", e)
		}
	)

	// State for the CURRENT live search — one at a time, mirroring the SDK's single live engine
	// per root (a different query key replaces it). querySearchDocuments returns `results`
	// synchronously so the binder thread NEVER blocks on the network; refreshSearch fills them in
	// the background and pings `notifyUri`. Grouping the fields keeps them from drifting apart the
	// way the old loose @Volatile stash did.
	private class SearchState(val key: String, notifyUri: Uri) {
		@Volatile
		var results: List<SearchQueryResponseEntry> = emptyList()
		// The observer uri of the MOST RECENT cursor for this search. refreshSearch notifies this
		// (not a captured stale one), so convergence pings reach whatever cursor the system is
		// currently watching — every querySearchDocuments mints a fresh uri and repoints here.
		@Volatile
		var notifyUri: Uri = notifyUri
		// True until the first successful querySearch (or we exhaust retries); gates the loading
		// spinner so a re-query during the auth-warmup retry window keeps it up.
		@Volatile
		var loading: Boolean = true
		// Refresh coalescing (guarded by `searchLock`): at most one querySearch in flight; an update
		// ping during it sets `pending` and exactly one more refresh runs when it finishes, instead
		// of unbounded overlapping coroutines on a churning resync.
		var refreshing: Boolean = false
		var pending: Boolean = false
	}
	@Volatile
	private var search: SearchState? = null
	private val searchLock = Any()
	private var transferNotifications: TransferNotifications? = null

	init {
		initJavaVM()
	}

	// routes synchronous (binder-thread) SDK calls through convertCacheException so a raw
	// uniffi CacheException never crosses the binder. do NOT use this in async scope.launch
	// blocks (those must log + notifyChange instead of rethrowing).
	private inline fun <T> cache(block: (FilenMobileCacheState) -> T): T =
		try {
			block(state!!)
		} catch (e: CacheException) {
			throw convertCacheException(e)
		}

	private fun transferNotification(text: String): NotificationCompat.Builder =
		NotificationCompat.Builder(context!!, TRANSFERS_CHANNEL).apply {
			setContentTitle(ROOT_TITLE)
			setContentText(text)
			setSmallIcon(
				context!!.resources.getIdentifier(
					LAUNCHER_ICON,
					"mipmap",
					context!!.packageName
				)
			)
			setOngoing(true)
			setOnlyAlertOnce(true)
			setProgress(100, 0, false)
			setGroup(TRANSFERS_GROUP)
		}

	// Posts a per-transfer notification plus the shared group summary (so concurrent transfers
	// collapse into one stack in the shade). Returns the notification id + builder for progress
	// updates; every begin MUST be paired with TransferNotifications.end, normally in a finally.
	private fun beginTransferNotification(text: String): Pair<Int, NotificationCompat.Builder> {
		val builder = transferNotification(text)
		val summary = transferNotification("Transferring files").apply {
			setProgress(0, 0, false)
			setGroupSummary(true)
		}
		val id = transferNotifications!!.begin(builder.build(), summary.build())
		return id to builder
	}

	private fun initializeClient(filesPath: String): FilenMobileCacheState {
		val documentProviderPath = Paths.get(filesPath, "documentsProvider")
		Files.createDirectories(documentProviderPath);
		// Provision-or-load the auth.json DEK from the Android Keystore (same-UID as the app). The
		// provider process can be started by the Files app BEFORE the app enables the provider and
		// provisions the key; since the Rust cache captures the key at construction but re-reads
		// auth.json on a poll, an absent-then-appearing key would strand the provider unauthenticated
		// until its process restarts. getOrCreate is idempotent and shares the Keystore alias +
		// wrapped-blob contract with the app, so a valid, stable key is always present up front and the
		// poll decrypts as soon as auth.json is written. A no-secure-hardware failure yields an empty
		// key -> Rust decrypt fails -> unauthenticated (fail-closed).
		val dek =
			try {
				AuthKeystore.getOrCreateDek(filesPath)
			} catch (e: Exception) {
				ByteArray(0)
			}
		return FilenMobileCacheState(
			"$filesPath/documentsProvider",
			"$filesPath/auth.json",
			dek
		)
	}

	override fun onCreate(): Boolean {
		this.state = initializeClient(context!!.filesDir.absolutePath)
		val manager: Any? = context!!.getSystemService(Context.NOTIFICATION_SERVICE)
		manager as NotificationManager
		val channel =
			NotificationChannel(TRANSFERS_CHANNEL, "Transfer", NotificationManager.IMPORTANCE_LOW)
		manager.createNotificationChannel(channel)
		transferNotifications = TransferNotifications(manager)
		return true

	}

	override fun queryRoots(projection: Array<out String>?): Cursor {
		Log.d(
			TAG,
			"Querying roots with projection: ${projection?.joinToString() ?: "null"}"
		)
		val result = MatrixCursor(projection ?: getRootProjection())

		val root = try {
			state!!.queryRootsInfo(rootUuid!!)!!
		} catch (e: CacheException) {
			when (e) {
				is CacheException.Unauthenticated -> return result
				is CacheException.Disabled -> return result
				else -> throw convertCacheException(e)
			}
		} catch (_: AuthenticationRequiredException) {
			return result
		}
		val row = result.newRow()
		row.add(Root.COLUMN_ROOT_ID, rootUuid!!)
		row.add(Root.COLUMN_DOCUMENT_ID, rootUuid!!)
		row.add(Root.COLUMN_CAPACITY_BYTES, root.maxStorage)
		row.add(
			Root.COLUMN_AVAILABLE_BYTES,
			root.maxStorage - root.storageUsed
		)
		row.add(Root.COLUMN_MIME_TYPES, "*/*")
		row.add(Root.COLUMN_TITLE, ROOT_TITLE)
		// we get this dynamically because doing it at compile time wasn't working
		// ideally this should instead be R.mipmap.ic_launcher
		row.add(
			Root.COLUMN_ICON,
			context!!.resources.getIdentifier(LAUNCHER_ICON, "mipmap", context!!.packageName)
		)
		row.add(
			Root.COLUMN_FLAGS,
			Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_CREATE
		)

		val rootUri = getNotifyURI(root.uuid)
		result.setNotificationUri(context!!.contentResolver, rootUri)

		val now = System.currentTimeMillis()
		if (now > root.lastUpdated + ROOT_UPDATE_INTERVAL) {
			val extras = Bundle()
			extras.putBoolean(DocumentsContract.EXTRA_LOADING, true)
			result.extras = extras
			scope.launch {
				try {
					state!!.updateRootsInfo()
				} catch (e: CacheException) {
					Log.e(TAG, "Error updating roots info", e)
				} finally {
					context!!.contentResolver.notifyChange(
						rootUri,
						null,
					)
				}
			}
		}


		return result
	}

	private fun getNotifyURI(documentId: String): Uri {
		if (rootUuid!! == documentId) {
			return DocumentsContract.buildRootsUri(AUTHORITY)
		}
		return DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
	}

	private fun addFileToRow(row: MatrixCursor.RowBuilder, file: FfiFile, id: String) {
		val meta = file.meta
		row.add(Document.COLUMN_DOCUMENT_ID, id)
		row.add(
			Document.COLUMN_DISPLAY_NAME,
			meta?.name ?: "CANNOT_DECRYPT_NAME_${file.uuid}"
		)
		row.add(Document.COLUMN_SIZE, file.size)
		row.add(
			Document.COLUMN_MIME_TYPE,
			meta?.mime?.ifEmpty { "application/octet-stream" }
				?: "application/octet-stream")
		row.add(Document.COLUMN_LAST_MODIFIED, meta?.modified ?: 0L)
		row.add(Document.COLUMN_FLAGS, getFileFlags(meta?.mime))
	}

	private fun addDirToRow(row: MatrixCursor.RowBuilder, dir: FfiDir, id: String) {
		val meta = dir.meta
		row.add(Document.COLUMN_DOCUMENT_ID, id)
		row.add(
			Document.COLUMN_DISPLAY_NAME,
			meta?.name ?: "CANNOT_DECRYPT_NAME_${dir.uuid}"
		)
		row.add(Document.COLUMN_SIZE, 0)
		row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
		row.add(Document.COLUMN_LAST_MODIFIED, meta?.created ?: 0L)
		row.add(Document.COLUMN_FLAGS, getDefaultFolderFlags())
	}

	private fun addObjectToRow(
		row: MatrixCursor.RowBuilder,
		obj: FfiObject,
		id: String
	) {
		when (obj) {
			is FfiObject.File -> addFileToRow(row, obj.v1, id)
			is FfiObject.Dir -> addDirToRow(row, obj.v1, id)
			is FfiObject.Root -> addRootRow(row, id)
		}
	}

	private fun addNonRootObjectToRow(
		row: MatrixCursor.RowBuilder,
		obj: FfiNonRootObject,
		id: String
	) {
		when (obj) {
			is FfiNonRootObject.File -> addFileToRow(row, obj.v1, id)
			is FfiNonRootObject.Dir -> addDirToRow(row, obj.v1, id)
		}
	}

	override fun queryDocument(
		documentId: String?,
		projection: Array<out String>?,
	): Cursor {
		documentId!!
		val result = MatrixCursor(projection ?: getDocumentProjection())
		val row = result.newRow()
		var actualId = documentId
		if (actualId == "null") {
			actualId = rootUuid
		}
		actualId!!
		if (actualId == rootUuid) {
			addRootRow(row, actualId)
		} else {
			val item = cache { it.queryItem(actualId) }
				?: throw IllegalArgumentException("Document with ID $documentId not found")
			addObjectToRow(row, item, actualId)
		}
		return result;
	}

	private fun <T> addObjectsToCursor(
		result: MatrixCursor,
		objects: List<T>,
		extractor: (T) -> Pair<String, FfiNonRootObject>
	) {
		for (item in objects) {
			val (id, obj) = extractor(item)
			val row = result.newRow()
			addNonRootObjectToRow(row, obj, id)
		}
	}

	override fun queryChildDocuments(
		parentDocumentId: String?,
		projection: Array<out String>?,
		orderBy: String?,
	): Cursor {
		parentDocumentId!!
		val result = MatrixCursor(projection ?: getDocumentProjection())
		val resp = cache { it.queryDirChildren(parentDocumentId, orderBy) } ?: return result

		this.addObjectsToCursor(result, resp.objects, { obj: FfiNonRootObject ->
			Pair(documentIdFor(obj), obj)
		})

		val now = System.currentTimeMillis()
		val notifyUri = getNotifyURI(parentDocumentId)
		result.setNotificationUri(context!!.contentResolver, notifyUri)

		Log.d(
			TAG,
			"Querying child documents for: $parentDocumentId, lastListed: ${resp.parent.lastListed}, now: $now"
		)
		if (now > resp.parent.lastListed + DIR_UPDATE_INTERVAL) {
			val extras = Bundle()
			extras.putBoolean(DocumentsContract.EXTRA_LOADING, true)
			result.extras = extras
			scope.launch {
				try {
					state!!.updateDirChildren(parentDocumentId)
				} catch (e: CacheException) {
					Log.e(TAG, "Error updating dir children for $parentDocumentId", e)
				} finally {
					context!!.contentResolver.notifyChange(
						notifyUri,
						null,
					)
				}
			}
		}

		return result;
	}

	override fun queryRecentDocuments(
		rootId: String,
		projection: Array<out String>?,
		queryArgs: Bundle?,
		signal: CancellationSignal?
	): Cursor {
		Log.d(TAG, "query recents")
		val result = MatrixCursor(projection ?: getDocumentProjection())

		val resp = runBlocking {
			val job = async {
				try {
					state!!.updateAndQueryRecents(null)
				} catch (e: CacheException) {
					throw convertCacheException(e)
				}
			}

			signal?.setOnCancelListener {
				job.cancel()
			}

			job.await()
		}

		this.addObjectsToCursor(result, resp.objects, { obj: FfiNonRootObject ->
			Pair(documentIdFor(obj), obj)
		})

		return result
	}

	@OptIn(ExperimentalUuidApi::class)
	override fun querySearchDocuments(
		rootId: String,
		projection: Array<out String?>?,
		queryArgs: Bundle
	): Cursor? {
		val result = MatrixCursor(projection ?: getDocumentProjection())

		val requestedMimeTypes = (queryArgs.getStringArray(DocumentsContract.QUERY_ARG_MIME_TYPES)
			?: arrayOf()).toList()
		// dirs carry no mime, so the dir request is expressed via itemType, not the mime list
		val mimeTypes = requestedMimeTypes.filter { it != Document.MIME_TYPE_DIR }
		// decide itemType from the whole (unordered) set, not from inside the filter:
		// file mimes present => restrict to files; only dir requested => restrict to dirs; otherwise both
		val itemType: ItemType? = when {
			mimeTypes.isNotEmpty() -> ItemType.FILE
			requestedMimeTypes.contains(Document.MIME_TYPE_DIR) -> ItemType.DIR
			else -> null
		}

		val name = (queryArgs.get(DocumentsContract.QUERY_ARG_DISPLAY_NAME) as? String)
		val rustQueryArgs = SearchQueryArgs(
			name = name,
			excludeMediaOnDevice = queryArgs.getBoolean(
				DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA,
				false
			),
			mimeTypes = mimeTypes,
			fileSizeMin = (queryArgs.get(DocumentsContract.QUERY_ARG_FILE_SIZE_OVER) as? Long)?.toULong(),
			lastModifiedMin = (queryArgs.get(DocumentsContract.QUERY_ARG_LAST_MODIFIED_AFTER) as? Long)?.toULong(),
			itemType = itemType
		)

		val notifyUri =
			DocumentsContract.buildSearchDocumentsUri(AUTHORITY, rootId, Uuid.random().toString())
		result.setNotificationUri(context!!.contentResolver, notifyUri)

		// No search term -> nothing to search; return an empty cursor (no background work).
		if (name == null) {
			return result
		}

		// One live search at a time. A matching key is a re-query (the system re-reading after a
		// notifyChange): reuse the stash and just repoint the observer uri at THIS cursor so ongoing
		// convergence pings reach it. A different key is a fresh search that supersedes the old one.
		val key = searchKeyOf(rustQueryArgs)
		val searchState: SearchState
		val isNewQuery: Boolean
		val snapResults: List<SearchQueryResponseEntry>
		val snapLoading: Boolean
		synchronized(searchLock) {
			val current = search
			if (current != null && current.key == key) {
				current.notifyUri = notifyUri
				searchState = current
				isNewQuery = false
			} else {
				searchState = SearchState(key, notifyUri)
				search = searchState
				isNewQuery = true
			}
			// Snapshot results + loading under the SAME lock as the repoint, so the returned rows and
			// the loading decision stay mutually consistent even if a refreshSearch write lands between
			// them.
			snapResults = searchState.results
			snapLoading = searchState.loading
		}

		// Return whatever has synced so far synchronously — the binder thread must NEVER block on the
		// network (create_search validates + resyncs remotely). refreshSearch fills the stash in the
		// background and notifyChange()s, so the system re-queries and reads the fresher stash. Only a
		// fresh query launches a refresh, so the notify -> re-query cycle can't loop.
		this.addObjectsToCursor(result, snapResults, { e -> Pair(documentIdFor(e.`object`), e.`object`) })

		// Loading spinner whenever we still have nothing to show and a fetch is (or will be) running —
		// evaluated on EVERY query, so a re-query during the retry window keeps it up.
		if (snapLoading && snapResults.isEmpty()) {
			result.extras = Bundle().apply {
				putBoolean(DocumentsContract.EXTRA_LOADING, true)
			}
		}

		if (isNewQuery) {
			scope.launch { refreshSearch(rootId, rustQueryArgs, searchState) }
		}
		return result
	}

	// A stable identity for a search query, so a re-query (notifyChange) is recognised as the same
	// search and reuses the stash instead of re-launching.
	private fun searchKeyOf(args: SearchQueryArgs): String =
		"${args.name}|${args.itemType}|${args.mimeTypes.sorted().joinToString(",")}|" +
			"${args.fileSizeMin}|${args.lastModifiedMin}|${args.excludeMediaOnDevice}"

	// Runs the actual search OFF the binder thread (Dispatchers.IO): updates the stash and pings
	// the system. Re-runs itself when the engine reports the results changed (bounded by the
	// engine's own debounced convergence), and bails if a newer query has superseded this one.
	private suspend fun refreshSearch(
		rootId: String,
		args: SearchQueryArgs,
		searchState: SearchState
	) {
		// Coalesce: at most one refresh cycle in flight per state; a ping during it defers to
		// `pending` and runs exactly once more when this cycle ends, instead of piling up coroutines.
		// A cycle that finds itself already superseded still pings its (possibly still-live) observer
		// so a cursor left mid-load isn't stranded.
		var superseded = false
		synchronized(searchLock) {
			when {
				search !== searchState -> superseded = true
				searchState.refreshing -> {
					searchState.pending = true
					return
				}
				else -> searchState.refreshing = true
			}
		}
		if (superseded) {
			context?.contentResolver?.notifyChange(searchState.notifyUri, null)
			return
		}
		try {
			var attempt = 0
			while (search === searchState && attempt < MAX_SEARCH_REFRESH_ATTEMPTS) {
				attempt++
				try {
					val results = state!!.querySearch(rootId, args, object : SearchUpdateCallback {
						override fun onUpdate() {
							// Coalesced relaunch; refreshSearch reads searchState.notifyUri fresh.
							scope.launch { refreshSearch(rootId, args, searchState) }
						}
					})
					// Commit results + read the CURRENT observer uri under the lock so the write and the
					// uri can't interleave with a concurrent same-key repoint; notify OUTSIDE the lock.
					val uri = synchronized(searchLock) {
						if (search === searchState) {
							searchState.results = results
							searchState.loading = false
							searchState.notifyUri
						} else {
							null
						}
					}
					uri?.let { context?.contentResolver?.notifyChange(it, null) }
					return
				} catch (e: Exception) {
					// Disabled/AuthenticationRequired during the provider's auth warmup, or a transient
					// failure — retry (the state re-reads auth.json after its throttle expires).
					Log.w(TAG, "Search refresh attempt $attempt for key=${searchState.key} failed, retrying: ${e.message}")
					delay(SEARCH_REFRESH_RETRY_DELAY_MS)
				}
			}
			// Gave up (or a newer query superseded this one) — clear loading + ping so the spinner stops.
			val uri = synchronized(searchLock) {
				if (search === searchState) {
					searchState.loading = false
					searchState.notifyUri
				} else {
					null
				}
			}
			uri?.let { context?.contentResolver?.notifyChange(it, null) }
		} finally {
			val runAgain = synchronized(searchLock) {
				searchState.refreshing = false
				if (searchState.pending && search === searchState) {
					searchState.pending = false
					true
				} else {
					false
				}
			}
			if (runAgain) {
				scope.launch { refreshSearch(rootId, args, searchState) }
			}
		}
	}

	override fun refresh(
		uri: Uri?, extras: Bundle?, cancellationSignal: CancellationSignal?
	): Boolean {
		Log.d(TAG, "Refresh called with uri: $uri")

		val path = getDocumentIdFromPath(uri)!!
		val item = cache { it.queryItem(path) }
		if (item == null) {
			Log.e(TAG, "Item not found for uri: $uri")
			return false
		}

		val job: Job

		when (item) {
			is FfiObject.Dir -> {
				job = scope.launch {
					try {
						if (item.v1.lastListed + DIR_UPDATE_INTERVAL < System.currentTimeMillis()) {
							// a bare uuid is not a resolvable id (only the root's is);
							// the stable namespace addresses any dir by its uuid
							state!!.updateDirChildren("stable/" + item.v1.uuid)
						}
					} catch (e: CacheException) {
						Log.e(TAG, "Error refreshing dir ${item.v1.uuid}", e)
					} finally {
						// notify the id the caller's cursor actually watches
						context!!.contentResolver.notifyChange(
							getNotifyURI(path),
							null,
						)
					}
				}
			}

			is FfiObject.Root -> {
				job = scope.launch {
					// The catches MUST live inside each async: a failing child async fails the
					// parent launch through the Job tree before awaitAll rethrows, so an outer
					// try/catch cannot stop the exception from reaching the process-killing
					// default handler. The try here exists only for the finally (notify even on
					// cancellation), never to catch.
					try {
						awaitAll(
							async {
								try {
									if (item.v1.lastListed + DIR_UPDATE_INTERVAL < System.currentTimeMillis()) {
										state!!.updateDirChildren(item.v1.uuid)
									}
								} catch (e: CacheException) {
									Log.e(TAG, "Error refreshing root children ${item.v1.uuid}", e)
								}
							},
							async {
								try {
									if (item.v1.lastUpdated + ROOT_UPDATE_INTERVAL < System.currentTimeMillis()) {
										state!!.updateRootsInfo()
									}
								} catch (e: CacheException) {
									Log.e(TAG, "Error refreshing roots info", e)
								}
							}
						)
					} finally {
						context!!.contentResolver.notifyChange(
							getNotifyURI(item.v1.uuid),
							null,
						)
					}
				}
			}

			is FfiObject.File -> {
				Log.w(TAG, "Tried to refresh file: $path")
				return false;
			}
		}

		cancellationSignal?.setOnCancelListener {
			job.cancel("Refresh cancelled by caller")
		}
		return true;
	}

	override fun openDocument(
		documentId: String?,
		mode: String?,
		signal: CancellationSignal?,
	): ParcelFileDescriptor {
		documentId!!
		val accessMode = ParcelFileDescriptor.parseMode(mode)

		val fd = runBlocking {
			Log.d(TAG, "Opening document: $documentId with mode: $mode")
			try {
				signal?.throwIfCanceled()

				val (id, builder) = beginTransferNotification("Downloading")

				// todo, do not download if we only want to write to the file
				val path = try {
					val pathJob = async {
						try {
							state!!.downloadFileIfChangedByPath(
								documentId,
								ProgressNotifier(builder, id, transferNotifications!!::notifyProgress)
							)
						} catch (e: CacheException) {
							throw convertCacheException(e)
						}
					}
					signal?.setOnCancelListener {
						pathJob.cancel("Download cancelled by caller")
					}
					pathJob.await()
				} finally {
					transferNotifications!!.end(id)
				}

				val file = File(path)

				if (!file.exists()) {
					throw FileNotFoundException("File not found: $path")
				}

				signal?.throwIfCanceled()

				when {
					accessMode == ParcelFileDescriptor.MODE_READ_ONLY -> {
						ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
					}

					else -> {
						Log.d(TAG, "Opening file for writing: $documentId")
						// this can be improved because we do not need to download the file if we only want to write to it
						val handler = Handler(context!!.mainLooper)

						ParcelFileDescriptor.open(file, accessMode, handler, { exception ->
							Log.d(
								TAG,
								"File opened with exception: $exception"
							)
							if (exception != null) {
								Log.e(
									TAG,
									"Error opening document $documentId: ${exception.message}"
								)
							} else {
								scope.launch {
									val (uploadId, uploadBuilder) = beginTransferNotification("Uploading")

									try {
										val updated = try {
											state!!.uploadFileIfChanged(
												documentId,
												ProgressNotifier(uploadBuilder, uploadId, transferNotifications!!::notifyProgress)
											)
										} catch (e: CacheException) {
											throw convertCacheException(e)
										}
										if (updated) {
											context!!.contentResolver.notifyChange(
												getNotifyURI(documentId),
												null,
											)
										}
									} catch (e: Exception) {
										// async scope: log instead of rethrowing (an unhandled coroutine
										// exception would kill the provider process)
										Log.e(TAG, "Upload failed for $documentId: ${e.message}")
									} finally {
										transferNotifications!!.end(uploadId)
									}
								}
							}
						})
					}
				}
			} catch (e: CancellationException) {
				// a CancellationSignal cancels pathJob -> CancellationException; this is a user cancel,
				// not a missing file, so propagate it unchanged instead of masking it as not-found
				Log.d(TAG, "Opening document $documentId cancelled")
				throw e
			} catch (e: Exception) {
				Log.e(TAG, "Error opening document $documentId: ${e.message}")
				throw FileNotFoundException("Document not found: $documentId: ${e.message}")
			}
		}
		Log.d(TAG, "Opened document: $documentId with fd: $fd")
		return fd
	}

	override fun openDocumentThumbnail(
		documentId: String?,
		sizeHint: Point?,
		signal: CancellationSignal?
	): AssetFileDescriptor {
		val state = this.state

		val job = scope.async {

			val result =
				state!!.getThumbnail(documentId!!, sizeHint!!.x.toUInt(), sizeHint.y.toUInt())

			when (result) {
				is ThumbnailResult.Err -> throw convertCacheException(result.v1)
				ThumbnailResult.NoThumbnail -> throw FileNotFoundException("No thumbnail available for document: $documentId")
				ThumbnailResult.NotFound -> throw FileNotFoundException("$documentId not found")
				is ThumbnailResult.Ok -> {
					val path = result.v1
					val file = File(path)
					AssetFileDescriptor(
						ParcelFileDescriptor.open(
							file,
							ParcelFileDescriptor.MODE_READ_ONLY
						), 0, file.length()
					)
				}
			}
		}

		signal?.setOnCancelListener {
			job.cancel("Thumbnail generation cancelled by caller")
		}

		return runBlocking {
			job.await()
		}
	}

	override fun createDocument(
		parentDocumentId: String?, mimeType: String?, displayName: String?
	): String {
		parentDocumentId!!
		mimeType!!
		displayName!!
		return runBlocking {
			Log.d(
				TAG,
				"Creating document: $displayName with mimeType: $mimeType in parent: $parentDocumentId"
			)
			val documentId: String
			if (mimeType.equals(Document.MIME_TYPE_DIR, true)) {
				// Create a new directory, identified by its stable id from day one
				documentId =
					cache { "stable/" + it.createDir(parentDocumentId, displayName, null).dir.uuid }
			} else {
				// Create a new file, identified by its stable id from day one
				documentId =
					cache { "stable/" + it.createEmptyFile(parentDocumentId, displayName, mimeType).file.stableUuid }
			}

			context!!.contentResolver.notifyChange(
				getNotifyURI(parentDocumentId),
				null,
			)
			documentId
		}

	}

	override fun removeDocument(documentId: String?, parentDocumentId: String?) {
		this.deleteDocument(documentId)
	}

	override fun deleteDocument(documentId: String?) {
		documentId!!
		runBlocking {
			val descendants = cache { it.getAllDescendantPaths(documentId) }
			// grants on descendants were issued under their stable-form ids —
			// resolve those before the trash retires the paths
			val descendantStableIds = descendants.mapNotNull { descendant ->
				val item = try {
					cache { it.queryItem(descendant) }
				} catch (e: Exception) {
					Log.e(TAG, "Failed to resolve descendant $descendant for revocation", e)
					null
				}
				when (item) {
					is FfiObject.File -> "stable/" + item.v1.stableUuid
					is FfiObject.Dir -> "stable/" + item.v1.uuid
					else -> null
				}
			}
			val resp = cache { it.trashItem(documentId) }

			// the id actually granted to other apps is the document id itself
			// (a stable id for files), so revoke that alongside the
			// path-form descendants and the descendants' stable-form ids
			revokeDocumentPermission(documentId)
			for (descendant in descendants) {
				revokeDocumentPermission(descendant)
			}
			for (stableId in descendantStableIds) {
				revokeDocumentPermission(stableId)
			}

			// a stable file id has no path structure to split a parent out of;
			// the trash response still carries the original parent
			val parentNotifyId = notifyIdForContainerOf(resp.`object`)
				?: getParentId(documentId)
			if (parentNotifyId != null) {
				context!!.contentResolver.notifyChange(
					getNotifyURI(parentNotifyId),
					null,
				)
			}
		}
	}

	// The document id of the container an object lives in (its original parent
	// while trashed), so change notifications land on the same URI the
	// container's watchers registered under. Containers are directories, whose
	// stable id IS their uuid — no cache lookup needed. Null when unresolvable.
	private fun notifyIdForContainerOf(obj: FfiObject): String? {
		val parentUuid = when (obj) {
			is FfiObject.File -> obj.v1.originalParent ?: obj.v1.parent
			is FfiObject.Dir -> obj.v1.originalParent ?: obj.v1.parent
			is FfiObject.Root -> return null
		}
		if (parentUuid == rootUuid) return rootUuid
		return "stable/$parentUuid"
	}

	override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
		if (documentId == null || parentDocumentId == null) return false
		// stable ids carry no path structure — resolve both sides to their
		// canonical name paths before the containment check (the lookup also
		// accepts a stale pre-migration uuid)
		val resolvedId = resolveToPath(documentId) ?: return false
		val resolvedParentId = resolveToPath(parentDocumentId) ?: return false
		// require a path boundary so siblings like "Photos" and "Photos Backup" don't match;
		// the root parent owns everything beneath it
		return resolvedId != resolvedParentId && resolvedId.startsWith("$resolvedParentId/")
	}

	// The canonical name path for a document id: stable-form ids are resolved
	// through the cache, everything else already is a path.
	private fun resolveToPath(documentId: String): String? {
		if (!documentId.startsWith("stable/")) return documentId
		return try {
			cache { it.queryPathForUuid(documentId.removePrefix("stable/")) }
		} catch (e: Exception) {
			Log.e(TAG, "Failed to resolve stable id $documentId", e)
			null
		}
	}

	override fun getDocumentType(documentId: String?): String {
		documentId!!
		val item = cache { it.queryItem(documentId) }
			?: throw FileNotFoundException("Document with ID $documentId not found")
		return when (item) {
			is FfiObject.File -> item.v1.meta?.mime?.ifEmpty { "application/octet-stream" } ?: "application/octet-stream"
			is FfiObject.Dir -> Document.MIME_TYPE_DIR
			is FfiObject.Root -> Document.MIME_TYPE_DIR
		}
	}

	override fun moveDocument(
		sourceDocumentId: String?,
		sourceParentDocumentId: String?,
		targetParentDocumentId: String?
	): String {
		sourceDocumentId!!
		sourceParentDocumentId!!
		targetParentDocumentId!!
		return runBlocking {
			val resp = cache { it.moveItem(sourceDocumentId, targetParentDocumentId) }
			// identity survives the move for files and directories alike
			val newId = when (val obj = resp.`object`) {
				is FfiObject.File -> "stable/" + obj.v1.stableUuid
				is FfiObject.Dir -> "stable/" + obj.v1.uuid
				else -> resp.id
			}
			context!!.contentResolver.notifyChange(
				getNotifyURI(sourceParentDocumentId),
				null,
			)
			context!!.contentResolver.notifyChange(
				getNotifyURI(targetParentDocumentId),
				null,
			)
			newId
		}
	}

	override fun renameDocument(documentId: String?, displayName: String?): String? {
		documentId!!
		displayName!!
		return runBlocking {
			val resp = cache { it.renameItem(documentId, displayName) }
			// identity survives the rename for files and directories alike
			val newId = when (val obj = resp?.`object`) {
				is FfiObject.File -> "stable/" + obj.v1.stableUuid
				is FfiObject.Dir -> "stable/" + obj.v1.uuid
				else -> resp?.id
			}
			val parentNotifyId = resp?.`object`?.let { notifyIdForContainerOf(it) }
				?: getParentId(documentId)
			if (parentNotifyId != null) {
				context!!.contentResolver.notifyChange(
					getNotifyURI(parentNotifyId),
					null,
				)
			}
			newId
		}
	}

	private fun makeAuthException(core: Throwable): AuthenticationRequiredException {
		val intent = Intent().apply {
			setClassName(AUTHORITY, "io.filen.app.MainActivity")
		}

		val pendingIntent = PendingIntent.getActivity(
			context,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)


		return AuthenticationRequiredException(
			core,
			pendingIntent
		)
	}

	private fun convertCacheException(error: CacheException): Exception {
		return when (error) {
			is CacheException.Unauthenticated -> {
				makeAuthException(error)
			}

			is CacheException.Disabled -> {
				makeAuthException(error)
			}

			is CacheException.DoesNotExist -> {
				FileNotFoundException(error.v1.toString())
			}

			is CacheException.InvalidName -> {
				IllegalArgumentException(error.v1.toString())
			}

			is CacheException.NotADirectory -> {
				IllegalArgumentException(error.v1.toString())
			}

			is CacheException.Unsupported -> {
				UnsupportedOperationException(error.v1.toString())
			}

			// defensive: never let a raw uniffi CacheException cross the binder
			else -> FileNotFoundException(error.message)
		}
	}

	override fun shutdown() {
		// uniffi doesn't do this automatically for kotlin
		state?.close()
	}
}

private fun getDefaultFolderFlags(): Int =
	Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_DIR_SUPPORTS_CREATE

private fun getDefaultFileFlags(): Int =
	Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_REMOVE

// flags for the root DOCUMENT row (Document.COLUMN_FLAGS) — only Document.* flags belong here.
// the root is not deletable/renamable/movable; the Root.* capability flags live in queryRoots.
private fun getRootDocumentFlags(): Int =
	Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_WRITE

private fun addRootRow(
	row: MatrixCursor.RowBuilder, rootUuid: String
) {
	row.add(Document.COLUMN_DOCUMENT_ID, rootUuid)
	row.add(Document.COLUMN_DISPLAY_NAME, ROOT_TITLE)
	row.add(Document.COLUMN_SIZE, 0)
	row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
	row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
	row.add(Document.COLUMN_FLAGS, getRootDocumentFlags())
}

private fun getRootProjection(): Array<String> = arrayOf(
	Root.COLUMN_ROOT_ID,
	Root.COLUMN_SUMMARY,
	Root.COLUMN_CAPACITY_BYTES,
	Root.COLUMN_FLAGS,
	Root.COLUMN_MIME_TYPES,
	Root.COLUMN_AVAILABLE_BYTES,
	Root.COLUMN_TITLE,
	Root.COLUMN_ICON,
)

private fun getDocumentProjection(): Array<String> = arrayOf(
	Document.COLUMN_DOCUMENT_ID,
	Document.COLUMN_DISPLAY_NAME,
	Document.COLUMN_SIZE,
	Document.COLUMN_MIME_TYPE,
	Document.COLUMN_LAST_MODIFIED,
	Document.COLUMN_FLAGS
)

private fun getFileFlags(mime: String?): Int {
	var flags = getDefaultFileFlags()
	// fall back to no thumbnail flag when the mime is unknown (null/blank)
	if (mime != null && (mime.startsWith("image") || mime.startsWith("video"))) {
		flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
	}
	return flags
}

private fun getDocumentIdFromPath(path: Uri?): String? {
	val fullPath = path?.path;
	val documentId = fullPath?.removePrefix("/document")
	if (fullPath == documentId) {
		val rootId = fullPath?.removePrefix("/root")
		if (rootId == fullPath) {
			Log.e(TAG, "Invalid document ID: $fullPath")
			return null
		}
		return rootId
	}
	return documentId
}

private fun getParentId(documentId: String): String? {
	val trimmed = documentId.trimEnd('/')
	val lastSlashIndex = trimmed.lastIndexOf('/')
	return if (lastSlashIndex == -1) {
		null
	} else {
		trimmed.substring(0, lastSlashIndex)
	}
}

// Owns every notify/cancel on the transfers channel: the id allocator, the active-id set, and
// the paired child + summary post/cancel. All state is private and only reachable through
// synchronized methods, so the lock and the data it guards can't drift apart. Invariant: the
// summary notification is posted iff at least one per-transfer notification is active, and a
// transfer finishing while another starts can't cancel the summary out from under a freshly
// posted child.
private class TransferNotifications(private val manager: NotificationManager) {
	// TRANSFERS_SUMMARY_ID is 0, so per-transfer ids start at 1.
	private var nextId = 1
	private val activeIds = mutableSetOf<Int>()

	@Synchronized
	fun begin(child: Notification, summary: Notification): Int {
		val id = nextId++
		activeIds.add(id)
		manager.notify(TRANSFERS_SUMMARY_ID, summary)
		manager.notify(id, child)
		return id
	}

	// Idempotent: ids are never reused, so a second end for the same id is a no-op.
	@Synchronized
	fun end(id: Int) {
		if (!activeIds.remove(id)) {
			return
		}
		manager.cancel(id)
		if (activeIds.isEmpty()) {
			manager.cancel(TRANSFERS_SUMMARY_ID)
		}
	}

	// Progress updates MUST route through here instead of notifying directly: cancelling the
	// Kotlin coroutine does not stop the Rust transfer (the uniffi wrapper spawns it on its own
	// runtime, so cancel merely detaches it), and the detached transfer keeps firing progress
	// callbacks after end() cancelled the id. An unguarded notify would then re-post the ongoing
	// notification with nothing left to ever cancel it.
	@Synchronized
	fun notifyProgress(id: Int, notification: Notification) {
		if (id in activeIds) {
			manager.notify(id, notification)
		}
	}
}

class ProgressNotifier(
	private var builder: NotificationCompat.Builder,
	private val notificationId: Int,
	// TransferNotifications.notifyProgress — drops updates for ended transfers, since the
	// Rust transfer (and its callbacks) can outlive the coroutine that awaited it
	private val notify: (Int, Notification) -> Unit
) :
	ProgressCallback {
	private var maxBytes = 0UL
	private var readBytes = 0UL

	override fun onProgress(bytesProcessed: ULong) {
		readBytes += bytesProcessed
		Log.d("Notifier", "Notifier $notificationId: $bytesProcessed bytes processed")
		if (readBytes >= maxBytes) {
			Log.d("Notifier", "Notifier $notificationId: completed")
			builder.setProgress(0, 0, false)
		} else {
			// we use 100 and divide because otherwise uploading a file > 2GB
			// will cause the progress to overflow since the progress bar uses an Int
			Log.d("Notifier", "Notifier $notificationId: $readBytes/$maxBytes bytes processed")
			builder.setProgress(100, (readBytes * 100UL / maxBytes).toInt(), false)
		}
		notify(notificationId, builder.build())
	}

	override fun setTotal(size: ULong) {
		maxBytes = size
	}
}

