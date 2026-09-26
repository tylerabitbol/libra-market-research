package com.tylerabitbol.libra.services.secrets

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The only tests in the project that touch a real `AndroidKeyStore`.
 *
 * No Swift counterpart: Swift's Keychain store is verified by `SelfTest` on a
 * signed build, because a test bundle has no entitlements. Android has no such
 * restriction — an instrumentation test runs inside a real app process — so the
 * store that was previously only compile-verified can be exercised directly.
 *
 * Each test uses its own preferences file and its own key alias, so a failure
 * cannot leave state that changes the next run.
 */
class KeystoreSecretsStoreTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val alias = "com.tylerabitbol.libra.test.${System.nanoTime()}"
    private val prefsName = "com.tylerabitbol.libra.test.prefs"

    private fun store() = KeystoreSecretsStore(
        preferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        keyAlias = alias,
    )

    private fun rawRecord(key: SecretKey): String? = context
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        .getString(key.raw, null)

    @BeforeTest
    @AfterTest
    fun clear() {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aValueSurvivesAWriteAndAReadAndIsGoneAfterADelete() {
        val store = store()
        assertNull(store.value(SecretKey.FinnhubAPIKey), "Nothing is stored yet")

        store.set("finnhub-secret-value", SecretKey.FinnhubAPIKey)
        assertEquals("finnhub-secret-value", store.value(SecretKey.FinnhubAPIKey))

        store.set(null, SecretKey.FinnhubAPIKey)
        assertNull(store.value(SecretKey.FinnhubAPIKey))
        assertNull(rawRecord(SecretKey.FinnhubAPIKey), "The record itself must be removed")
    }

    @Test
    fun thePreferencesHoldCiphertextRatherThanTheValue() {
        val store = store()
        store.set("finnhub-secret-value", SecretKey.FinnhubAPIKey)

        val record = rawRecord(SecretKey.FinnhubAPIKey)
        assertTrue(record != null && record.isNotEmpty(), "A record must be written")
        assertTrue(
            !record.contains("finnhub-secret-value"),
            "The value must never appear in the preferences file",
        )
    }

    /**
     * GCM's per-write IV is the reason: without it, two writes of the same
     * value would produce identical records, and a reader of the preferences
     * file could tell that two keys hold the same secret.
     */
    @Test
    fun writingTheSameValueTwiceProducesDifferentCiphertext() {
        val store = store()

        store.set("identical", SecretKey.FinnhubAPIKey)
        val first = rawRecord(SecretKey.FinnhubAPIKey)

        store.set("identical", SecretKey.FinnhubAPIKey)
        val second = rawRecord(SecretKey.FinnhubAPIKey)

        assertNotEquals(first, second, "A per-write IV must make the records differ")
        assertEquals("identical", store.value(SecretKey.FinnhubAPIKey), "Both still decrypt")
    }

    /**
     * The keystore entry can vanish under a record that outlives it — a
     * restored backup, a cleared lock screen. The value is unrecoverable
     * either way, so the read drops the orphan and reports "not set" rather
     * than throwing from a read the whole settings screen depends on.
     */
    @Test
    fun anOrphanedRecordIsDroppedRatherThanThrowing() {
        val store = store()
        store.set("will-be-orphaned", SecretKey.TiingoAPIKey)
        assertTrue(rawRecord(SecretKey.TiingoAPIKey) != null)

        // Delete the key the record was encrypted under. The next read
        // generates a fresh key, and GCM's tag check fails against it.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        assertNull(store.value(SecretKey.TiingoAPIKey), "An undecryptable record reads as not set")
        assertNull(rawRecord(SecretKey.TiingoAPIKey), "And the orphan is removed")
    }

    @Test
    fun diagnoseReportsTheKeystoreIsAvailable() {
        assertEquals(SecretsHealth.Available, store().diagnose())
    }
}
