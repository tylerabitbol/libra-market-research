package com.tylerabitbol.libra.support

import kotlin.random.Random

/**
 * A UUID-shaped random identifier.
 *
 * Swift used `UUID()`. Kotlin's `kotlin.uuid.Uuid` is still opt-in, and these
 * ids are only ever identity for UI lists — never persisted as a key, never
 * parsed — so a hand-rolled v4-shaped string avoids the experimental API.
 */
fun randomId(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    for (i in 0 until 32) {
        when (i) {
            8, 12, 16, 20 -> sb.append('-')
        }
        val nibble = when (i) {
            12 -> 4                                   // version 4
            16 -> 8 + Random.nextInt(4)               // variant 10xx
            else -> Random.nextInt(16)
        }
        sb.append(hex[nibble])
    }
    return sb.toString()
}
