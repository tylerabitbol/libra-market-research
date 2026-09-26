package com.tylerabitbol.libra.fixtures

import com.tylerabitbol.libra.networking.HTTPClient
import kotlinx.serialization.json.Json

/**
 * Loads the JSON captured from real API responses during Phase 2 planning.
 *
 * Every provider test decodes a real payload rather than a hand-written one.
 * Hand-authored fixtures drift from what a vendor actually sends and hide
 * exactly the surprises worth catching — Finnhub's zero-filled quotes for
 * unknown symbols, FRED's "." for market holidays.
 *
 * The files live at `core/src/commonTest/resources/fixtures` and are copied
 * from `LibraTests/Fixtures` unchanged. They reach the test binary through the
 * `generateFixtures` Gradle task rather than through a resource bundle, which
 * Kotlin/Native test binaries do not have — see `KNOWN_ISSUES.md`.
 */
object Fixture {

    /** Every fixture name currently available, for the coverage check. */
    val names: Set<String> get() = fixtureContents.keys

    fun text(name: String, extension: String = "json"): String {
        val file = "$name.$extension"
        return fixtureContents[file] ?: throw IllegalArgumentException(
            "Fixture $file not found. Check it is in " +
                "core/src/commonTest/resources/fixtures — the generator picks up " +
                "everything in that directory, so a missing file is a missing copy, " +
                "not a missing registration.",
        )
    }

    inline fun <reified T> decode(name: String, json: Json = HTTPClient.libraJson): T =
        json.decodeFromString(text(name))
}
