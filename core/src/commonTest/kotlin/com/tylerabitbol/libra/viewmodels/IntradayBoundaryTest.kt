package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.calculations.ChartSeriesBuilder
import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.ChartAvailability
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.ChartSegment
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.persistence.LibraDatabase
import com.tylerabitbol.libra.persistence.Security
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.persistence.inMemoryLibraDatabase
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.services.providers.MarketDataProvider
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.ProviderRegistry
import com.tylerabitbol.libra.services.providers.QuoteDTO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Serves a daily and an intraday series that can be told apart by volume
 * alone, so a single IEX bar leaking into the daily series is visible.
 */
private class TwoSeriesProvider : MarketDataProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = true

    override suspend fun quote(symbol: String): QuoteDTO =
        throw APIError.Transport(DataProviderID.Finnhub, "not part of this test")

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        val isIntraday = !resolution.isDailyOrCoarser
        val count = if (isIntraday) 60 else 200
        val step = if (isIntraday) 300 else 86_400
        // Anchored to a real session, since the chart draws regular hours only
        // and this suite runs at any hour.
        val end = if (isIntraday) lastRegularClose() else Clock.System.now() - 60.seconds
        return (0 until count).map { index ->
            val close = 100 + (index % 5).toDouble()
            PriceBarDTO(
                date = end - ((count - 1 - index) * step).seconds,
                open = close, high = close + 1, low = close - 1, close = close,
                volume = if (isIntraday) intradayVolumeMarker else dailyVolumeMarker,
                adjustedClose = if (isIntraday) null else close,
            )
        }
    }

    override suspend fun profile(symbol: String): CompanyProfileDTO =
        throw APIError.NotFound(DataProviderID.Finnhub, "profile")

    override suspend fun search(query: String): List<CompanyProfileDTO> = emptyList()

    companion object {
        /**
         * The marker. Real IEX volume is small but not 7; nothing else in the
         * test produces this number.
         */
        const val intradayVolumeMarker: Double = 7.0
        const val dailyVolumeMarker: Double = 1_000_000.0
    }
}

/**
 * The most recent 15:55 in New York at or before now.
 *
 * The chart draws regular-session bars only, so a series anchored to the
 * current instant would be filtered away entirely whenever the suite runs
 * outside market hours — which is most of the time.
 */
internal fun lastRegularClose(): Instant {
    val zone = ChartSeriesBuilder.marketZone
    var day = Clock.System.now().toLocalDateTime(zone).date
    repeat(8) {
        val close = LocalDateTime(day, LocalTime(15, 55)).toInstant(zone)
        if (close <= Clock.System.now()) return close
        day = day.minus(DatePeriod(days = 1))
    }
    return Clock.System.now()
}

private fun LocalDateTime.toInstant(zone: kotlinx.datetime.TimeZone): Instant =
    date.atStartOfDayIn(zone) + time.toSecondOfDay().seconds

internal suspend fun detailModel(
    registry: ProviderRegistry,
    db: LibraDatabase?,
    range: ChartRange? = null,
): SecurityDetailViewModel {
    val model = SecurityDetailViewModel("TEST", CoroutineScope(Dispatchers.Default))
    model.load(registry, db?.let { SnapshotStore(it) })
    model.awaitLoad()
    if (range != null) {
        model.select(range, registry)
        model.awaitLoad()
    }
    return model
}

internal fun seededDatabase(body: suspend (LibraDatabase) -> Unit) = runTest {
    val db = inMemoryLibraDatabase()
    try {
        db.securities().upsert(Security(symbol = "TEST", name = "Test Corp"))
        body(db)
    } finally {
        db.close()
    }
}

private fun twoSeriesRegistry() = ProviderRegistry(
    marketData = TwoSeriesProvider(), fundamentals = null, analyst = null,
    metrics = null, sec = null, macro = null, news = null, isUsingSampleData = false,
)

class IntradayBoundaryTest {

