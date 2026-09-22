package com.tylerabitbol.libra.services.secrets

/**
 * The Security-framework status codes this app knows how to explain.
 *
 * Swift read these from the `Security` module. They are stable ABI constants,
 * so they are restated here in `commonMain`: that keeps [SecretsError.explain]
 * — the part with the user-facing judgement in it — testable on every target
 * rather than only on iOS.
 */
object OSStatusCode {
    const val SUCCESS = 0
    const val NOT_AVAILABLE = -25291
    const val AUTH_FAILED = -25293
    const val DUPLICATE_ITEM = -25299
    const val ITEM_NOT_FOUND = -25300
    const val INTERACTION_NOT_ALLOWED = -25308
    const val DECODE = -26275
    const val MISSING_ENTITLEMENT = -34018
}

/** Thrown when secure storage refuses a write. */
class SecretsError(
    val status: Int,
    val explanation: String = SecretsError.explain(status),
) : Exception(explanation) {

    companion object {
        /**
         * Turns a status code into something the user can act on.
         *
         * `-34018` in particular is not a user error and not a transient
         * glitch: it means the build was signed without keychain entitlements.
         * Printing the bare code sent an earlier debugging session down the
         * wrong path.
         *
         * `-25299` reaches here from `SecItemAdd`, which the store calls only
         * after `SecItemUpdate` reported the item missing. A duplicate at that
         * point means an entry exists that the update query did not match, so
         * the advice is to remove the key rather than to retry.
         *
         * [systemMessage] is what `SecCopyErrorMessageString` returned, which
         * only iOS can supply; the fallback keeps the raw code visible so an
         * unmapped status is never swallowed.
         */
        fun explain(status: Int, systemMessage: String? = null): String = when (status) {
            OSStatusCode.MISSING_ENTITLEMENT ->
                "This build can't use the Keychain because it was signed without the " +
                    "required entitlements. That's a build configuration problem, not " +
                    "something you did — code signing must be enabled with a development team."
            OSStatusCode.INTERACTION_NOT_ALLOWED ->
                "The Keychain is locked. Unlock the device and try again."
            OSStatusCode.AUTH_FAILED ->
                "The Keychain refused access. You may need to unlock the device."
            OSStatusCode.NOT_AVAILABLE ->
                "The Keychain is unavailable on this device right now."
            OSStatusCode.DUPLICATE_ITEM ->
                "A Keychain entry for this key already exists but could not be " +
                    "updated. Remove the key and enter it again."
            else ->
                "Keychain error $status: ${systemMessage ?: "unknown error"}"
        }
    }
}
