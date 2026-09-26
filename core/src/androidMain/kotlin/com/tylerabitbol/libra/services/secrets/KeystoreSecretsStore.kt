package com.tylerabitbol.libra.services.secrets

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey as JavaSecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Android counterpart of Swift's `KeychainSecretsStore`.
 *
 * Android has no keychain that stores arbitrary secrets directly, so this is
 * the documented two-part arrangement: a non-exportable AES key lives in the
 * AndroidKeyStore (hardware-backed where the device supports it) and encrypts
 * each value into an ordinary `SharedPreferences` file. Neither half is useful
 * alone — the preferences hold only ciphertext, and the key cannot leave the
 * keystore.
 *
 * `androidx.security:security-crypto` would have done this, but it is
 * deprecated and unmaintained, so `PLAN.md §5` calls for the primitives
 * directly.
 *
 * Each record is `Base64(iv ‖ ciphertext)`. GCM's 12-byte IV is generated per
 * write by the provider and prefixed, so re-saving the same value produces
 * different ciphertext, and the authentication tag makes tampering a decrypt
 * failure rather than a silently wrong credential.
 */
class KeystoreSecretsStore(
    private val preferences: SharedPreferences,
    private val keyAlias: String = "com.tylerabitbol.libra.secrets",
) : SecretsStore {

    constructor(
        context: Context,
        name: String = "com.tylerabitbol.libra.secrets",
        keyAlias: String = "com.tylerabitbol.libra.secrets",
    ) : this(
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE),
        keyAlias,
    )

    override fun value(key: SecretKey): String? {
        val stored = preferences.getString(key.raw, null) ?: return null
        return try {
            decrypt(stored)
        } catch (_: GeneralSecurityException) {
            // The keystore entry was replaced or cleared — a factory reset, a
            // restored backup, a changed lock screen on some devices. The
            // ciphertext is unrecoverable, so drop it and report "not set"
            // rather than throwing from a read the whole UI depends on.
            preferences.edit().remove(key.raw).apply()
            null
        }
    }

    override fun set(value: String?, key: SecretKey) {
        val editor = preferences.edit()
        if (value.isNullOrEmpty()) {
            editor.remove(key.raw)
        } else {
            try {
                editor.putString(key.raw, encrypt(value))
            } catch (error: GeneralSecurityException) {
                throw SecretsError(
                    OSStatusCode.NOT_AVAILABLE,
                    "Android couldn't encrypt the value: ${error.message ?: "keystore error"}",
                )
            }
        }
        editor.apply()
    }

    /**
     * Writes, reads back, and deletes a throwaway value — the same capability
     * check Swift performs, for the same reason: a keystore that has lost its
     * key reads as empty rather than as broken.
     */
    override fun diagnose(): SecretsHealth = try {
        val probe = "ok"
        val roundTripped = decrypt(encrypt(probe))
        if (roundTripped == probe) {
            SecretsHealth.Available
        } else {
            SecretsHealth.Unavailable(
                "The keystore returned a different value than was written.",
            )
        }
    } catch (error: GeneralSecurityException) {
        SecretsHealth.Unavailable(
            "This device's keystore is unavailable: ${error.message ?: "unknown error"}",
        )
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(record: String): String {
        val bytes = Base64.decode(record, Base64.NO_WRAP)
        if (bytes.size <= IV_LENGTH) throw GeneralSecurityException("Stored record is truncated")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH),
        )
        return cipher
            .doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
            .toString(Charsets.UTF_8)
    }

    private fun secretKey(): JavaSecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // Deliberately not user-authentication-bound: background
                // refreshes need the keys while the screen is off, which is
                // exactly what `kSecAttrAccessibleAfterFirstUnlock` allows on
                // iOS. Keeping the two platforms aligned matters more here
                // than a stricter Android-only rule.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
