package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.support.Format
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

/**
 * How far back the "What changed" panel looks.
 *
 * The default is the user's previous visit, which is the question the panel
 * was built to answer and the one the `NEW` badge rests on. Every other case
 * is exploration layered on top: a wider window does not change what counts
 * as new, only what is shown.
 */
sealed interface ChangeWindow {
    /** Everything the user has not already seen. */
    data object LastVisit : ChangeWindow

    /** A rolling window ending now. */
    data class Days(val count: Int) : ChangeWindow

    /** A fixed date the user picked. */
    data class Custom(val date: Instant) : ChangeWindow

    val id: String
        get() = when (this) {
            is LastVisit -> "lastVisit"
            is Days -> "days-$count"
            is Custom -> "custom-${date.toEpochMilliseconds() / 1000.0}"
        }

    val displayName: String
        get() = when (this) {
            is LastVisit -> "Last visit"
            is Days -> if (count == 365) "1 year" else "$count days"
            is Custom -> Format.shortDate(date)
        }

    /**
     * The moment the window opens, or null when no window can be formed.
     *
     * Only [LastVisit] on a first visit returns null, and it means "there is
     * no prior visit to measure from" rather than "measure from the beginning
     * of time". Callers must treat the two differently: reporting a company's
     * entire history as new on first open would be false.
     */
    fun startDate(
        lastVisit: Instant?,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): Instant? = when (this) {
        is LastVisit -> lastVisit
        is Days -> now.minus(DatePeriod(days = count), zone)
        is Custom -> date
    }

    /**
     * Whether this window reaches further back than the user's last visit,
     * and so is asking for history the last-visit default would have hidden.
     */
    fun widensPast(
        lastVisit: Instant?,
        now: Instant = Clock.System.now(),
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): Boolean {
        if (this is LastVisit) return false
        val start = startDate(lastVisit, now, zone) ?: return false
        if (lastVisit == null) return true
        return start < lastVisit
    }

    companion object {
        /**
         * The windows offered in the menu. [Custom] is reached through the date
         * picker rather than listed, since it has no single value to list.
         */
        val offered: List<ChangeWindow> =
            listOf(LastVisit, Days(7), Days(30), Days(90), Days(365))
    }
}
