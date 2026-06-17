package io.filen.app

import android.app.AuthenticationRequiredException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import uniffi.filen_mobile_native_cache.ThumbnailResult
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
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

private const val TAG = "FilenDocumentsProvider"
private const val TRANSFERS_CHANNEL = "transfers_channel"
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
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private var notificationManager: NotificationManager? = null
	private val notificationIdCounter = AtomicInteger(0)

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
		}

	private fun initializeClient(filesPath: String): FilenMobileCacheState {
		val documentProviderPath = Paths.get(filesPath, "documentsProvider")
		Files.createDirectories(documentProviderPath);
		return FilenMobileCacheState(
			"$filesPath/documentsProvider",
			"$filesPath/auth.json"
		)
	}

	override fun onCreate(): Boolean {
		this.state = initializeClient(context!!.filesDir.absolutePath)
		val manager: Any? = context!!.getSystemService(Context.NOTIFICATION_SERVICE)
		manager as NotificationManager
		val channel =
			NotificationChannel(TRANSFERS_CHANNEL, "Transfer", NotificationManager.IMPORTANCE_LOW)
		manager.createNotificationChannel(channel)
		notificationManager = manager
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
			Pair("$parentDocumentId/" + obj.displayName, obj)
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
			Pair("recents/" + obj.uuid, obj)
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

		val results = cache { it.querySearch(rustQueryArgs) }

		this.addObjectsToCursor(result, results, { e ->
			Pair(e.path, e.`object`)
		})

		val notifyUri =
			DocumentsContract.buildSearchDocumentsUri(AUTHORITY, rootId, Uuid.random().toString())
		result.setNotificationUri(context!!.contentResolver, notifyUri)

		if (name != null) {
			val extras = Bundle()
			extras.putBoolean(DocumentsContract.EXTRA_LOADING, true)
			result.extras = extras

			scope.launch {
				try {
					state!!.updateSearch(name)
				} catch (e: CacheException) {
					Log.e(TAG, "Error updating search for $name", e)
				} finally {
					context!!.contentResolver.notifyChange(
						notifyUri,
						null,
					)
				}
			}
		}
		return result
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
							state!!.updateDirChildren(item.v1.uuid)
						}
					} catch (e: CacheException) {
						Log.e(TAG, "Error refreshing dir ${item.v1.uuid}", e)
					} finally {
						context!!.contentResolver.notifyChange(
							getNotifyURI(item.v1.uuid),
							null,
						)
					}
				}
			}

			is FfiObject.Root -> {
				job = scope.launch {
					try {
						awaitAll(
							async {
								if (item.v1.lastListed + DIR_UPDATE_INTERVAL < System.currentTimeMillis()) {
									state!!.updateDirChildren(item.v1.uuid)
								}
							},
							async {
								if (item.v1.lastUpdated + ROOT_UPDATE_INTERVAL < System.currentTimeMillis()) {
									state!!.updateRootsInfo()
								}
							}
						)
					} catch (e: CacheException) {
						Log.e(TAG, "Error refreshing root ${item.v1.uuid}", e)
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

				val builder = transferNotification("Downloading")
				val nManager = notificationManager!!
				val id = notificationIdCounter.getAndIncrement()
				nManager.notify(id, builder.build())

				// todo, do not download if we only want to write to the file
				val pathJob = async {
					try {
						state!!.downloadFileIfChangedByPath(
							documentId,
							ProgressNotifier(nManager, builder, id)
						)
					} catch (e: CacheException) {
						throw convertCacheException(e)
					}

				}
				signal?.setOnCancelListener {
					pathJob.cancel("Download cancelled by caller")
				}
				val path = pathJob.await()

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
									val uploadBuilder = transferNotification("Uploading")
									val uploadId = notificationIdCounter.getAndIncrement()
									nManager.notify(uploadId, uploadBuilder.build())

									val updated = try {
										state!!.uploadFileIfChanged(
											documentId,
											ProgressNotifier(nManager, uploadBuilder, uploadId)
										)
									} catch (e: CacheException) {
										throw convertCacheException(e)
									}
									if (updated) {
										context!!.contentResolver.notifyChange(
											getNotifyURI(documentId),
											null,
										)
									} else {
										// if the file was not updated, we still want to notify the user that the upload is complete
										// rust currently doesn't handle this
										uploadBuilder.setProgress(0, 0, false)
										nManager.notify(uploadId, uploadBuilder.build())
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
				// Create a new directory
				documentId = cache { it.createDir(parentDocumentId, displayName, null).id }
			} else {
				// Create a new file
				documentId = cache { it.createEmptyFile(parentDocumentId, displayName, mimeType).id }
			}
			val parentId =
				getParentId(documentId)!! // we can assume that the parent is not null because we successfully trashed the item

			context!!.contentResolver.notifyChange(
				getNotifyURI(parentId),
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
			cache { it.trashItem(documentId) }

			val parentId =
				getParentId(documentId)!! // we can assume that the parent is not null because we successfully trashed the item
			val descendants = cache { it.getAllDescendantPaths(documentId) }

			for (descendant in descendants) {
				revokeDocumentPermission(descendant)
			}

			context!!.contentResolver.notifyChange(
				getNotifyURI(parentId),
				null,
			)
		}
	}

	override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
		if (documentId == null || parentDocumentId == null) return false
		// require a path boundary so siblings like "Photos" and "Photos Backup" don't match;
		// the root parent owns everything beneath it
		return documentId != parentDocumentId && documentId.startsWith("$parentDocumentId/")
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
			val newId = cache { it.moveItem(sourceDocumentId, targetParentDocumentId).id }
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
			val newId = cache { it.renameItem(documentId, displayName)?.id }
			context!!.contentResolver.notifyChange(
				getNotifyURI(getParentId(documentId)!!),
				null,
			)
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

class ProgressNotifier(
	private val notificationManager: NotificationManager,
	private var builder: NotificationCompat.Builder,
	private val notificationId: Int
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
		notificationManager.notify(notificationId, builder.build())
	}

	override fun setTotal(size: ULong) {
		maxBytes = size
	}
}

