package com.tylerabitbol.libra.services.secrets

import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Whether secure storage actually works right now.
 *
 * This exists because a misconfigured build breaks the keystore in a way that
 * is invisible from the outside: writes throw, but *reads* just return null,
 * so the app cheerfully reports "key not set" for a key the user already
 * entered. Asking the store to prove it works is the only reliable way to tell.
 */
sealed class SecretsHealth {
    /** The explanation to put in front of the user, or null when all is well. */
    open val reason: String? get() = null

    val isAvailable: Boolean get() = this is Available

    data object Available : SecretsHealth()

    /** Carries an explanation the user can act on, not an error code. */
    data class Unavailable(override val reason: String) : SecretsHealth()
}

/**
 * Read/write access to stored credentials.
 *
 * Swift declared this as a `protocol` with default implementations in an
 * extension; the Kotlin shape is the same, with [hasValue], [fingerprint],
 * [require] and [diagnose] defaulted on the interface. `PLAN.md §5` called for
 * an `expect class`, which would have made the in-memory implementation the
 * tests need impossible to express — see `KNOWN_ISSUES.md`.
 */
interface SecretsStore {
    fun value(key: SecretKey): String?

    /** Passing null or an empty string deletes the stored value. */
    fun set(value: String?, key: SecretKey)

    /** Proves the store can round-trip a value, rather than assuming it can. */
    fun diagnose(): SecretsHealth = SecretsHealth.Available

    fun hasValue(key: SecretKey): Boolean = !value(key)?.trim().isNullOrEmpty()

    /**
     * A masked fingerprint of a stored value: its length and first/last four
     * characters, e.g. "40 chars · d54d…4940".
     *
     * Enough to compare against the key shown in a provider's dashboard —
     * which is how a truncated paste or a key entered in the wrong field gets
     * caught — without putting the credential on screen. Returns null when
     * nothing is stored, and refuses to fingerprint a value too short to mask.
     */
    fun fingerprint(key: SecretKey): String? {
        val value = value(key)?.trim()
        if (value.isNullOrEmpty()) return null

        // Email addresses aren't secrets and are more useful shown in full.
        if (!key.isSensitive) return value

        if (value.length < 12) return "${value.length} chars · too short to be valid"
        return "${value.length} chars · ${value.take(4)}…${value.takeLast(4)}"
    }

    /** Returns the value or throws the error the UI knows how to act on. */
    fun require(key: SecretKey, provider: DataProviderID): String {
        val value = value(key)?.trim()
        if (value.isNullOrEmpty()) throw APIError.MissingCredentials(provider)
        return value
    }
}

/**
 * In-memory store for previews and tests, so neither ever touches the real
 * keystore or requires real credentials.
 *
 * Swift guarded its dictionary with an `NSLock`. The equivalent here is an
 * atomic reference to an immutable map updated by compare-and-set: the store
 * is not a suspending interface, so a `Mutex` is unavailable, and a bare
 * `MutableMap` shared across threads is a data race under Kotlin/Native's
 * memory model.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemorySecretsStore(seed: Map<SecretKey, String> = emptyMap()) : SecretsStore {
    private val storage = AtomicReference(seed.toMap())

    override fun value(key: SecretKey): String? = storage.load()[key]

    override fun set(value: String?, key: SecretKey) {
        while (true) {
            val current = storage.load()
            val updated =
                if (value.isNullOrEmpty()) current - key else current + (key to value)
            if (storage.compareAndSet(current, updated)) return
        }
    }
}
