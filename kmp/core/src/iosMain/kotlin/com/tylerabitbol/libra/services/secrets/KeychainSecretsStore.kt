package com.tylerabitbol.libra.services.secrets

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import platform.Security.SecCopyErrorMessageString
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Keychain-backed implementation using a generic password item per key.
 *
 * A direct port of Swift's `KeychainSecretsStore`: same service name, same
 * account names, same `kSecAttrAccessibleAfterFirstUnlock` accessibility, so
 * an existing install's stored keys are found unchanged.
 *
 * Swift's `query as CFDictionary` was an ARC-managed bridge. Kotlin/Native has
 * no such bridge, so every dictionary here is built through [withCFDictionary],
 * which creates a real `CFMutableDictionary`, fills it, and releases it after
 * the call. Doing it inline would leak one dictionary per keychain access.
 *
 * It is built that way rather than by bridging a Kotlin `Map` because the
 * `kSec…` names are `CFStringRef` *pointers*, not objects: bridging a map that
 * contains them produces a dictionary whose keys are opaque Kotlin wrappers,
 * which the Security framework does not recognise, and every call fails with
 * `errSecParam` (-50). Only `SecItemAdd` reported it — the read and the delete
 * treat any non-success as "not there" — which is why it looked like a problem
 * with the write.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecretsStore(
    private val service: String = "com.tylerabitbol.libra.secrets",
) : SecretsStore {

    override fun value(key: SecretKey): String? = memScoped {
        val query = baseQuery(key.raw) + listOf(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val item = alloc<CFTypeRefVar>()
        val status = withCFDictionary(query) { SecItemCopyMatching(it, item.ptr) }
        if (status != OSStatusCode.SUCCESS) return@memScoped null
        (CFBridgingRelease(item.value) as? NSData)?.decodeUTF8()
    }

    override fun set(value: String?, key: SecretKey) {
        val query = baseQuery(key.raw)

        if (value.isNullOrEmpty()) {
            val status = withCFDictionary(query) { SecItemDelete(it) }
            if (status != OSStatusCode.SUCCESS && status != OSStatusCode.ITEM_NOT_FOUND) {
                throw secretsError(status)
            }
            return
        }

        val data = value.toNSData()
        val updateStatus = withCFDictionary(query) { q ->
            withCFDictionary(listOf(kSecValueData to data)) { SecItemUpdate(q, it) }
        }

        when (updateStatus) {
            OSStatusCode.SUCCESS -> return
            OSStatusCode.ITEM_NOT_FOUND -> {
                val insert = query + listOf(
                    kSecValueData to data,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
                )
                val addStatus = withCFDictionary(insert) { SecItemAdd(it, null) }
                if (addStatus != OSStatusCode.SUCCESS) throw secretsError(addStatus)
            }
            else -> throw secretsError(updateStatus)
        }
    }

    /**
     * Writes, reads back, and deletes a throwaway value.
     *
     * A capability check rather than an inference: entitlement problems, a
     * locked device, and a corrupt keychain all present differently, and only
     * an actual round-trip distinguishes "works" from "silently empty".
     */
    override fun diagnose(): SecretsHealth = memScoped {
        val account = "__healthcheck__"
        val probe = "ok"

        // Clear any residue from an interrupted earlier check.
        withCFDictionary(baseQuery(account)) { SecItemDelete(it) }

        val insert = baseQuery(account) + listOf(
            kSecValueData to probe.toNSData(),
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
        )
        val addStatus = withCFDictionary(insert) { SecItemAdd(it, null) }
        if (addStatus != OSStatusCode.SUCCESS) {
            return@memScoped SecretsHealth.Unavailable(explain(addStatus))
        }

        try {
            val read = baseQuery(account) + listOf(
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            )
            val item = alloc<CFTypeRefVar>()
            val readStatus = withCFDictionary(read) { SecItemCopyMatching(it, item.ptr) }
            if (readStatus != OSStatusCode.SUCCESS) {
                return@memScoped SecretsHealth.Unavailable(explain(readStatus))
            }

            val readBack = (CFBridgingRelease(item.value) as? NSData)?.decodeUTF8()
            if (readBack != probe) {
                return@memScoped SecretsHealth.Unavailable(
                    "The Keychain returned a different value than was written.",
                )
            }
            SecretsHealth.Available
        } finally {
            withCFDictionary(baseQuery(account)) { SecItemDelete(it) }
        }
    }

    /** An entry of a keychain query: a `kSec…` key, and a CF or Kotlin value. */
    private fun baseQuery(account: String): List<Pair<CFStringRef?, Any?>> = listOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        kSecAttrAccount to account,
    )

    /**
     * Builds a `CFMutableDictionary`, runs [block] against it, and releases it.
     *
     * A value that is already a CF pointer — every `kSec…` constant — is stored
     * as it is. Anything else is a Kotlin object (a `String`, the `NSData` of a
     * secret) and is bridged, which retains it; the dictionary's own `kCFType`
     * callbacks retain it again, so the bridge's reference is given back here.
     */
    private fun <R> withCFDictionary(
        entries: List<Pair<CFStringRef?, Any?>>,
        block: (CFDictionaryRef?) -> R,
    ): R {
        val bridged = mutableListOf<COpaquePointer>()
        val dictionary = CFDictionaryCreateMutable(
            null,
            entries.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        try {
            for ((key, value) in entries) {
                val cfValue: COpaquePointer? = when (value) {
                    null -> null
                    is CPointer<*> -> value
                    else -> CFBridgingRetain(value)?.also { bridged += it }
                }
                CFDictionarySetValue(dictionary, key, cfValue)
            }
            return block(dictionary)
        } finally {
            bridged.forEach { CFRelease(it) }
            if (dictionary != null) CFRelease(dictionary)
        }
    }

    private fun secretsError(status: Int) = SecretsError(status, explain(status))

    private fun explain(status: Int): String =
        SecretsError.explain(status, systemMessage(status))

    private fun systemMessage(status: Int): String? =
        CFBridgingRelease(SecCopyErrorMessageString(status, null)) as? String

    // NSString does not bridge to kotlin.String the way CFString does, so the
    // two conversions go through raw bytes instead of a cast.

    private fun NSData.decodeUTF8(): String {
        val bytes = ByteArray(length.toInt())
        if (bytes.isEmpty()) return ""
        bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
        return bytes.decodeToString()
    }

    private fun String.toNSData(): NSData {
        val bytes = encodeToByteArray()
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned {
            NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
        }
    }
}
