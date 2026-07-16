package io.filen.app

import android.app.NotificationManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.filen_mobile_native_cache.FilenMobileCacheState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Integration test for the provider's transfer-notification lifecycle, driven end-to-end through
 * [android.content.ContentResolver.openFileDescriptor] against the registered provider.
 *
 * The bug this guards against: every download/upload posted an ongoing notification under a fresh
 * ID and no code path ever cancelled it, so finished transfers piled up as permanent, unswipeable
 * notifications. The unchanged-file re-open was the worst case — the Rust side returns early
 * without a single progress callback, so the notification never even left its initial state.
 *
 * Covers all three transfer shapes:
 *  1. fresh download (first open for read)
 *  2. unchanged-file open (second open for read — no progress callbacks at all)
 *  3. upload (open for write, write bytes, close -> async upload on the close listener)
 *
 * Requirements to run (`./gradlew connectedAndroidTest`): same as SearchDocumentsInstrumentedTest —
 * a connected device/emulator and the session env vars baked into BuildConfig.
 */
@RunWith(AndroidJUnit4::class)
class TransferNotificationsInstrumentedTest {
	private val authority = "io.filen.app.documentsprovider"

	// Separate cache dir from the provider's own ("documentsProvider") so data creation and the
	// provider-under-test don't share a cache DB; both authenticate off the same auth.json.
	private lateinit var dataState: FilenMobileCacheState
	private lateinit var authFile: Path

	// Created on the server during the test, trashed in tearDown.
	private var createdRootDirId: String? = null

	private companion object {
		private const val POLL_INTERVAL_MS = 500L

		// The provider's own state authenticates lazily and openDocument resolves the path chain
		// remotely, so the first open can take a while on a slow connection.
		private const val OPEN_TIMEOUT_MS = 2 * 60 * 1_000L

		// How long a finished transfer's notification may linger before we call it leaked. The fix
		// cancels synchronously before openDocument returns (download) / right after the upload
		// coroutine finishes, so this only absorbs binder latency.
		private const val CLEAR_TIMEOUT_MS = 30 * 1_000L
	}

	@Before
	fun setUp() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val ctx = instrumentation.targetContext
		// Notifications are dropped silently without this on API 33+, which would make the
		// assertions pass vacuously.
		instrumentation.uiAutomation.grantRuntimePermission(
			ctx.packageName,
			android.Manifest.permission.POST_NOTIFICATIONS
		)
		val filesDir: File = ctx.filesDir
		authFile = Paths.get(filesDir.absolutePath, "auth.json")
		val dek = TestAuth.provision(filesDir.absolutePath, authFile)
		// The SDK opens native_cache.db under these dirs before creating them, so they must exist
		// first — for our data-creation state AND for the provider's own state.
		File(filesDir, "documentsProvider").mkdirs()
		val dataDir = File(filesDir, "notifTestData").apply { mkdirs() }
		dataState = FilenMobileCacheState(dataDir.absolutePath, authFile.toString(), dek)
		// Start clean so a leftover notification from another test can't fail us.
		ctx.getSystemService(NotificationManager::class.java).cancelAll()
	}

	@Test
	fun transferNotificationsAreClearedWhenTransfersFinish() {
		val ctx = InstrumentationRegistry.getInstrumentation().targetContext
		val resolver = ctx.contentResolver
		val notificationManager = ctx.getSystemService(NotificationManager::class.java)
		val rootId = dataState.rootUuid()

		val name = "notiftest_" + Random.nextLong(0, Long.MAX_VALUE).toString(36)

		// Create an isolated file on the server: <root>/notif-<name>/<name>.txt
		val fileId = runBlocking {
			val dir = dataState.createDir(rootId, "notif-$name", null)
			createdRootDirId = dir.id
			dataState.createEmptyFile(dir.id, "$name.txt", "text/plain").id
		}
		val docUri = DocumentsContract.buildDocumentUri(authority, fileId)

		// 1. Fresh download. The provider authenticates lazily on its first op, so retry until the
		// open goes through.
		var opened = false
		val openDeadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS
		var lastError: Exception? = null
		while (System.currentTimeMillis() < openDeadline) {
			try {
				resolver.openFileDescriptor(docUri, "r")?.use { }
				opened = true
				break
			} catch (e: Exception) {
				lastError = e
			}
			Thread.sleep(POLL_INTERVAL_MS)
		}
		assertTrue("openDocument should eventually succeed (last error: $lastError)", opened)
		assertNotificationsClear(notificationManager, "download")

		// 2. Unchanged-file open: the Rust side returns the cached path without a single progress
		// callback, so the notification must be cleared by the caller, not by progress reaching 100%.
		resolver.openFileDescriptor(docUri, "r")?.use { }
		assertNotificationsClear(notificationManager, "unchanged-file download")

		// 3. Upload: open for write, change the content, close. The close listener uploads
		// asynchronously (main-looper close callback -> scope.launch), so asserting right after
		// close() would race ahead of the upload notification ever being posted and pass vacuously.
		// The upload's one observable completion signal is the provider's notifyChange(docUri) on a
		// successful content change — wait for it, then require the shade to drain.
		val uploadDone = CountDownLatch(1)
		val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
			override fun onChange(selfChange: Boolean) {
				uploadDone.countDown()
			}
		}
		resolver.registerContentObserver(docUri, false, observer)
		try {
			resolver.openFileDescriptor(docUri, "w")?.use { pfd ->
				FileOutputStream(pfd.fileDescriptor).use { out ->
					out.write("notification test content $name".toByteArray())
				}
			}
			assertTrue(
				"upload never completed: no notifyChange for $docUri",
				uploadDone.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
			)
		} finally {
			resolver.unregisterContentObserver(observer)
		}
		assertNotificationsClear(notificationManager, "upload")
	}

	private fun assertNotificationsClear(notificationManager: NotificationManager, phase: String) {
		val deadline = System.currentTimeMillis() + CLEAR_TIMEOUT_MS
		while (System.currentTimeMillis() < deadline &&
			notificationManager.activeNotifications.isNotEmpty()
		) {
			Thread.sleep(POLL_INTERVAL_MS)
		}
		val leaked = notificationManager.activeNotifications
		assertEquals(
			"$phase left ${leaked.size} stale transfer notification(s): " +
				leaked.joinToString { "#${it.id} '${it.notification.extras.getString("android.text")}'" },
			0,
			leaked.size
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

}
