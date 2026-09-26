package com.tylerabitbol.libra.models.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from LibraTests/FormatTests.swift, suite "Benchmarks". */
class BenchmarkTest {

    @Test
    fun the_major_indexes_use_real_fred_series_not_etf_proxies() {
        for (id in listOf("sp500", "nasdaq", "dow")) {
            val benchmark = assertNotNull(Benchmark.broadMarket.firstOrNull { it.id == id })
            assertTrue(benchmark.hasRealIndex, "$id should resolve to a genuine index series")
            assertFalse(benchmark.isProxy)
            assertNull(benchmark.proxyNote, "A real index needs no substitution caveat")
        }
        assertEquals("SP500", Benchmark.broadMarket.firstOrNull { it.id == "sp500" }?.fredSeriesID)
    }

    @Test
    fun volatility_is_the_real_vix_not_a_futures_etf() {
        val vix = assertNotNull(Benchmark.volatility.firstOrNull())
        assertEquals("VIXCLS", vix.fredSeriesID)
        assertNull(vix.etfSymbol, "The decaying VIX-futures proxy was removed deliberately")
        assertFalse(vix.isProxy)
    }

    @Test
    fun benchmarks_without_a_fred_series_keep_an_etf_and_say_they_are_proxies() {
        val russell = assertNotNull(Benchmark.broadMarket.firstOrNull { it.id == "russell2000" })
        assertNull(russell.fredSeriesID)
        assertTrue(russell.isProxy)
        assertTrue(russell.proxyNote?.isNotEmpty() == true)

        // FRED publishes no sector index, so every sector row is a proxy.
        assertTrue(Benchmark.sectors.all { it.isProxy })
        assertTrue(Benchmark.sectors.all { it.proxyNote?.isNotEmpty() == true })
    }

    @Test
    fun all_eleven_gics_sectors_are_covered() {
        assertEquals(11, Benchmark.sectors.size)
    }

    @Test
    fun benchmark_identifiers_are_unique_and_every_row_has_a_data_source() {
        assertEquals(Benchmark.all.size, Benchmark.all.map { it.id }.toSet().size)
        assertTrue(
            Benchmark.all.all { it.fredSeriesID != null || it.etfSymbol != null },
            "A benchmark with no source could never render a value"
        )
    }

    @Test
    fun a_companys_sector_maps_to_the_matching_sector_benchmark() {
        assertEquals("XLK", Benchmark.sector("Information Technology")?.etfSymbol)
        assertEquals("XLF", Benchmark.sector("Financials")?.etfSymbol)
    }

    @Test
    fun an_unrecognised_sector_matches_nothing_rather_than_a_wrong_benchmark() {
        assertNull(
            Benchmark.sector("Blockchain Widgets"),
            "A wrong sector comparison is worse than none"
        )
        assertNull(Benchmark.sector(null))
    }

    @Test
    fun an_industry_level_label_resolves_to_its_gics_sector() {
        // NVIDIA reports "Semiconductors", which shares no substring with any
        // sector name and previously fell through to no benchmark at all.
        assertEquals("XLK", Benchmark.sector("Semiconductors")?.etfSymbol)
        assertEquals("XLV", Benchmark.sector("Pharmaceuticals")?.etfSymbol)
        assertEquals("XLF", Benchmark.sector("Banking")?.etfSymbol)
        assertEquals("XLP", Benchmark.sector("Beverages")?.etfSymbol)
        assertEquals("XLI", Benchmark.sector("Aerospace & Defense")?.etfSymbol)
        assertEquals(
            "XLK",
            Benchmark.sector("  semiconductors  ")?.etfSymbol,
            "Case and surrounding whitespace are the vendor's, not the reader's"
        )
    }

    @Test
    fun a_label_with_no_sensible_parent_still_falls_through() {
        assertNull(
            Benchmark.sector("Diversified Financial Services"),
            "A genuinely diversified issuer has no single sector to name"
        )
    }
}
