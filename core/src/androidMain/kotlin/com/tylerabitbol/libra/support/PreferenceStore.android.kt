package com.tylerabitbol.libra.support

import android.content.Context
import android.content.SharedPreferences

/** `SharedPreferences`, the Android counterpart of `NSUserDefaults`. */
class SharedPreferencesStore(
    private val preferences: SharedPreferences
) : PreferenceStore {

    constructor(context: Context, name: String = "com.tylerabitbol.libra.preferences") : this(
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    )

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun setString(key: String, value: String?) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
