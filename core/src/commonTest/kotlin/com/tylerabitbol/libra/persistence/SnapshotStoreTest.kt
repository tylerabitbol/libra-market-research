package com.tylerabitbol.libra.persistence

import com.tylerabitbol.libra.models.core.BarResolution
import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.core.FinancialConcept
import com.tylerabitbol.libra.models.provenance.ClaimKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.models.provenance.Derivation
import com.tylerabitbol.libra.services.providers.FilingDTO
import com.tylerabitbol.libra.services.providers.FinancialFactDTO
import com.tylerabitbol.libra.services.providers.FiscalPeriodKind
import com.tylerabitbol.libra.services.providers.PriceBarDTO
import com.tylerabitbol.libra.services.providers.QuoteDTO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Append-only persistence semantics.
 *
 * These are the guarantees Section 17 rests on: history accumulates, nothing
 * is overwritten, and refreshing a screen does not multiply rows.
 */
class SnapshotStoreTest {

    private val epoch = Instant.fromEpochSeconds(1_700_000_000)

    private suspend fun makeStore(db: LibraDatabase): SnapshotStore {
        db.securities().upsert(Security(symbol = "TEST", name = "Test Corp"))
        return SnapshotStore(db)
    }

    private fun quote(last: Double, previousClose: Double = 100.0) = QuoteDTO(
        symbol = "TEST", last = last, previousClose = previousClose
    )

    private fun bar(day: Int, close: Double) = PriceBarDTO(
        date = epoch + day.days, open = close, high = close, low = close,
        close = close, volume = 1000.0, adjustedClose = close
    )

    private fun quarterFact(
        concept: FinancialConcept,
        value: Double,
        periodEnd: Instant,
        accession: String,
        filedAt: Instant
    ) = FinancialFactDTO(
        concept = concept,
        periodStart = periodEnd - 90.days,
        periodEnd = periodEnd,
        fiscalYear = 2025,
        fiscalQuarter = 2,
        isAnnual = false,
        periodKind = FiscalPeriodKind.Quarter,
        value = value,
        unit = "USD",
        filedAt = filedAt,
        accessionNumber = accession
    )

    /** Every test opens its own database, so none can see another's rows. */
    private fun withStore(body: suspend (SnapshotStore, LibraDatabase) -> Unit) = runTest {
        val db = inMemoryLibraDatabase()
        try {
            body(makeStore(db), db)
        } finally {
            db.close()
        }
    }

    // MARK: - Writing

    @Test
    fun quotesAlwaysAppend() = withStore { store, _ ->
        store.recordQuote(quote(105.0), "TEST", DataProviderID.Finnhub)
        store.recordQuote(quote(105.0), "TEST", DataProviderID.Finnhub)

        // A quote is a reading at a moment. Two readings are two facts, even at
        // an identical price — that is what makes "since you last looked" work.
        assertEquals(2, store.observationCount("TEST"))
    }

    @Test
    fun barsAreIdempotent() = withStore { store, db ->
        val bars = (0 until 5).map { bar(it, 100.0 + it) }

        val first = store.recordBars(bars, "TEST", BarResolution.Daily, DataProviderID.Tiingo)
        val second = store.recordBars(bars, "TEST", BarResolution.Daily, DataProviderID.Tiingo)

        assertEquals(5, first)
        assertEquals(
            0, second,
            "A closed session does not change; refetching must not duplicate it"
        )
        assertEquals(5, db.priceBars().all("TEST", BarResolution.Daily.raw).size)
    }

    @Test
    fun barsExtendIncrementally() = withStore { store, db ->
        store.recordBars(
            (0 until 5).map { bar(it, 100.0) }, "TEST",
            BarResolution.Daily, DataProviderID.Tiingo
        )
        val added = store.recordBars(
            (0 until 8).map { bar(it, 100.0) }, "TEST",
            BarResolution.Daily, DataProviderID.Tiingo
        )
        assertEquals(3, added)
        assertEquals(8, db.priceBars().all("TEST", BarResolution.Daily.raw).size)
    }

