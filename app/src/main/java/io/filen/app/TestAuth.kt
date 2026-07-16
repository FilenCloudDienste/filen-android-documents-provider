package io.filen.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Shared auth bootstrap for the test harness ([TestActivity] and the instrumented tests) —
 * NOT copied into the real app by the Expo plugin.
 *
 * Since the auth.json DEK change the Rust cache only accepts an encrypted auth file
 * (version 0x01 ++ nonce(12) ++ ciphertext ++ tag(16), AES-256-GCM, no AAD — the SDK's
 * DataCrypter format after the version byte), so the harness encrypts with the same
 * Keystore-provisioned DEK the provider unwraps ([AuthKeystore.getOrCreateDek] is
 * idempotent and same-UID with the app under test).
 *
 * The session itself is a pre-obtained one injected via BuildConfig (from `.env` /
 * env vars at build time), so there is no live login and no secrets in the repo.
 */
object TestAuth {
	private const val AUTH_FILE_VERSION: Byte = 0x01

	/**
	 * Writes the encrypted auth file with the BuildConfig session and returns the raw DEK, which
	 * callers pass to [uniffi.filen_mobile_native_cache.FilenMobileCacheState]'s constructor.
	 */
	fun provision(filesDir: String, authFile: Path): ByteArray {
		val dek = AuthKeystore.getOrCreateDek(filesDir)
		writeSealed(dek, authFile, sessionJson())
		return dek
	}

	/** Writes an encrypted providerEnabled=false auth file (the "logged out" harness state). */
	fun writeDisabled(filesDir: String, authFile: Path) {
		val dek = AuthKeystore.getOrCreateDek(filesDir)
		writeSealed(
			dek,
			authFile,
			"""
			{
				"providerEnabled": false,
				"sdkConfig": null
			}
			""".trimIndent()
		)
	}

	private fun writeSealed(dek: ByteArray, authFile: Path, plaintext: String) {
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"))
		// Cipher.doFinal returns ciphertext ++ tag(16); cipher.iv is the 12-byte GCM nonce.
		val sealed = byteArrayOf(AUTH_FILE_VERSION) + cipher.iv + cipher.doFinal(plaintext.toByteArray())
		Files.write(
			authFile,
			sealed,
			StandardOpenOption.WRITE,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)
	}

	private fun sessionJson(): String = """
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
}
