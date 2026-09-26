package com.tylerabitbol.libra.calculations

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.PriceBar
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.QuoteDTO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Section 4 detection.
 *
 * The point of these tests is not that the detectors fire — it is that they
 * stay silent when they should. A research tool that flags an ordinary
 * Tuesday teaches the user to ignore it, at which point it is worse than
 * nothing. Most of what follows pins down the *absence* of an event.
 */
class EventDetectionTest {

    // MARK: - Fixtures

    private val epoch = Instant.fromEpochSeconds(1_700_000_000)

    /** Bars from a series of daily percentage moves, compounded. */
    private fun bars(
        moves: List<Double>,
        start: Double = 100.0,
        volume: Double? = 1_000_000.0
    ): List<PriceBar> {
        var close = start
        val result = mutableListOf(
            PriceBar(
                date = epoch, resolution = BarResolution.Daily, open = close, high = close,
                low = close, close = close, volume = volume, adjustedClose = close
            )
        )
        moves.forEachIndexed { index, move ->
            close *= (1 + move / 100)
            result.add(
                PriceBar(
                    date = epoch + ((index + 1) * 86_400).seconds,
                    resolution = BarResolution.Daily, open = close, high = close, low = close,
                    close = close, volume = volume, adjustedClose = close
                )
            )
        }
        return result
    }

    /**
     * A calm series: alternating ±0.4% so dispersion exists but nothing is
     * remarkable. Alternating rather than constant, because a constant series
     * has zero dispersion and every detector correctly refuses to score it.
     */
    private fun calmMoves(count: Int): List<Double> =
        (0 until count).map { if (it % 2 == 0) 0.4 else -0.4 }

    // MARK: - Statistics

