package com.tylerabitbol.libra.services.secrets

import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swift: `Keychain error reporting` in `NetworkingTests.swift`. */
class KeychainErrorTest {

    @Test
    fun errSecMissingEntitlementIsExplainedAsABuildProblemNotAUserError() {
        val message = SecretsError.explain(OSStatusCode.MISSING_ENTITLEMENT)
        assertContains(message, "entitlements")
        assertTrue(
            message.contains("not"),
            "Must make clear this isn't the user's fault",
        )
        assertFalse(
            message.contains("-34018"),
            "A bare OSStatus is what made this hard to diagnose in the first place",
        )
    }

    @Test
    fun aLockedKeychainSuggestsUnlockingRatherThanReportingACode() {
        assertContains(
            SecretsError.explain(OSStatusCode.INTERACTION_NOT_ALLOWED).lowercase(),
            "unlock",
        )
    }

    /**
     * No Swift counterpart. `DUPLICATE_ITEM` sat in the constant table with no
     * case in `explain`, which meant the one status `SecItemAdd` is most likely
     * to return fell through to the bare-code branch.
     */
    @Test
    fun errSecDuplicateItemSaysToRemoveTheKeyRatherThanReportingACode() {
        val message = SecretsError.explain(OSStatusCode.DUPLICATE_ITEM)
        assertContains(message.lowercase(), "already exists")
        assertContains(message.lowercase(), "remove")
        assertFalse(
            message.contains("${OSStatusCode.DUPLICATE_ITEM}"),
            "A mapped status must not fall through to the raw-code branch",
        )
    }

    @Test
    fun unmappedStatusesStillReportTheRawCodeSoNothingIsSwallowed() {
        val message = SecretsError.explain(OSStatusCode.DECODE)
        assertContains(message, "${OSStatusCode.DECODE}")
    }

    @Test
    fun theSystemMessageIsIncludedWhenThePlatformSuppliesOne() {
        val message = SecretsError.explain(OSStatusCode.DECODE, systemMessage = "Unable to decode")
        assertContains(message, "Unable to decode")
        assertContains(message, "${OSStatusCode.DECODE}")
    }

    @Test
    fun aHealthyStoreReportsAvailableWithNoReasonToShow() {
        val health = InMemorySecretsStore().diagnose()
        assertTrue(health.isAvailable)
        assertNull(health.reason)
    }

    @Test
    fun anUnavailableStoreCarriesAnExplanationForTheUI() {
        val health = SecretsHealth.Unavailable("signed without entitlements")
        assertFalse(health.isAvailable)
        assertEquals("signed without entitlements", health.reason)
    }

    @Test
    fun aThrownSecretsErrorCarriesTheExplanationAsItsMessage() {
        val error = SecretsError(OSStatusCode.MISSING_ENTITLEMENT)
        assertEquals(OSStatusCode.MISSING_ENTITLEMENT, error.status)
        assertContains(error.message ?: "", "entitlements")
    }
}

/** Swift: `Key fingerprints` in `NetworkingTests.swift`. */
class KeyFingerprintTest {

    @Test
    fun aStoredKeyIsFingerprintedByLengthAndEdgesNeverShownWhole() {
        val store = InMemorySecretsStore()
        store.set("0123456789abcdef0123456789abcdef01234567", SecretKey.TiingoAPIKey)
        val fingerprint = assertNotNull(store.fingerprint(SecretKey.TiingoAPIKey))

        assertContains(fingerprint, "40 chars")
        assertContains(fingerprint, "0123")
        assertContains(fingerprint, "4567")
        assertFalse(
            fingerprint.contains("456789abcdef0123456789abcdef0123"),
            "The middle of a credential must never be displayed",
        )
    }

    @Test
    fun lengthIsWhatCatchesATruncatedPaste() {
        val store = InMemorySecretsStore()
        store.set("0123456789abcdef0123456789abcdef012345", SecretKey.TiingoAPIKey) // 38
        assertContains(assertNotNull(store.fingerprint(SecretKey.TiingoAPIKey)), "38 chars")
    }

    @Test
    fun aValueTooShortToMaskIsFlaggedRatherThanPartiallyRevealed() {
        val store = InMemorySecretsStore()
        store.set("abc123", SecretKey.FinnhubAPIKey)
        val fingerprint = assertNotNull(store.fingerprint(SecretKey.FinnhubAPIKey))
        assertContains(fingerprint, "too short")
        assertFalse(fingerprint.contains("abc1"))
    }

    @Test
    fun theSECContactEmailIsShownInFullBeingAnAddressRatherThanASecret() {
        val store = InMemorySecretsStore()
        store.set("someone@example.com", SecretKey.SecContactEmail)
        assertEquals("someone@example.com", store.fingerprint(SecretKey.SecContactEmail))
    }

    @Test
    fun nothingStoredYieldsNoFingerprint() {
        assertNull(InMemorySecretsStore().fingerprint(SecretKey.TiingoAPIKey))
    }