    @Test
    fun restatementsAppend() = withStore { store, db ->
        fun fact(value: Double, accession: String) = FinancialFactDTO(
            concept = FinancialConcept.Revenue, rawTag = "us-gaap:Revenues",
            periodEnd = epoch, fiscalYear = 2025, fiscalQuarter = 2, isAnnual = false,
            periodKind = FiscalPeriodKind.Quarter, value = value, unit = "USD",
            filedAt = epoch, accessionNumber = accession
        )

        store.recordFacts(listOf(fact(100.0, "original")), "TEST", DataProviderID.SEC)
        store.recordFacts(listOf(fact(110.0, "restated")), "TEST", DataProviderID.SEC)

        val stored = db.financialFacts().all("TEST")
        // Both survive: "what did this look like before it was corrected" is
        // precisely the question the append-only design exists to answer.
        assertEquals(2, stored.size)
        assertEquals(setOf(100.0, 110.0), stored.map { it.value }.toSet())
    }

    @Test
    fun identicalFactsAreIdempotent() = withStore { store, db ->
        val fact = FinancialFactDTO(
            concept = FinancialConcept.Revenue, periodEnd = epoch, fiscalYear = 2025,
            fiscalQuarter = 2, isAnnual = false, periodKind = FiscalPeriodKind.Quarter,
            value = 100.0, unit = "USD", accessionNumber = "same"
        )

        store.recordFacts(listOf(fact), "TEST", DataProviderID.SEC)
        store.recordFacts(listOf(fact), "TEST", DataProviderID.SEC)

        assertEquals(1, db.financialFacts().all("TEST").size)
    }

    @Test
    fun filingsAreIdempotent() = withStore { store, db ->
        val filing = FilingDTO(
            accessionNumber = "0000320193-26-000013", formType = "10-Q", filedAt = epoch
        )

        assertEquals(1, store.recordFilings(listOf(filing), "TEST", DataProviderID.SEC))
        // accessionNumber is the primary key; a blind second insert would fail
        // the whole write.
        assertEquals(0, store.recordFilings(listOf(filing), "TEST", DataProviderID.SEC))
        assertEquals(1, db.filings().recent("TEST", 50).size)
    }

    @Test
    fun unknownSymbolIsNotRecorded() = withStore { store, _ ->
        store.recordQuote(quote(105.0), "NOTFOLLOWED", DataProviderID.Finnhub)
        assertEquals(
            0, store.observationCount("NOTFOLLOWED"),
            "A stray symbol must not quietly populate the store"
        )
    }

    @Test
    fun syntheticWritesAreRefusedAtTheDoor() = withStore { store, _ ->
        // A sample bar on disk is indistinguishable from a real one afterwards,
        // and the read path would then serve it in preference to fetching.
        assertEquals(
            0,
            store.recordBars(
                listOf(bar(0, 100.0)), "TEST", BarResolution.Daily, DataProviderID.Sample
            )
        )
        assertTrue(store.bars("TEST").isEmpty())
    }

    @Test
    fun lastQuoteBeforeDate() = withStore { store, _ ->
        store.recordQuote(quote(100.0), "TEST", DataProviderID.Finnhub)
        val cutoff = Clock.System.now()
        store.recordQuote(quote(120.0), "TEST", DataProviderID.Finnhub)

        assertEquals(
            100.0, assertNotNull(store.lastQuote("TEST", before = cutoff)).last,
            "Must return the state as of then, not the latest"
        )
        assertEquals(
            120.0, assertNotNull(store.lastQuote("TEST", before = Clock.System.now())).last
        )
    }

    @Test
    fun recordedQuoteComputesChange() = withStore { store, db ->
        store.recordQuote(quote(110.0, previousClose = 100.0), "TEST", DataProviderID.Finnhub)

        val observation = assertNotNull(db.quotes().latest("TEST"))
        assertEquals(10.0, observation.changePercent)
        assertTrue(observation.observedAt <= Clock.System.now())
    }

    // MARK: - Reading the store back

    @Test
    fun barsRoundTrip() = withStore { store, _ ->
        store.recordBars(
            (0 until 5).map { bar(it, 100.0 + it) }, "TEST",
            BarResolution.Daily, DataProviderID.Tiingo
        )

        val read = store.bars("TEST")
        assertEquals(5, read.size)
        // Ascending by date. A detector handed these in reverse would treat the
        // oldest session as the newest and judge the wrong day.
        assertEquals(listOf(100.0, 101.0, 102.0, 103.0, 104.0), read.map { it.close })
        assertEquals(
            100.0, read.first().adjustedClose,
            "The adjusted series must survive the round trip"
        )
    }