    @Test
    fun availabilityBeforeAnyAttempt() {
        val model = SecurityDetailViewModel("TEST", CoroutineScope(Dispatchers.Default))
        // Nothing held, no error, nothing in flight — the state on the frame
        // between the view appearing and its load starting. Reporting
        // "unavailable" from here is a verdict on a fetch nobody has run, and
        // it rendered as an error card flashing on every open.
        assertEquals(ChartAvailability.Loading, model.state.value.chartAvailability)

        model.select(ChartRange.OneDay, ProviderRegistry.sample)
        assertEquals(ChartAvailability.Loading, model.state.value.chartAvailability)
    }

    @Test
    fun intradayIsFetchedAndDrawn() = seededDatabase { db ->
        val model = detailModel(twoSeriesRegistry(), db, ChartRange.OneDay)
        val state = model.state.value
        assertTrue(state.intradayBars.isNotEmpty())
        assertEquals(ChartAvailability.Ready, state.chartAvailability)
        assertTrue(
            state.chartBars.all { it.volume == TwoSeriesProvider.intradayVolumeMarker },
            "The 1D chart draws the intraday series",
        )
    }

    @Test
    fun intradayNeverEntersBars() = seededDatabase { db ->
        val model = detailModel(twoSeriesRegistry(), db, ChartRange.OneDay)
        val state = model.state.value

        // `bars` feeds EventDetector, RelativeAnalysis and rangeReturn;
        // `visibleBars` feeds ReturnCalculator.priceContext. A single IEX bar
        // in either would make a volume anomaly a measurement of 2.5% of the
        // tape, which is the reason Alpaca was declined as a history source.
        assertTrue(state.bars.isNotEmpty())
        assertTrue(state.bars.all { it.volume == TwoSeriesProvider.dailyVolumeMarker })
        assertTrue(state.visibleBars.all { it.volume == TwoSeriesProvider.dailyVolumeMarker })
        assertTrue(state.bars.all { it.resolution == BarResolution.Daily })
    }

    @Test
    fun detectorInputIsUnchanged() = seededDatabase { db ->
        val registry = twoSeriesRegistry()
        val model = detailModel(registry, db)
        val before = model.state.value.bars.map { it.date }

        model.select(ChartRange.OneDay, registry)
        model.awaitLoad()

        assertEquals(
            before, model.state.value.bars.map { it.date },
            "Switching to 1D must not disturb what the detectors read",
        )
    }
}

/**
 * Fails every bars request on demand, so a chart that already holds data can be
 * asked what it does when a refresh goes wrong.
 */
@OptIn(ExperimentalAtomicApi::class)
private class FailingAfterFirstProvider(private val shouldFail: AtomicBoolean) :
    MarketDataProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = true

    override suspend fun quote(symbol: String): QuoteDTO =
        throw APIError.Transport(DataProviderID.Finnhub, "not part of this test")

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        if (shouldFail.load()) throw APIError.Transport(DataProviderID.Alpaca, "offline")
        val end = lastRegularClose()
        return (0 until 60).map { index ->
            val close = 100 + (index % 5).toDouble()
            PriceBarDTO(
                date = end - ((59 - index) * 300).seconds,
                open = close, high = close + 1, low = close - 1, close = close,
                volume = 7.0, adjustedClose = null,
            )
        }
    }

    override suspend fun profile(symbol: String): CompanyProfileDTO =
        throw APIError.NotFound(DataProviderID.Finnhub, "profile")

    override suspend fun search(query: String): List<CompanyProfileDTO> = emptyList()
}

/** Serves one session that begins before the open and runs past the close. */
private class ExtendedHoursProvider : MarketDataProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = true

    override suspend fun quote(symbol: String): QuoteDTO =
        throw APIError.Transport(DataProviderID.Finnhub, "not part of this test")

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        val zone = ChartSeriesBuilder.marketZone
        val day = lastRegularClose().toLocalDateTime(zone).date
        // 07:00 through 19:55 in five-minute steps, spanning both edges.
        val open = day.atStartOfDayIn(zone) + (7 * 3600).seconds
        return (0 until 156).map { index ->
            val price = 100 + (index % 5).toDouble()
            PriceBarDTO(
                date = open + (index * 300).seconds,
                open = price, high = price + 1, low = price - 1, close = price,
                volume = 7.0, adjustedClose = null,
            )
        }
    }

    override suspend fun profile(symbol: String): CompanyProfileDTO =
        throw APIError.NotFound(DataProviderID.Finnhub, "profile")

    override suspend fun search(query: String): List<CompanyProfileDTO> = emptyList()
}

