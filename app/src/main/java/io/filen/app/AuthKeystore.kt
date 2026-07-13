package io.filen.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the AES-256-GCM data-encryption key (DEK) that encrypts `auth.json`.
 *
 * The DEK is wrapped by a non-exportable AndroidKeyStore key (the Keystore can't hand back raw key
 * bytes, but the Rust cache needs them), and the wrapped blob is stored in filesDir. A filesystem
 * reader gets only the wrapped blob, which is inert without the hardware-backed Keystore key.
 *
 * Shared, same-UID, between the app's native module (provision + hand the raw DEK to JS for
 * encryption) and [FilenDocumentsProvider] (unwrap the DEK to pass into the Rust cache).
 *
 * The wrap key carries NO auth/unlock constraint: the DocumentsProvider runs headless — including
 * while the screen is locked after the first post-boot unlock — with no UI to prompt for auth.
 */
object AuthKeystore {
	private const val KEYSTORE = "AndroidKeyStore"
	private const val WRAP_KEY_ALIAS = "filen_auth_dek_wrap"
	private const val WRAPPED_DEK_FILENAME = "auth_dek.bin"
	private const val DEK_SIZE_BYTES = 32
	private const val GCM_IV_SIZE_BYTES = 12
	private const val GCM_TAG_BITS = 128

	private fun wrappedDekFile(filesDir: String) = File(filesDir, WRAPPED_DEK_FILENAME)

	private fun getOrCreateWrapKey(): SecretKey {
		val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(keyStore.getEntry(WRAP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				WRAP_KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build()
		)
		return generator.generateKey()
	}

	/**
	 * Provision (idempotent): ensure the wrap key + a wrapped DEK exist, returning the raw 32-byte DEK.
	 * Called by the app when enabling the provider. Throws if no secure hardware is available so the
	 * caller can fail closed (never write a plaintext / unprotected fallback).
	 */
	@Synchronized
	fun getOrCreateDek(filesDir: String): ByteArray {
		loadDek(filesDir)?.let { return it }

		val dek = ByteArray(DEK_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
		val iv = cipher.iv // 12 bytes for GCM
		val wrapped = cipher.doFinal(dek)
		wrappedDekFile(filesDir).writeBytes(iv + wrapped)
		return dek
	}

	/**
	 * Unwrap the DEK if it exists; null when not provisioned or on any failure. Callers (the provider
	 * and the app) treat null as "no key" and fail closed (the Rust cache then reports unauthenticated).
	 */
	@Synchronized
	fun loadDek(filesDir: String): ByteArray? {
		return try {
			val file = wrappedDekFile(filesDir)
			if (!file.exists()) return null
			val bytes = file.readBytes()
			if (bytes.size <= GCM_IV_SIZE_BYTES) return null

			val iv = bytes.copyOfRange(0, GCM_IV_SIZE_BYTES)
			val wrapped = bytes.copyOfRange(GCM_IV_SIZE_BYTES, bytes.size)

			val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
			val entry = keyStore.getEntry(WRAP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null

			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.DECRYPT_MODE, entry.secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
			cipher.doFinal(wrapped)
		} catch (e: Exception) {
			null
		}
	}

	/** Purge on logout: delete the wrapped DEK and the Keystore wrap key. Best-effort. */
	@Synchronized
	fun purge(filesDir: String) {
		try {
			wrappedDekFile(filesDir).delete()
		} catch (_: Exception) {
		}
		try {
			val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
			if (keyStore.containsAlias(WRAP_KEY_ALIAS)) {
				keyStore.deleteEntry(WRAP_KEY_ALIAS)
			}
		} catch (_: Exception) {
		}
	}
}
