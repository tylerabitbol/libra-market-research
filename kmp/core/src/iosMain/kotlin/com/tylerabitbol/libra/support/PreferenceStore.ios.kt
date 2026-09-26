package com.tylerabitbol.libra.support

import platform.Foundation.NSUserDefaults

/** `NSUserDefaults`, which is what the Swift app used. */
class UserDefaultsPreferenceStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : PreferenceStore {
    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun setString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, key)
    }
}
