package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.QuoteDTO
import com.tylerabitbol.libra.support.Format
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Deterministic detection of changes worth investigating — Section 4.
 *
 * Everything here is pure Kotlin over bars and filings the app already holds.
 * No AI, no model, no prediction: a detector states that something is unusual
 * *relative to this security's own recent history* and shows the arithmetic.
 * It never says why, and never says what to do about it.
 *
 * Three statistical decisions run through all of it, and each exists because
 * the obvious alternative produces confident nonsense:
 *
 * - **Rank, not probability.** A 3σ day in a normal distribution is a 1-in-370
 *   event. Daily equity returns are not normal — 3σ days arrive several times
 *   a year — so converting a z-score to a probability would put a wildly
 *   overstated rarity on screen. Unusualness is reported as position within
 *   the observed sample: "larger than 248 of the past 250 sessions" is both
 *   true and checkable.
 * - **Median and MAD, not mean and standard deviation.** The spike being
 *   measured also inflates a standard deviation computed over the window
 *   containing it, which shrinks its own z-score and hides the *next* spike
 *   behind a permanently widened band.
 * - **An observation is excluded from its own reference sample**, for the same
 *   reason valuation percentiles are: a value cannot be its own yardstick.
 */
object EventDetector {

    /**
     * Sessions of history a detector requires before it will say anything.
     * Below this, "unusual" is an opinion about a small sample.
     */
    const val minimumSample = 40

    /**
     * Window for measuring *dispersion*. Long enough to describe a regime,
     * short enough that a change of regime shows up rather than being
     * averaged away — a stock that got twice as volatile last month should be
     * judged against last month, not against a calm year.
     */
    const val scaleWindow = 60

    /**
     * Window for measuring *rank*. Deliberately longer than the scale window:
     * rank costs nothing extra to compute over more data, and a year of
     * context supports a far more useful statement. "The largest move in 60
     * sessions" understates what the app knows when it holds five years.
     */
    const val rankWindow = 250

    /**
     * Retained name for the dispersion window, which is what callers outside
     * the anomaly machinery mean by "the reference window".
     */
    val referenceWindow: Int get() = scaleWindow

    // MARK: - Entry point

    /**
     * Runs every detector over one security.
     *
     * Bars must be daily and are sorted defensively — a provider returning
     * newest-first would otherwise make "the latest session" the oldest one.
     *
     * The quote is not optional decoration. Daily bars end at the *previous*
     * close, so a detector reading bars alone is blind to today — which is
     * precisely the day the user is asking about. Passing the quote lets the
     * current session be measured against the closed ones behind it.
     */
    fun detect(
        bars: List<PriceBar>,
        quote: QuoteDTO? = null,
        sourceDetail: String = "Daily bars"
    ): List<DetectedEventDTO> {
        val sorted = bars.sortedBy { it.date }
        return listOfNotNull(
            priceMove(bars = sorted, quote = quote, sourceDetail = sourceDetail),
            volumeAnomaly(bars = sorted, quote = quote, sourceDetail = sourceDetail),
            volatilityShift(bars = sorted, sourceDetail = sourceDetail)
        )
    }

    /**
     * The most recent reading, from the live quote when it is ahead of the
     * bars and from the last closed session otherwise.
     *
     * [isIntraday] travels with it because the distinction is material: a
     * session still open can reverse before it closes, and reporting an
     * unfinished move in the same words as a settled one would overstate it.
     */
    data class LatestReading(
        val date: Instant,
        val percent: Double,
        val close: Double,
        val volume: Double?,
        val isIntraday: Boolean
    ) {
        val sessionLabel: String
            get() = if (isIntraday) "so far today" else "on ${Format.dayAndMonth(date)}"
    }

    /** One closed session's return, with the close it ended at. */
    data class DailyReturn(val date: Instant, val percent: Double, val close: Double)

    /** The reading being judged, paired with what it is judged against. */
    data class Reading(val reading: LatestReading, val priors: List<DailyReturn>)

