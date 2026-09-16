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

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.let {
        it.epochSeconds * 1_000_000_000L + it.nanosecondsOfSecond
    }

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let {
        Instant.fromEpochSeconds(it.floorDiv(1_000_000_000L), it.mod(1_000_000_000L))
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