    @Test
    fun barsRespectRange() = withStore { store, _ ->
        store.recordBars(
            (0 until 10).map { bar(it, 100.0 + it) }, "TEST",
            BarResolution.Daily, DataProviderID.Tiingo
        )

        val read = store.bars("TEST", from = epoch + 3.days, to = epoch + 6.days)
        assertEquals(
            listOf(103.0, 104.0, 105.0, 106.0), read.map { it.close },
            "Both bounds are inclusive"
        )
    }

    @Test
    fun barsAreResolutionScoped() = withStore { store, _ ->
        store.recordBars(
            (0 until 3).map { bar(it, 100.0) }, "TEST",
            BarResolution.Daily, DataProviderID.Tiingo
        )

        assertTrue(
            store.bars("TEST", resolution = BarResolution.Weekly).isEmpty(),
            "Mixing resolutions would put weekly bars in a daily return series"
        )
    }

    @Test
    fun factsCollapseRestatements() = withStore { store, _ ->
        store.recordFacts(
            listOf(
                quarterFact(FinancialConcept.Revenue, 100.0, epoch, "original", epoch),
                quarterFact(FinancialConcept.Revenue, 110.0, epoch, "restated", epoch + 1.days)
            ),
            "TEST", DataProviderID.SEC
        )

        val read = store.facts("TEST", concepts = listOf(FinancialConcept.Revenue))
        assertEquals(
            1, read.size,
            "Two conflicting figures for one quarter must not both reach a chart"
        )
        assertEquals(110.0, read.first().value, "The correction supersedes the original")
    }

    @Test
    fun factRevisionsKeepSupersededRows() = withStore { store, _ ->
        store.recordFacts(
            listOf(
                quarterFact(FinancialConcept.Revenue, 100.0, epoch, "original", epoch),
                quarterFact(FinancialConcept.Revenue, 110.0, epoch, "restated", epoch + 1.days)
            ),
            "TEST", DataProviderID.SEC
        )

        val revisions = store.factRevisions("TEST", FinancialConcept.Revenue)
        assertEquals(
            listOf(100.0, 110.0), revisions.map { it.value },
            "Ordered by when each version was filed"
        )
    }

    @Test
    fun factsDoNotCollapseAcrossConcepts() = withStore { store, _ ->
        store.recordFacts(
            listOf(
                quarterFact(FinancialConcept.Revenue, 500.0, epoch, "a", epoch),
                quarterFact(FinancialConcept.NetIncome, 50.0, epoch, "a", epoch)
            ),
            "TEST", DataProviderID.SEC
        )

        // The dedup helper keys on the period alone, because the provider calls
        // it with one concept already fixed. Handed a mixed-concept list it
        // would drop one of these silently.
        assertEquals(
            setOf(FinancialConcept.Revenue, FinancialConcept.NetIncome),
            store.facts("TEST").map { it.concept }.toSet()
        )
    }

    @Test
    fun factsRespectSince() = withStore { store, _ ->
        val old = Instant.fromEpochSeconds(1_600_000_000)
        store.recordFacts(
            listOf(
                quarterFact(FinancialConcept.Revenue, 100.0, old, "old", old),
                quarterFact(FinancialConcept.Revenue, 200.0, epoch, "new", epoch)
            ),
            "TEST", DataProviderID.SEC
        )

        val read = store.facts(
            "TEST", concepts = listOf(FinancialConcept.Revenue),
            since = Instant.fromEpochSeconds(1_650_000_000)
        )
        assertEquals(listOf(200.0), read.map { it.value })
    }

    @Test
    fun factPeriodKindIsRederived() = withStore { store, _ ->
        store.recordFacts(
            listOf(quarterFact(FinancialConcept.Revenue, 100.0, epoch, "a", epoch)),
            "TEST", DataProviderID.SEC
        )

        // periodKind is not a stored column — it is re-derived from the
        // period's own duration. Getting this wrong would let a cumulative
        // nine-month figure read as a quarter, the error that makes Q3 look 3x Q2.
        assertEquals(
            FiscalPeriodKind.Quarter,
            store.facts("TEST", concepts = listOf(FinancialConcept.Revenue)).first().periodKind
        )
    }

    @Test
    fun instantFactsHaveNoDuration() = withStore { store, _ ->
        store.recordFacts(
            listOf(
                FinancialFactDTO(
                    concept = FinancialConcept.CashAndEquivalents, periodEnd = epoch,
                    fiscalYear = 2025, fiscalQuarter = 2, isAnnual = false,
                    periodKind = FiscalPeriodKind.Instant, value = 1000.0, unit = "USD",
                    filedAt = epoch, accessionNumber = "a"
                )
            ),
            "TEST", DataProviderID.SEC
        )

        assertEquals(
            FiscalPeriodKind.Instant,
            store.facts("TEST", concepts = listOf(FinancialConcept.CashAndEquivalents))
                .first().periodKind
        )
    }