    @Test
    fun medianHandlesOddAndEvenCounts() {
        assertEquals(2.0, Statistics.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, Statistics.median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals(0.0, Statistics.median(emptyList()))
    }

    @Test
    fun flatSeriesHasNoScale() {
        // Without this guard every deviation divides by zero and renders as an
        // infinitely extraordinary event, when the truth is that nothing moved.
        assertNull(Statistics.robustScale(listOf(5.0, 5.0, 5.0, 5.0)))
        assertNull(Statistics.robustScale(emptyList()))
    }

    @Test
    fun annualisedVolatilityScalesByRoot252() {
        val returns = listOf(1.0, -1.0, 1.0, -1.0, 1.0, -1.0)
        val daily = assertNotNull(Statistics.standardDeviation(returns))
        val annual = assertNotNull(Statistics.annualisedVolatility(returns))
        assertTrue(abs(annual - daily * sqrt(252.0)) < 1e-9)
    }

    // MARK: - Anomaly measure

    @Test
    fun observationExcludedFromOwnSample() {
        val sample = List(30) { 0.4 } + List(30) { -0.4 }
        val measure = assertNotNull(AnomalyMeasure.measure(9.0, sample))

        // The sample is the priors only; the 9% day must not appear in it, or
        // it would widen the very yardstick used to judge it.
        assertEquals(sample.size, measure.sampleSize)
        assertEquals(sample.size, measure.exceededCount)
        assertEquals(1.0, measure.unusualness)
    }

    @Test
    fun smallSampleYieldsNothing() {
        assertNull(AnomalyMeasure.measure(5.0, List(10) { 0.5 }))
    }

    // MARK: - Price moves

    @Test
    fun largeMoveDetected() {
        val series = bars(calmMoves(80) + 9.0)
        val event = assertNotNull(EventDetector.priceMove(series))

        assertEquals(EventKind.UnusualPriceMove, event.kind)
        assertTrue(event.unusualness > 0.97)
        assertNotNull(event.derivation, "A detected number must be checkable")
        assertTrue(
            event.detailLines.any { it.contains("prior") },
            "The reader must be told what the move was compared against"
        )
    }

    @Test
    fun ordinaryDayIsSilent() {
        assertNull(EventDetector.priceMove(bars(calmMoves(90))))
    }

    @Test
    fun shortHistoryIsSilent() {
        // Ten sessions cannot establish what is usual, so a 12% day says
        // nothing about this security yet.
        assertNull(EventDetector.priceMove(bars(calmMoves(10) + 12.0)))
    }

    @Test
    fun tinyMoveSuppressed() {
        // 0.05% moves make a 0.5% day rank at the very top of the sample and
        // clear the deviation threshold. It is still not worth telling anyone
        // about, so an absolute floor sits on top of the statistics — a
        // judgement about attention, not about significance.
        val quiet = (0 until 80).map { if (it % 2 == 0) 0.05 else -0.05 }
        assertNull(EventDetector.priceMove(bars(quiet + 0.5)))
    }

    @Test
    fun downMoveDetected() {
        val event = assertNotNull(EventDetector.priceMove(bars(calmMoves(80) + -9.0)))
        assertTrue(event.headline.contains("fell"))
        assertTrue(
            event.detailLines.any { it.contains("-") },
            "Direction must survive into the detail lines"
        )
    }

    // MARK: - The current session

    private fun quote(
        changePercent: Double,
        at: Instant,
        volume: Double? = null
    ): QuoteDTO {
        val previous = 100.0
        return QuoteDTO(
            symbol = "TEST", last = previous * (1 + changePercent / 100),
            previousClose = previous, volume = volume, quoteTime = at
        )
    }

    @Test
    fun quoteSuppliesCurrentSession() {
        // The defect this pins down was visible on screen: a security up 8.7%
        // on the day showed "nothing unusual", because daily bars stop at the
        // previous close and the detectors only ever read bars.
        val series = bars(calmMoves(80))
        val today = assertNotNull(series.lastOrNull()).date + 1.days

        assertNull(EventDetector.priceMove(series))

        val event = assertNotNull(
            EventDetector.priceMove(series, quote = quote(8.7, today))
        )
        assertTrue(event.headline.contains("so far today"))
        assertEquals(today, event.occurredAt)
    }

    @Test
    fun openSessionIsProvisional() {
        val series = bars(calmMoves(80))
        val today = assertNotNull(series.lastOrNull()).date + 1.days
        val event = assertNotNull(EventDetector.priceMove(series, quote = quote(8.7, today)))

        assertTrue(event.isProvisional)
        assertTrue(
            event.detailLines.any { it.contains("still open") },
            "A reading that can still change must say so"
        )
    }

    @Test
    fun closedSessionIsFinal() {
        val event = assertNotNull(EventDetector.priceMove(bars(calmMoves(80) + 9.0)))
        assertFalse(event.isProvisional)
    }

    @Test
    fun sameSessionIsNotDoubleCounted() {
        // Some providers publish a partial bar for the open session. Taking
        // both it and the quote would enter one day's move twice — once as a
        // prior and once as the observation.
        val series = bars(calmMoves(80) + 9.0)
        val lastBarDate = assertNotNull(series.lastOrNull()).date

        val reading = assertNotNull(
            EventDetector.latestReading(series, quote(2.0, lastBarDate))
        )
        assertFalse(reading.reading.isIntraday)
        assertTrue(
            abs(reading.reading.percent - 9.0) < 0.001,
            "The bar's own move, not the quote's"
        )
    }

    @Test
    fun quoteWithoutPreviousCloseIsIgnored() {
        // Change percent is null without a previous close, and inventing one
        // would fabricate the very number being judged.
        val series = bars(calmMoves(80))
        val today = assertNotNull(series.lastOrNull()).date + 1.days
        val bare = QuoteDTO(symbol = "TEST", last = 150.0, quoteTime = today)
        val reading = assertNotNull(EventDetector.latestReading(series, bare))
        assertFalse(reading.reading.isIntraday)
    }

    @Test
    fun liveVolumeUsesAllClosedSessionsAsPriors() {
        val series = bars(calmMoves(80), volume = 1_000_000.0)
        val today = assertNotNull(series.lastOrNull()).date + 1.days
        val event = assertNotNull(
            EventDetector.volumeAnomaly(
                series, quote = quote(1.0, today, volume = 3_000_000.0)
            )
        )

        assertTrue(event.isProvisional)
        assertTrue(event.headline.contains("so far today"))
        assertTrue(
            event.detailLines.any { it.contains("still open") },
            "A partial day's volume understates the day"
        )
    }

    // MARK: - Volume

    @Test
    fun volumeSpikeDetected() {
        val base = bars(calmMoves(80), volume = 1_000_000.0)
        val last = base.last()
        val series = base.dropLast(1) + last.copy(volume = 3_000_000.0)

        val event = assertNotNull(EventDetector.volumeAnomaly(series))
        assertEquals(EventKind.UnusualVolume, event.kind)
        assertTrue(event.headline.contains("3.0×"))
    }

    @Test
    fun steadyVolumeIsSilent() {
        assertNull(EventDetector.volumeAnomaly(bars(calmMoves(80))))
    }

    @Test
    fun missingVolumeIsSilent() {
        // Absent volume must read as absent, never as zero — a provider that
        // omits the field would otherwise look like a day with no trading.
        assertNull(EventDetector.volumeAnomaly(bars(calmMoves(80), volume = null)))
    }

    // MARK: - Volatility

    @Test
    fun volatilityShiftDetected() {
        val quiet = (0 until 70).map { if (it % 2 == 0) 0.3 else -0.3 }
        val loud = (0 until 20).map { if (it % 2 == 0) 2.0 else -2.0 }
        val event = assertNotNull(EventDetector.volatilityShift(bars(quiet + loud)))

        assertEquals(EventKind.VolatilityShift, event.kind)
        assertTrue(event.headline.contains("risen"))
        // "Doubled" is uninformative without the levels: 8% to 16% and 60% to
        // 120% are very different situations.
        assertEquals(2, event.detailLines.count { it.contains("annualised") })
    }

    @Test
    fun steadyVolatilityIsSilent() {
        assertNull(EventDetector.volatilityShift(bars(calmMoves(90))))
    }

    // MARK: - Backfilling sessions the user missed

    @Test
    fun backfillCoversMissedSessions() {
        // A large move on a day the app was not opened is still a change the
        // user has not seen. Judging only the newest session loses it entirely.
        val series = bars(calmMoves(60) + 9.0 + calmMoves(3) + -8.0)
        val lastVisit = assertNotNull(series.dropLast(6).lastOrNull()).date

        val events = EventDetector.priceMoves(series, after = lastVisit)
        assertEquals(2, events.size)
        assertTrue(events.none { it.isProvisional })
        assertTrue(events.any { it.headline.contains("rose") })
        assertTrue(events.any { it.headline.contains("fell") })
    }

    @Test
    fun backfillNeedsAReferencePoint() {
        assertTrue(EventDetector.priceMoves(bars(calmMoves(60) + 9.0), after = null).isEmpty())
    }

    @Test
    fun backfillIgnoresSeenSessions() {
        val series = bars(calmMoves(60) + 9.0 + calmMoves(5))
        val after = assertNotNull(series.lastOrNull()).date
        assertTrue(EventDetector.priceMoves(series, after = after).isEmpty())
    }

    @Test
    fun backfillDoesNotUseHindsight() {
        // Including later sessions in the reference sample would let a move be
        // judged by information that did not exist yet.
        val series = bars(calmMoves(60) + 5.0 + List(30) { 6.0 })
        val lastVisit = assertNotNull(series.dropLast(32).lastOrNull()).date
        val events = EventDetector.priceMoves(series, after = lastVisit)

        // The 5% day was extraordinary when it happened, even though a calmer
        // reading of the whole series would call it unremarkable.
        assertTrue(events.any { it.headline.contains("5.0%") })
    }

    @Test
    fun comparisonLineReadsWell() {
        val event = assertNotNull(EventDetector.priceMove(bars(calmMoves(80) + 9.0)))
        val line = assertNotNull(event.detailLines.firstOrNull { it.contains("Larger than") })
        // "Larger than 250 of the prior 250" is correct and reads badly.
        assertTrue(line.contains("every one of"))
    }

    // MARK: - Filings

    private fun filing(form: String, daysAgo: Double): FilingDTO = FilingDTO(
        accessionNumber = "$form-$daysAgo",
        formType = form,
        filedAt = epoch - (daysAgo * 86_400).toInt().seconds
    )

    @Test
    fun firstVisitReportsNothing() {
        // With no prior visit there is no "since", and presenting a company's
        // whole filing history as new would be false.
        val filings = listOf(filing("10-K", 5.0), filing("8-K", 1.0))
        assertTrue(EventDetector.newFilings(filings, since = null).isEmpty())
    }

    @Test
    fun filingsSinceLastVisit() {
        val filings = listOf(filing("10-K", 30.0), filing("8-K", 3.0), filing("4", 1.0))
        val since = epoch - 10.days
        val events = EventDetector.newFilings(filings, since = since)

        assertEquals(2, events.size)
        assertTrue(events.first().headline.contains("4"))
        assertTrue(
            events.all { it.unusualness == 0.0 },
            "A filing either exists or does not; there is no sample to rank it against"
        )
    }

    @Test
    fun filingSignificanceDescribesTheDocument() {
        assertTrue(FilingSignificance.explanation("10-K").contains("annual report"))
        assertTrue(FilingSignificance.explanation("8-K").contains("material"))
        assertTrue(
            FilingSignificance.explanation("ZZZ").isNotEmpty(),
            "An unknown form still gets an honest description rather than silence"
        )
    }

    // MARK: - Event identity

    @Test
    fun naturalKeyIgnoresHeadlineText() {
        val first = DetectedEventDTO.create(
            kind = EventKind.UnusualVolume, occurredAt = epoch,
            headline = "Volume 2.0× the median"
        )
        val second = DetectedEventDTO.create(
            kind = EventKind.UnusualVolume, occurredAt = epoch,
            headline = "Volume was twice its usual level"
        )

        // Rewording a headline in a future release must not resurrect an event
        // the user already acknowledged.
        assertEquals(first.naturalKey, second.naturalKey)
    }

    @Test
    fun unusualnessClamped() {
        val now = Clock.System.now()
        assertEquals(
            1.0,
            DetectedEventDTO.create(EventKind.Other, now, "x", unusualness = 4.0).unusualness
        )
        assertEquals(
            0.0,
            DetectedEventDTO.create(EventKind.Other, now, "x", unusualness = -2.0).unusualness
        )
    }

    @Test
    fun claimKinds() {
        val event = DetectedEventDTO.create(
            kind = EventKind.UnusualPriceMove, occurredAt = Clock.System.now(),
            headline = "Price rose 9.0%",
            context = "Moves this large usually have a cause.",
            derivation = Derivation(formula = "a - b", inputs = emptyList(), result = "9.0%")
        )
        assertEquals(ClaimKind.Calculation, event.headlineClaim.kind)
        assertEquals(ClaimKind.Interpretation, assertNotNull(event.contextClaim).kind)
    }

    @Test
    fun filingHeadlineIsFact() {
        // The app did not compute that an 8-K exists; the SEC reported it.
        // Badging it CALCULATION overstates the app's involvement and
        // understates the claim's authority.
        val since = epoch - 10.days
        val event = assertNotNull(
            EventDetector.newFilings(listOf(filing("8-K", 1.0)), since = since).firstOrNull()
        )
        assertEquals(ClaimKind.Fact, event.headlineClaim.kind)
        assertTrue(event.headline.startsWith("Form 8-K"))
    }

    @Test
    fun filingHasSingleSourceLink() {
        val dated = FilingDTO(
            accessionNumber = "0000320193-26-000001", formType = "10-Q",
            filedAt = epoch,
            primaryDocumentURL = "https://www.sec.gov/doc.htm",
            filingIndexURL = "https://www.sec.gov/index.htm"
        )
        val since = epoch - 1.days
        val event = assertNotNull(
            EventDetector.newFilings(listOf(dated), since = since).firstOrNull()
        )

        assertEquals(1, event.sourceURLs.size)
        assertTrue(event.sourceURLs.first().contains("doc.htm"))
        // The reference must reach the user as a traceable source.
        assertTrue(event.headlineClaim.isTraceable)
        assertEquals(DataProviderID.SEC, event.headlineClaim.sources.first().provider)
    }
}
