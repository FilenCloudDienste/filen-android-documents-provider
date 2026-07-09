package io.filen.app

import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.filen_mobile_native_cache.FilenMobileCacheState
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/**
 * Integration test for the documents provider's search, driven end-to-end through
 * [android.content.ContentResolver] against the registered provider (so it exercises the real
 * `querySearchDocuments` override: Bundle -> SearchQueryArgs mapping, the live cache-search FFI,
 * and MatrixCursor building).
 *
 * Requirements to run (`./gradlew connectedAndroidTest`):
 *  - a connected device/emulator (instrumented test), and
 *  - the session env vars baked into BuildConfig (EMAIL, MASTER_KEYS, API_KEY, PRIVATE_KEY,
 *    AUTH_VERSION, BASE_FOLDER_UUID) — the same ones TestActivity uses to write auth.json.
 *
 * The search is inherently drive-root scoped (that is how the provider works), so it triggers a
 * whole-account resync into the SDK cache on first query; assertions key off a unique name to stay
 * deterministic, and the query is polled while the resync converges.
 */
@RunWith(AndroidJUnit4::class)
class SearchDocumentsInstrumentedTest {
	private val authority = "io.filen.app.documentsprovider"

	// Separate cache dir from the provider's own ("documentsProvider") so data creation and the
	// provider-under-test don't share a cache DB; both authenticate off the same auth.json.
	private lateinit var dataState: FilenMobileCacheState
	private lateinit var authFile: Path

	// Created on the server during the test, trashed in tearDown.
	private var createdRootDirId: String? = null

	private companion object {
		private const val POLL_INTERVAL_MS = 2_000L

		// The provider always searches from the drive root, so the first query triggers a
		// whole-account resync. Test accounts converge in seconds (the loop breaks out early), but
		// a large-but-realistic account can take a few minutes, so allow a generous ceiling.
		private const val CONVERGENCE_TIMEOUT_MS = 5 * 60 * 1_000L

		// How long to keep re-querying a non-matching search to be sure the background search ran
		// and the file never leaked in (the search returns results asynchronously).
		private const val NEGATIVE_CONTROL_WINDOW_MS = 20 * 1_000L
	}

	@Before
	fun setUp() {
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val filesDir: File = ctx.filesDir
		authFile = Paths.get(filesDir.absolutePath, "auth.json")
		writeAuthFile(authFile)
		// The SDK opens native_cache.db under these dirs before creating them, so they must exist
		// first — for our data-creation state AND for the provider's own state (which was created
		// at app startup, before auth.json existed, and re-authenticates lazily on its next op).
		File(filesDir, "documentsProvider").mkdirs()
		val dataDir = File(filesDir, "searchTestData").apply { mkdirs() }
		dataState = FilenMobileCacheState(dataDir.absolutePath, authFile.toString())
	}

	@Test
	fun searchDocumentsFindsUploadedFile() {
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val resolver = ctx.contentResolver
		val rootId = dataState.rootUuid()

		val name = "androidtest_" + Random.nextLong(0, Long.MAX_VALUE).toString(36)
		val fileName = "$name.txt"

		// Create an isolated subtree on the server: <root>/search-<name>/a/<name>.txt
		runBlocking {
			val searchRoot = dataState.createDir(rootId, "search-$name", null)
			createdRootDirId = searchRoot.id
			val a = dataState.createDir(searchRoot.id, "a", null)
			dataState.createEmptyFile(a.id, fileName, "text/plain")
		}

		// Drive the provider's search via ContentResolver, polling while the resync converges.
		val searchUri = DocumentsContract.buildSearchDocumentsUri(authority, rootId, name)
		val queryArgs = android.os.Bundle().apply {
			putString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, name)
		}

		var found = false
		val deadline = System.currentTimeMillis() + CONVERGENCE_TIMEOUT_MS
		while (System.currentTimeMillis() < deadline) {
			try {
				// null projection -> the provider fills its full document projection.
				resolver.query(searchUri, null, queryArgs, null)?.use { cursor ->
					val nameIdx = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
					while (cursor.moveToNext()) {
						if (nameIdx >= 0 && cursor.getString(nameIdx) == fileName) {
							found = true
							break
						}
					}
				}
			} catch (e: Exception) {
				// The provider's own state authenticates lazily on its first op and is briefly
				// throttled, so early queries can throw AuthenticationRequiredException. Retry.
			}
			if (found) break
			Thread.sleep(POLL_INTERVAL_MS)
		}

		assertTrue("search should surface the uploaded file '$fileName'", found)

		// Negative control: a query whose name cannot match must NOT return the file. This proves
		// the provider actually filters — a broken no-op search that just lists the whole drive
		// would contain the file. Because the provider returns results asynchronously (empty first,
		// then the background search fills the stash), poll long enough for that background search
		// to run and assert the file NEVER leaks in.
		val bogus = "no_such_${name}_zzz"
		val bogusUri = DocumentsContract.buildSearchDocumentsUri(authority, rootId, bogus)
		val bogusArgs = android.os.Bundle().apply {
			putString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, bogus)
		}
		var leaked = false
		val bogusDeadline = System.currentTimeMillis() + NEGATIVE_CONTROL_WINDOW_MS
		while (System.currentTimeMillis() < bogusDeadline && !leaked) {
			resolver.query(bogusUri, null, bogusArgs, null)?.use { cursor ->
				val nameIdx = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
				while (cursor.moveToNext()) {
					if (nameIdx >= 0 && cursor.getString(nameIdx) == fileName) {
						leaked = true
						break
					}
				}
			}
			Thread.sleep(POLL_INTERVAL_MS)
		}
		assertFalse(
			"a non-matching query must not return '$fileName' (search must filter, not list all)",
			leaked
		)
	}

	@After
	fun tearDown() {
		createdRootDirId?.let { id ->
			try {
				runBlocking { dataState.trashItem(id) }
			} catch (e: Exception) {
				// best-effort cleanup; leave for the account's own housekeeping if it fails
			}
		}
		try {
			dataState.close()
		} catch (e: Exception) {
			// ignore
		}
	}

	// Mirrors TestActivity.writeAuthFile: a pre-obtained session injected via BuildConfig, so no
	// live login and no secrets in the repo.
	private fun writeAuthFile(path: Path) {
		val content = """
		{
			"providerEnabled": true,
			"sdkConfig": {
				"email": "${BuildConfig.EMAIL}",
				"password": "redacted",
				"twoFactorCode": "",
				"masterKeys": ${BuildConfig.MASTER_KEYS},
				"apiKey": "${BuildConfig.API_KEY}",
				"publicKey": "",
				"privateKey": "${BuildConfig.PRIVATE_KEY}",
				"authVersion": ${BuildConfig.AUTH_VERSION},
				"baseFolderUUID": "${BuildConfig.BASE_FOLDER_UUID}",
				"userId": 0,
				"metadataCache": false,
				"tmpPath": "",
				"connectToSocket": false
			}
		}
		""".trimIndent()
		Files.write(
			path,
			content.toByteArray(),
			StandardOpenOption.WRITE,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)
	}
}