    @Test
    fun filingsRoundTrip() = withStore { store, _ ->
        store.recordFilings(
            listOf(
                FilingDTO(accessionNumber = "older", formType = "10-K", filedAt = epoch),
                FilingDTO(
                    accessionNumber = "newer", formType = "10-Q", filedAt = epoch + 1.days
                )
            ),
            "TEST", DataProviderID.SEC
        )

        val read = store.filings("TEST")
        assertEquals(listOf("newer", "older"), read.map { it.accessionNumber })
        assertEquals("10-Q", read.first().formType)
    }

    @Test
    fun latestObservedAtIsNilWhenEmpty() = withStore { store, _ ->
        for (kind in StoredDataKind.entries) {
            assertNull(
                store.latestObservedAt("TEST", kind),
                "$kind must not report freshness for data it does not hold"
            )
        }
    }

    @Test
    fun latestObservedAtIsPerSeries() = withStore { store, _ ->
        store.recordBars(
            listOf(bar(0, 100.0)), "TEST", BarResolution.Daily, DataProviderID.Tiingo
        )

        assertNotNull(store.latestObservedAt("TEST", StoredDataKind.Bars))
        // A screen deciding whether to spend a request must not be told
        // fundamentals are fresh because bars happen to be.
        assertNull(store.latestObservedAt("TEST", StoredDataKind.Facts))
    }

    @Test
    fun derivationsSurviveTheStore() = withStore { store, _ ->
        val event = DetectedEventDTO.create(
            kind = EventKind.UnusualVolume,
            occurredAt = epoch,
            headline = "Volume 2.4x the 60-session median",
            unusualness = 0.98,
            sourceDetails = listOf("Daily bars"),
            derivation = Derivation(
                formula = "volume ÷ median(prior 60)",
                inputs = listOf(
                    Derivation.Input("volume", "214.9M"),
                    Derivation.Input("median", "89.5M")
                ),
                result = "2.4x"
            )
        )
        store.recordEvents(listOf(event), "TEST", DataProviderID.Computed)

        val stored = assertNotNull(store.events("TEST").firstOrNull())
        // Without this, an event read back had no derivation and headlineClaim
        // therefore badged a measured calculation as a FACT — the app
        // misdescribing its own epistemic status, which is the one thing
        // Section 24 exists to prevent.
        assertEquals("volume ÷ median(prior 60)", stored.derivation?.formula)
        assertEquals(2, stored.derivation?.inputs?.size)
        assertEquals(ClaimKind.Calculation, stored.headlineClaim.kind)
    }

    @Test
    fun factsDoNotAcquireArithmetic() = withStore { store, _ ->
        // A filing is reported by the SEC, not computed by this app. Giving it
        // an empty derivation would overstate the app's involvement.
        store.recordEvents(
            listOf(
                DetectedEventDTO.create(
                    kind = EventKind.NewFiling, occurredAt = epoch,
                    headline = "Form 10-Q filed"
                )
            ),
            "TEST", DataProviderID.Computed
        )

        val stored = assertNotNull(store.events("TEST").firstOrNull())
        assertNull(stored.derivation)
        assertEquals(ClaimKind.Fact, stored.headlineClaim.kind)
    }

    @Test
    fun readsAreCaseInsensitive() = withStore { store, _ ->
        store.recordBars(
            listOf(bar(0, 100.0)), "test", BarResolution.Daily, DataProviderID.Tiingo
        )
        store.recordQuote(quote(105.0), "test", DataProviderID.Finnhub)

        assertEquals(1, store.bars("test").size)
        assertEquals(1, store.observationCount("test"))
    }

    // MARK: - Benchmarks

    @Test
    fun aBenchmarkIsCreatedOnceAndExcludedFromScreening() = withStore { store, db ->
        store.ensureBenchmark("XLK", "Technology Select Sector SPDR")
        store.ensureBenchmark("XLK", "Technology Select Sector SPDR")

        val benchmark = assertNotNull(db.securities().find("XLK"))
        assertTrue(benchmark.isBenchmark)
        // A sector ETF is not a company; screening one on revenue growth would
        // return nothing while looking like a result.
        assertEquals(listOf("TEST"), store.screenSubjects().map { it.symbol })
    }
}