    /**
     * Splits the series into "the reading being judged" and "what it is judged
     * against", with the current session included when only the quote has it.
     *
     * Same-day equality matters more than the raw timestamp comparison: some
     * providers do publish a partial bar for the open session, and counting
     * that bar *and* the quote would enter one day's move twice.
     */
    fun latestReading(
        bars: List<PriceBar>,
        quote: QuoteDTO?,
        zone: TimeZone = TimeZone.currentSystemDefault()
    ): Reading? {
        val returns = dailyReturns(bars)
        val lastBar = bars.lastOrNull() ?: return null
        val lastReturn = returns.lastOrNull() ?: return null

        val percent = quote?.changePercent
        if (quote != null && percent != null) {
            val quoteDate = quote.quoteTime ?: Clock.System.now()
            val sameDay = quoteDate.toLocalDateTime(zone).date ==
                lastBar.date.toLocalDateTime(zone).date
            val isNewSession = quoteDate > lastBar.date && !sameDay
            if (isNewSession) {
                // Every closed session is a prior; none of them is this one.
                return Reading(
                    LatestReading(
                        date = quoteDate, percent = percent, close = quote.last,
                        volume = quote.volume, isIntraday = true
                    ),
                    returns
                )
            }
        }

        return Reading(
            LatestReading(
                date = lastReturn.date, percent = lastReturn.percent,
                close = lastReturn.close, volume = lastBar.volume, isIntraday = false
            ),
            returns.dropLast(1)
        )
    }

    // MARK: - Price

    /**
     * A daily move large relative to this security's own recent daily moves.
     *
     * Two conditions must both hold. The rank condition asks whether the move
     * is unusual *for this security*; the deviation condition keeps a quiet
     * name's ordinary wobble from being flagged merely because the sample is
     * tight. A minimum absolute move sits on top of both as noise suppression
     * — that one is a judgement about what is worth a reader's attention, not
     * a statistical claim, and is labelled as such.
     *
     * @param rankThreshold the primary criterion — how far into the tail of the
     *   security's own recent sessions the move must sit. This is what the
     *   app actually states on screen, so it is what the gate is built on.
     * @param deviationThreshold a secondary guard, not a second opinion. It
     *   exists only to reject a move that ranks highly because the sample
     *   happens to be unusually tight. Set low deliberately: an earlier
     *   value of 3.0 rejected an 8.7% day that was the *largest in the whole
     *   reference window*, because a high-volatility name has a large MAD —
     *   which made the detector least sensitive exactly where large moves
     *   matter most.
     * @param minimumAbsoluteMove noise suppression. A judgement about what is
     *   worth attention, not a statistical claim.
     */
    fun priceMove(
        bars: List<PriceBar>,
        quote: QuoteDTO? = null,
        rankThreshold: Double = 0.975,
        deviationThreshold: Double = 2.0,
        minimumAbsoluteMove: Double = 1.0,
        sourceDetail: String = "Daily bars"
    ): DetectedEventDTO? {
        val split = latestReading(bars = bars, quote = quote) ?: return null
        if (split.priors.size < minimumSample) return null

        return priceMoveEvent(
            latest = split.reading,
            priors = split.priors.map { it.percent },
            rankThreshold = rankThreshold,
            deviationThreshold = deviationThreshold,
            minimumAbsoluteMove = minimumAbsoluteMove,
            sourceDetail = sourceDetail
        )
    }

    /**
     * Unusual moves on sessions that closed after a given date.
     *
     * [priceMove] only ever judges the newest session, which means a large
     * move on a day the user did not open the app was never recorded even
     * though its bar was stored. This walks the gap between visits so the
     * record is complete regardless of when the app happened to be open.
     *
     * Each session is judged only against the sessions *before* it, never
     * against the ones that followed — anything else is hindsight dressed up
     * as detection.
     */
    fun priceMoves(
        bars: List<PriceBar>,
        after: Instant?,
        limit: Int = 10,
        rankThreshold: Double = 0.975,
        deviationThreshold: Double = 2.0,
        minimumAbsoluteMove: Double = 1.0,
        sourceDetail: String = "Daily bars"
    ): List<DetectedEventDTO> {
        if (after == null) return emptyList()
        val sorted = bars.sortedBy { it.date }
        val returns = dailyReturns(sorted)
        if (returns.size <= minimumSample) return emptyList()

        val events = mutableListOf<DetectedEventDTO>()
        for (index in minimumSample until returns.size) {
            val session = returns[index]
            if (session.date <= after) continue
            val reading = LatestReading(
                date = session.date, percent = session.percent,
                close = session.close, volume = null, isIntraday = false
            )
            val priors = returns.subList(0, index).map { it.percent }
            priceMoveEvent(
                latest = reading, priors = priors,
                rankThreshold = rankThreshold,
                deviationThreshold = deviationThreshold,
                minimumAbsoluteMove = minimumAbsoluteMove,
                sourceDetail = sourceDetail
            )?.let { events.add(it) }
        }
        return events.takeLast(limit)
    }