/** Serves a whole number of regular sessions, most recent last. */
private class SessionSeriesProvider(private val sessions: Int) : MarketDataProvider {
    override val id: DataProviderID = DataProviderID.Finnhub

    override suspend fun isConfigured(): Boolean = true

    override suspend fun quote(symbol: String): QuoteDTO =
        throw APIError.Transport(DataProviderID.Finnhub, "not part of this test")

    override suspend fun bars(
        symbol: String,
        resolution: BarResolution,
        from: Instant,
        to: Instant,
    ): List<PriceBarDTO> {
        val zone = ChartSeriesBuilder.marketZone
        val latest = lastRegularClose()
        val bars = mutableListOf<PriceBarDTO>()
        for (session in 0 until sessions) {
            val day = (latest - session.days).toLocalDateTime(zone).date
            val open = day.atStartOfDayIn(zone) + (9 * 3600 + 30 * 60).seconds
            // 09:30 to 15:45 in fifteen-minute steps.
            for (step in 0 until 26) {
                val price = 100 + ((session + step) % 5).toDouble()
                bars.add(
                    PriceBarDTO(
                        date = open + (step * 900).seconds,
                        open = price, high = price + 1, low = price - 1, close = price,
                        volume = 7.0, adjustedClose = null,
                    ),
                )
            }
        }
        return bars.sortedBy { it.date }
    }

    override suspend fun profile(symbol: String): CompanyProfileDTO =
        throw APIError.NotFound(DataProviderID.Finnhub, "profile")

    override suspend fun search(query: String): List<CompanyProfileDTO> = emptyList()
}

@OptIn(ExperimentalAtomicApi::class)
class ChartPersistenceTest {

    private fun registry(failing: AtomicBoolean) = ProviderRegistry(
        marketData = FailingAfterFirstProvider(failing), fundamentals = null,
        analyst = null, metrics = null, sec = null, macro = null, news = null,
        isUsingSampleData = false,
    )

    private fun sessionRegistry(sessions: Int) = ProviderRegistry(
        marketData = SessionSeriesProvider(sessions), fundamentals = null,
        analyst = null, metrics = null, sec = null, macro = null, news = null,
        isUsingSampleData = false,
    )

    private fun sessionDays(model: SecurityDetailViewModel): Set<kotlinx.datetime.LocalDate> =
        model.state.value.chartBars
            .map { it.date.toLocalDateTime(ChartSeriesBuilder.marketZone).date }
            .toSet()

    @Test
    fun failedRefreshKeepsTheChart() = seededDatabase { db ->
        val failing = AtomicBoolean(false)
        val registry = registry(failing)
        val model = detailModel(registry, db, ChartRange.OneDay)
        assertEquals(
            ChartAvailability.Ready, model.state.value.chartAvailability,
            "Precondition: a chart is drawn",
        )

        // Now break the network and force a refresh.
        failing.store(true)
        model.load(registry, snapshots = null, force = true)
        model.awaitLoad()
        model.select(ChartRange.FiveDay, registry)
        model.awaitLoad()
        model.select(ChartRange.OneDay, registry)
        model.awaitLoad()

        // The bars we already hold are still perfectly drawable. Replacing them
        // with an error card is the chart "disappearing".
        assertEquals(ChartAvailability.Ready, model.state.value.chartAvailability)
        assertTrue(model.state.value.chartBars.size >= 2)
    }

