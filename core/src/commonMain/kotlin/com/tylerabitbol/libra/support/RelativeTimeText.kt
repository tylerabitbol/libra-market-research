package com.tylerabitbol.libra.support

import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Renders "Updated 4 minutes ago" strings.
 *
 * The spec calls this out specifically: the UI should say how old the data is
 * rather than leaving the user to guess.
 *
 * Swift used `RelativeDateTimeFormatter` with `.full`/`.numeric`, which has no
 * multiplatform equivalent, so the unit selection is written out. It is an
 * approximation of ICU at the month and year boundaries (calendar months vary
 * in length); nothing in the app depends on precision above weeks.
 */
object RelativeTimeText {

    fun string(date: Instant, now: Instant = Clock.System.now()): String {
        val seconds = (now - date).inWholeSeconds
        // Anything under a few seconds reads oddly as "in 0 seconds".
        if (abs(seconds) < 5) return "just now"

        val past = seconds > 0
        val magnitude = abs(seconds)

        val (count, unit) = when {
            magnitude < 60 -> magnitude to "second"
            magnitude < 3_600 -> (magnitude / 60) to "minute"
            magnitude < 86_400 -> (magnitude / 3_600) to "hour"
            magnitude < 604_800 -> (magnitude / 86_400) to "day"
            magnitude < 2_629_746 -> (magnitude / 604_800) to "week"
            magnitude < 31_556_952 -> (magnitude / 2_629_746) to "month"
            else -> (magnitude / 31_556_952) to "year"
        }

        val noun = if (count == 1L) unit else unit + "s"
        return if (past) "$count $noun ago" else "in $count $noun"
    }

    /**
     * The full status line shown under a value, e.g. "Updated 4 minutes ago"
     * or "Couldn't refresh — showing data from 2 hours ago".
     */
    fun status(freshness: Freshness, now: Instant = Clock.System.now()): String =
        when (freshness) {
            is Freshness.Missing -> "Not available"
            is Freshness.Fresh -> "Updated ${string(freshness.asOf, now)}"
            is Freshness.Stale -> "Stale — last updated ${string(freshness.asOf, now)}"
            is Freshness.Refreshing ->
                freshness.previous?.let { "Refreshing — showing data from ${string(it, now)}" }
                    ?: "Loading…"
            is Freshness.Failed ->
                freshness.previous?.let {
                    "Couldn't refresh (${freshness.reason}) — showing data from ${string(it, now)}"
                } ?: "Not available (${freshness.reason})"
        }
}