    /**
     * Unusual volume on sessions that closed after a given date.
     *
     * Same reason as [priceMoves]: judging only the newest session loses a
     * spike on any day the app was not opened.
     */
    fun volumeAnomalies(
        bars: List<PriceBar>,
        after: Instant?,
        limit: Int = 10,
        multipleThreshold: Double = 2.0,
        rankThreshold: Double = 0.95,
        sourceDetail: String = "Daily bars"
    ): List<DetectedEventDTO> {
        if (after == null) return emptyList()
        val sorted = bars.sortedBy { it.date }
        if (sorted.size <= minimumSample) return emptyList()

        val events = mutableListOf<DetectedEventDTO>()
        for (index in minimumSample until sorted.size) {
            if (sorted[index].date <= after) continue
            // Judged against the sessions before it only — the window ends at
            // this bar, so nothing that followed can influence the verdict.
            val window = sorted.subList(0, index + 1)
            volumeAnomaly(
                bars = window, quote = null,
                multipleThreshold = multipleThreshold,
                rankThreshold = rankThreshold,
                sourceDetail = sourceDetail
            )?.let { events.add(it) }
        }
        return events.takeLast(limit)
    }

    /**
     * Builds the event for one reading judged against one set of priors.
     * Shared so the live session and a backfilled one are described in
     * identical terms and cannot drift apart.
     */
    private fun priceMoveEvent(
        latest: LatestReading,
        priors: List<Double>,
        rankThreshold: Double,
        deviationThreshold: Double,
        minimumAbsoluteMove: Double,
        sourceDetail: String
    ): DetectedEventDTO? {
        val measure = AnomalyMeasure.measure(latest.percent, priors) ?: return null

        if (abs(latest.percent) < minimumAbsoluteMove) return null
        if (measure.unusualness < rankThreshold) return null
        if (abs(measure.deviations) < deviationThreshold) return null

        val direction = if (latest.percent >= 0) "rose" else "fell"
        val details = mutableListOf(
            "Move: ${Format.signedPercent(latest.percent, precision = 2)}",
            measure.comparisonLine,
            "${Format.multiple(abs(measure.deviations), precision = 1)} the typical " +
                "daily move (median absolute deviation)",
            "${if (latest.isIntraday) "Last" else "Close"}: ${Format.currency(latest.close)}"
        )
        if (latest.isIntraday) {
            details.add("Session still open — this can change before the close")
        }

        return DetectedEventDTO.create(
            kind = EventKind.UnusualPriceMove,
            occurredAt = latest.date,
            headline = "Price $direction ${Format.percent(abs(latest.percent), precision = 1)} " +
                latest.sessionLabel,
            detailLines = details,
            context = "Size is measured against this security's own prior " +
                "${measure.sampleSize} closed sessions, not against the market. A move " +
                "this large usually has a specific cause — a filing, an earnings report, " +
                "a sector move, or market-wide news. This detector does not know which.",
            unusualness = measure.unusualness,
            sourceDetails = listOf(sourceDetail),
            derivation = measure.derivation(
                label = if (latest.isIntraday) "Change so far today" else "Daily return",
                formatted = Format.signedPercent(latest.percent, precision = 2)
            ),
            isProvisional = latest.isIntraday
        )
    }

    // MARK: - Volume