    @Test
    fun aWhitespaceOnlyValueYieldsNoFingerprint() {
        val store = InMemorySecretsStore()
        store.set("   ", SecretKey.TiingoAPIKey)
        assertNull(store.fingerprint(SecretKey.TiingoAPIKey))
    }

    @Test
    fun theFingerprintIsTakenAfterTrimmingSoAPastedNewlineDoesNotInflateTheLength() {
        val store = InMemorySecretsStore()
        store.set("0123456789abcdef0123456789abcdef01234567\n", SecretKey.TiingoAPIKey)
        assertContains(assertNotNull(store.fingerprint(SecretKey.TiingoAPIKey)), "40 chars")
    }
}

/** Swift: `Secrets store` in `NetworkingTests.swift`. */
class SecretsStoreTest {

    @Test
    fun anUnsetKeyThrowsTheErrorTheUICanActOn() {
        val store = InMemorySecretsStore()
        val error = assertFailsWith<APIError.MissingCredentials> {
            store.require(SecretKey.FinnhubAPIKey, DataProviderID.Finnhub)
        }
        assertEquals(DataProviderID.Finnhub, error.providerID)
    }

    @Test
    fun aWhitespaceOnlyKeyCountsAsUnset() {
        val store = InMemorySecretsStore()
        store.set("   ", SecretKey.FredAPIKey)
        assertFalse(store.hasValue(SecretKey.FredAPIKey))
        assertFailsWith<APIError.MissingCredentials> {
            store.require(SecretKey.FredAPIKey, DataProviderID.FRED)
        }
    }

    @Test
    fun theSECContactEmailIsNotMaskedUnlikeTheAPIKeys() {
        assertFalse(SecretKey.SecContactEmail.isSensitive)
        assertFalse(SecretKey.SecOrganizationName.isSensitive)
        assertTrue(SecretKey.FinnhubAPIKey.isSensitive)
        assertTrue(SecretKey.AlpacaSecretKey.isSensitive)
    }

    @Test
    fun requireReturnsTheTrimmedValue() {
        val store = InMemorySecretsStore(mapOf(SecretKey.FredAPIKey to "  abcdef  "))
        assertEquals("abcdef", store.require(SecretKey.FredAPIKey, DataProviderID.FRED))
    }

    @Test
    fun settingNullOrEmptyDeletesTheValue() {
        val store = InMemorySecretsStore(mapOf(SecretKey.TiingoAPIKey to "value"))
        store.set(null, SecretKey.TiingoAPIKey)
        assertNull(store.value(SecretKey.TiingoAPIKey))

        store.set("value", SecretKey.TiingoAPIKey)
        store.set("", SecretKey.TiingoAPIKey)
        assertNull(store.value(SecretKey.TiingoAPIKey))
    }

    @Test
    fun aSeededStoreReadsBackWhatItWasGiven() {
        val store = InMemorySecretsStore(
            mapOf(
                SecretKey.AlpacaKeyID to "id",
                SecretKey.AlpacaSecretKey to "secret",
            ),
        )
        assertEquals("id", store.value(SecretKey.AlpacaKeyID))
        assertEquals("secret", store.value(SecretKey.AlpacaSecretKey))
        assertFalse(store.hasValue(SecretKey.FinnhubAPIKey))
    }

    @Test
    fun storageNamesAreStableSoAnUpgradeStillFindsWhatTheUserEntered() {
        // These strings are written into the keychain and into
        // SharedPreferences. Renaming one orphans a stored credential, so they
        // are pinned rather than derived from the enum case name.
        assertEquals(
            listOf(
                "finnhubAPIKey",
                "tiingoAPIKey",
                "alpacaKeyID",
                "alpacaSecretKey",
                "fredAPIKey",
                "secContactEmail",
                "secOrganizationName",
            ),
            SecretKey.entries.map { it.raw },
        )
    }

    @Test
    fun everyKeyHasADisplayNameAndHelpText() {
        for (key in SecretKey.entries) {
            assertTrue(key.displayName.isNotBlank(), "${key.name} has no display name")
            assertTrue(key.helpText.isNotBlank(), "${key.name} has no help text")
        }
    }

    @Test
    fun keysRoundTripThroughTheirStorageName() {
        for (key in SecretKey.entries) {
            assertEquals(key, SecretKey.fromRaw(key.raw))
        }
        assertNull(SecretKey.fromRaw("notAKey"))
        assertNull(SecretKey.fromRaw(null))
    }

    @Test
    fun noHelpTextLeaksACredential() {
        // Section 19: a key never appears in the UI or in source. The help
        // strings point at the provider's website and nothing else.
        for (key in SecretKey.entries) {
            assertFalse(
                key.helpText.contains("sk_") || key.helpText.contains("Bearer "),
                "${key.name} help text looks like it contains a credential",
            )
        }
    }
}