    @Test
    fun switchingRangesKeepsBothSeries() = seededDatabase { db ->
        val failing = AtomicBoolean(false)
        val registry = registry(failing)
        val model = detailModel(registry, db, ChartRange.OneDay)
        model.select(ChartRange.FiveDay, registry)
        model.awaitLoad()

        // Going back must not re-fetch into an emptied list — which, with the
        // network down, would leave 1D blank despite having been drawn a moment
        // ago.
        failing.store(true)
        model.select(ChartRange.OneDay, registry)
        model.awaitLoad()
        assertEquals(ChartAvailability.Ready, model.state.value.chartAvailability)
    }

    @Test
    fun extendedHoursBarsAreExcluded() = seededDatabase { db ->
        val registry = ProviderRegistry(
            marketData = ExtendedHoursProvider(), fundamentals = null, analyst = null,
            metrics = null, sec = null, macro = null, news = null, isUsingSampleData = false,
        )
        val model = detailModel(registry, db, ChartRange.OneDay)

        val minutes = model.state.value.chartBars.map {
            val time = it.date.toLocalDateTime(ChartSeriesBuilder.marketZone).time
            time.hour * 60 + time.minute
        }

        assertTrue(minutes.isNotEmpty(), "Precondition: the session bars survived")
        // The thin 7 a.m. print was the widest segment on the chart and an
        // artefact of two sparse bars, not a move anyone could have traded.
        assertTrue(minutes.all { it >= 9 * 60 + 30 && it < 16 * 60 })
    }

    @Test
    fun fiveDayCountsSessions() = seededDatabase { db ->
        val registry = sessionRegistry(sessions = 8)
        val model = detailModel(registry, db, ChartRange.FiveDay)

        // Five calendar days back from a Monday reaches the previous Wednesday,
        // and the chart drew three sessions while calling itself five. Sessions
        // are what the range counts.
        assertEquals(5, sessionDays(model).size)

        model.select(ChartRange.OneDay, registry)
        model.awaitLoad()
        assertEquals(1, sessionDays(model).size)
    }

    @Test
    fun axisTicksSitOnBars() = seededDatabase { db ->
        val model = detailModel(sessionRegistry(sessions = 8), db, ChartRange.FiveDay)
        val points = model.state.value.chartPoints
        val ticks = model.state.value.chartAxisTicks

        // Positions run 0..<count with no holes: the axis spends its width on
        // trading rather than on the seventeen hours a day that are not.
        assertEquals(points.indices.toList(), points.map { it.id })
        assertEquals(5, ticks.size, "One label per session")
        assertTrue(ticks.all { tick -> points.any { it.id == tick.id } })
        assertTrue(ticks.all { it.label.isNotEmpty() })
    }

    @Test
    fun overnightLinksAreOneBarWide() = seededDatabase { db ->
        val model = detailModel(sessionRegistry(sessions = 8), db, ChartRange.FiveDay)
        val segments = model.state.value.chartSegments
        val traded = segments.filter { it.kind == ChartSegment.Kind.Traded }
        val overnight = segments.filter { it.kind == ChartSegment.Kind.Overnight }

        assertEquals(5, traded.size)
        assertEquals(4, overnight.size, "One link between each pair of sessions")

        // A link spans a single position. That is the whole argument for drawing
        // it: a stroke that narrow reads as the jump it is, where the same move
        // across a wall-clock weekend read as a steady decline.
        for (link in overnight) {
            assertEquals(2, link.points.size)
            assertEquals(1, link.points[1].id - link.points[0].id)
            assertTrue(link.points[0].session != link.points[1].session)
        }

        // Nothing is dropped or duplicated by the grouping.
        assertEquals(
            model.state.value.chartPoints.map { it.id },
            traded.flatMap { it.points }.map { it.id },
        )
    }

    @Test
    fun oneDayHasNoOvernightLink() = seededDatabase { db ->
        val model = detailModel(sessionRegistry(sessions = 8), db, ChartRange.OneDay)
        val segments = model.state.value.chartSegments
        assertEquals(1, segments.count { it.kind == ChartSegment.Kind.Traded })
        assertTrue(segments.none { it.kind == ChartSegment.Kind.Overnight })
    }
}