    /**
     * Volume well above the recent median.
     *
     * Measured as a ratio rather than in deviations: volume is strictly
     * positive and heavily right-skewed, so a symmetric deviation band around
     * it is the wrong shape and would flag quiet days as anomalies too.
     */
    fun volumeAnomaly(
        bars: List<PriceBar>,
        quote: QuoteDTO? = null,
        multipleThreshold: Double = 2.0,
        rankThreshold: Double = 0.95,
        sourceDetail: String = "Daily bars"
    ): DetectedEventDTO? {
        val withVolume = bars.mapNotNull { bar ->
            val volume = bar.volume
            if (volume == null || volume <= 0 || !volume.isFinite()) null
            else bar.date to volume
        }
        if (withVolume.size < minimumSample) return null
        val lastClosed = withVolume.last()

        // Same treatment as price: without the quote, today is invisible. An
        // open session's volume is partial by definition, so the ratio built
        // from it understates the day — said plainly rather than left implied.
        val session = latestReading(bars = bars, quote = quote)?.reading
        val liveVolume = session?.let { reading ->
            val volume = reading.volume
            if (reading.isIntraday && volume != null && volume > 0) volume else null
        }
        val latest: Pair<Instant, Double>
        val closedPriors: List<Pair<Instant, Double>>
        if (liveVolume != null) {
            latest = session.date to liveVolume
            closedPriors = withVolume
        } else {
            latest = lastClosed
            closedPriors = withVolume.dropLast(1)
        }

        val priors = closedPriors.takeLast(referenceWindow).map { it.second }
        if (priors.size < minimumSample - 1) return null
        val median = Statistics.median(priors)
        if (median <= 0) return null

        val latestVolume = latest.second
        val multiple = latestVolume / median
        val exceeded = priors.count { it < latestVolume }
        val unusualness = exceeded.toDouble() / priors.size

        if (multiple < multipleThreshold || unusualness < rankThreshold) return null

        return DetectedEventDTO.create(
            kind = EventKind.UnusualVolume,
            occurredAt = latest.first,
            headline = "Volume ${Format.multiple(multiple)} the ${priors.size}-session median " +
                if (liveVolume != null) "so far today"
                else "on ${Format.dayAndMonth(latest.first)}",
            detailLines = listOf(
                "Volume: ${Format.compact(latestVolume)} shares",
                "Median of prior ${Format.count(priors.size, "session")}: " +
                    "${Format.compact(median)} shares",
                "Higher than $exceeded of those ${Format.count(priors.size, "session")}"
            ) + if (liveVolume != null) {
                listOf("Session still open — the day's total will be higher")
            } else {
                emptyList()
            },
            context = "Elevated volume means more shares changed hands than usual, which " +
                "indicates unusual attention. It carries no direction on its own: heavy " +
                "buying and heavy selling look identical in a volume figure.",
            unusualness = unusualness,
            sourceDetails = listOf(sourceDetail),
            derivation = Derivation(
                formula = "volume ÷ median(prior ${priors.size} sessions)",
                inputs = listOf(
                    Derivation.Input("volume", Format.compact(latestVolume)),
                    Derivation.Input("median volume", Format.compact(median))
                ),
                result = Format.multiple(multiple)
            ),
            isProvisional = liveVolume != null
        )
    }

    // MARK: - Volatility

