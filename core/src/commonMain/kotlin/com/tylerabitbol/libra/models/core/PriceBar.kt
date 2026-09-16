package com.tylerabitbol.libra.models.core

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.time.Instant

/**
 * One OHLCV bar.
 *
 * Swift's `@Model final class PriceBar`, and also the plain value the
 * calculators take. One type rather than two: every calculation signature
 * already named this, and a second "record" class would mean converting at
 * every call site for no gain.
 *
 * A bar built in memory by a view model has [id] 0 and no [provider]; only a
 * row that came from, or is going to, the store carries either.
 *
 * Bars are immutable once written for a given (symbol, date, resolution); a
 * re-fetch that disagrees indicates a provider restatement and is recorded as
 * a new row with a later [observedAt].
 */
@Entity(
    tableName = "price_bars",
    indices = [Index("symbol"), Index("symbol", "resolution"), Index("symbol", "date")]
)
data class PriceBar(
    /** The session or interval this bar describes. */
    val date: Instant,
    val resolution: BarResolution = BarResolution.Daily,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double? = null,
    /**
     * Split/dividend-adjusted close where the provider supplies one. Returns
     * computed over long windows must use this, not raw close.
     */
    val adjustedClose: Double? = null,
    val symbol: String? = null,
    val observedAt: Instant? = null,
    /**
     * Which provider supplied this bar.
     *
     * Null for two reasons. Rows written before this column existed carry
     * nothing, and a bar whose origin is unknown cannot be trusted — a keyless
     * run once wrote five years of sample closes here, and afterwards nothing
     * on disk distinguished them from real sessions. And a bar is also built
     * as a plain in-memory carrier by the view models, where there is no
     * provider to name and nothing is ever inserted.
     */
    val provider: DataProviderID? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
) {
    /**
     * Prefers the adjusted series when available, since that is what any
     * return or moving-average calculation should be built on.
     */
    val analysisClose: Double get() = adjustedClose ?: close
}
