package com.tylerabitbol.libra.persistence

import androidx.room.TypeConverter
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The column types SwiftData handled implicitly.
 *
 * Two decisions worth stating:
 *
 * - **Instants are stored as epoch nanoseconds, not as text.** ISO-8601 text
 *   would be inspectable in a SQL browser, but it does not sort: a timestamp
 *   with a fractional part renders `…:20.5Z`, which compares *below* `…:20Z`
 *   because `.` precedes `Z`. Every `ORDER BY observedAt` in the store would
 *   have been subtly wrong. Nanoseconds keep full precision — milliseconds
 *   would silently truncate a `Clock.System.now()` round-trip — and a Long
 *   covers years 1678 to 2262, well beyond any date a filing carries.
 * - **Enums are stored by their declared raw value, not by ordinal.** An
 *   ordinal would silently repoint every stored row the first time a case is
 *   inserted into the middle of an enum; the raw strings are the same ones
 *   SwiftData wrote, so an existing store reads back unchanged.
 * - **String lists are JSON.** `detailLines` and the two source lists are
 *   short, are never queried against, and are always read whole.
 */
internal object Converters {

    private val json = Json

    /**
     * The last second a nanosecond count fits a Long in, which is in 2262.
     * Anything beyond saturates rather than wrapping.
     *
     * It wrapped before, and silently: `Instant.DISTANT_FUTURE` multiplied out
     * to about 3e21, overflowed to a negative number, and every bounded range
     * query that used it as an open upper bound — which is how `SnapshotStore`
     * expresses "everything from this date onwards" — matched nothing. The
     * store looked empty, so every page re-fetched five years of history it
     * already held, against the scarcest budget in the app.
     */
    private const val MAX_WHOLE_SECONDS = Long.MAX_VALUE / 1_000_000_000L
    private const val MIN_WHOLE_SECONDS = Long.MIN_VALUE / 1_000_000_000L

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.let {
        when {
            it.epochSeconds >= MAX_WHOLE_SECONDS -> Long.MAX_VALUE
            it.epochSeconds <= MIN_WHOLE_SECONDS -> Long.MIN_VALUE
            else -> it.epochSeconds * 1_000_000_000L + it.nanosecondsOfSecond
        }
    }

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let {
        when (it) {
            Long.MAX_VALUE -> Instant.DISTANT_FUTURE
            Long.MIN_VALUE -> Instant.DISTANT_PAST
            else -> Instant.fromEpochSeconds(it.floorDiv(1_000_000_000L), it.mod(1_000_000_000L))
        }
    }

    @TypeConverter
    fun resolutionToString(value: BarResolution?): String? = value?.raw

    @TypeConverter
    fun stringToResolution(value: String?): BarResolution? =
        value?.let { raw -> BarResolution.entries.firstOrNull { it.raw == raw } }

    @TypeConverter
    fun providerToString(value: DataProviderID?): String? = value?.raw

    @TypeConverter
    fun stringToProvider(value: String?): DataProviderID? = DataProviderID.fromRaw(value)

    @TypeConverter
    fun stringListToJson(value: List<String>?): String? =
        value?.let { json.encodeToString(ListSerializer(String.serializer()), it) }

    @TypeConverter
    fun jsonToStringList(value: String?): List<String>? =
        value?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
}