    /**
     * A change in how much the price is moving, recent window against prior.
     *
     * Reported as annualised realised volatility so the figure is comparable
     * to how volatility is normally quoted, with both windows named — the
     * ratio alone would hide that "doubled" can mean 8% to 16% or 60% to 120%.
     */
    fun volatilityShift(
        bars: List<PriceBar>,
        recentWindow: Int = 20,
        priorWindow: Int = 60,
        riseThreshold: Double = 1.6,
        fallThreshold: Double = 0.625,
        sourceDetail: String = "Daily bars"
    ): DetectedEventDTO? {
        val returns = dailyReturns(bars).map { it.percent }
        if (returns.size < recentWindow + priorWindow) return null

        val recent = returns.takeLast(recentWindow)
        val prior = returns.dropLast(recentWindow).takeLast(priorWindow)
        val recentVol = Statistics.annualisedVolatility(recent) ?: return null
        val priorVol = Statistics.annualisedVolatility(prior) ?: return null
        if (priorVol <= 0) return null

        val ratio = recentVol / priorVol
        if (ratio < riseThreshold && ratio > fallThreshold) return null
        val occurredAt = bars.lastOrNull()?.date ?: return null

        val rising = ratio > 1
        // Mapped onto 0–1 by how far the ratio sits beyond its threshold, and
        // capped: unlike the rank-based scores this is not a sample position,
        // so it must not be presented as one.
        val unusualness = min(1.0, abs(ln(ratio)) / ln(3.0))

        return DetectedEventDTO.create(
            kind = EventKind.VolatilityShift,
            occurredAt = occurredAt,
            headline = "Volatility has ${if (rising) "risen" else "fallen"} — " +
                "${Format.percent(recentVol, precision = 1)} over $recentWindow sessions " +
                "against ${Format.percent(priorVol, precision = 1)} before that",
            detailLines = listOf(
                "Recent $recentWindow sessions: " +
                    "${Format.percent(recentVol, precision = 1)} annualised",
                "Prior ${Format.count(prior.size, "session")}: " +
                    "${Format.percent(priorVol, precision = 1)} annualised",
                "Ratio: ${Format.multiple(ratio, precision = 2)}"
            ),
            context = "Realised volatility is the spread of recent daily returns, annualised. " +
                "A rise means the price has been moving more than it was, which often " +
                "accompanies a period of unresolved news. It says nothing about direction.",
            unusualness = unusualness,
            sourceDetails = listOf(sourceDetail),
            derivation = Derivation(
                formula = "stdev(daily returns) × √252, recent ÷ prior",
                inputs = listOf(
                    Derivation.Input(
                        "recent $recentWindow-session",
                        Format.percent(recentVol, precision = 1)
                    ),
                    Derivation.Input(
                        "prior ${prior.size}-session",
                        Format.percent(priorVol, precision = 1)
                    )
                ),
                result = Format.multiple(ratio, precision = 2)
            )
        )
    }

    // MARK: - Filings

    /**
     * Filings that appeared since a given moment.
     *
     * [since] is when the user last looked, not when the app last ran: the
     * question being answered is "what is new *to me*". With no prior visit
     * recorded, nothing is reported — showing a company's entire filing
     * history as "new" on first open would be false.
     */
    fun newFilings(
        filings: List<FilingDTO>,
        since: Instant?,
        limit: Int = 10
    ): List<DetectedEventDTO> {
        if (since == null) return emptyList()
        return filings
            .filter { it.filedAt > since }
            .sortedByDescending { it.filedAt }
            .take(limit)
            .map { filing ->
                DetectedEventDTO.create(
                    kind = EventKind.NewFiling,
                    occurredAt = filing.filedAt,
                    headline = "Form ${filing.formType} filed " +
                        Format.shortDate(filing.filedAt),
                    detailLines = listOf(
                        "Form: ${filing.formType}",
                        filing.periodOfReport?.let { "Period: ${Format.shortDate(it)}" }
                            ?: "Period: ${Format.notAvailable}",
                        "Accession: ${filing.accessionNumber}"
                    ),
                    context = FilingSignificance.explanation(filing.formType),
                    // A filing either exists or does not; there is no sample to
                    // rank it against, so it carries no unusualness score.
                    unusualness = 0.0,
                    sourceDetails = listOf("${filing.formType} — ${filing.accessionNumber}"),
                    // One link, not two. The document and its index point at
                    // the same filing, and offering both as identically
                    // labelled links is noise rather than a second source.
                    sourceURLs = listOfNotNull(
                        filing.primaryDocumentURL ?: filing.filingIndexURL
                    )
                )
            }
    }

    // MARK: - Helpers

    private fun dailyReturns(bars: List<PriceBar>): List<DailyReturn> {
        if (bars.size <= 1) return emptyList()
        val result = ArrayList<DailyReturn>(bars.size - 1)
        for (index in 1 until bars.size) {
            val previous = bars[index - 1].analysisClose
            val current = bars[index].analysisClose
            val percent = ReturnCalculator.simpleReturn(previous, current) ?: continue
            result.add(DailyReturn(bars[index].date, percent, current))
        }
        return result
    }
}
