package com.tylerabitbol.libra.support

/**
 * Small key-value storage for user preferences.
 *
 * Swift passed a `UserDefaults` into `SavedScreens.load(from:)`, which is the
 * same shape as an injected interface — the test handed it a throwaway suite
 * rather than the standard one. Kept as an interface rather than an
 * `expect class` for exactly that reason: the in-memory implementation below
 * *is* the test double, and no platform needs to supply one.
 *
 * Preferences are not observations. They do not belong in the append-only
 * store, which is why this exists separately from `SnapshotStore`.
 */
interface PreferenceStore {
    fun getString(key: String): String?
    fun setString(key: String, value: String?)
}

/** For tests and previews. */
class InMemoryPreferenceStore(
    initial: Map<String, String> = emptyMap()
) : PreferenceStore {
    private val values = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun setString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
