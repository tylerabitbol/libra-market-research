package com.tylerabitbol.libra.persistence

import com.tylerabitbol.libra.models.core.DetectedEventDTO
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.models.provenance.DataProviderID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Storing and reading detected events.
 *
 * The second suite in Swift's `EventDetectionTests.swift`, deferred out of
 * Phase 2 because every case needs the store.
 */
class EventPersistenceTest {

    private val epoch = Instant.fromEpochSeconds(1_700_000_000)

    private fun event(kind: EventKind, dayOffset: Int) = DetectedEventDTO.create(
        kind = kind,
        occurredAt = epoch + dayOffset.days,
        headline = "${kind.displayName} on day $dayOffset",
        unusualness = 0.99
    )

    private fun withStore(body: suspend (SnapshotStore, LibraDatabase) -> Unit) = runTest {
        val db = inMemoryLibraDatabase()
        try {
            db.securities().upsert(Security(symbol = "TEST", name = "Test Corp"))
            body(SnapshotStore(db), db)
        } finally {
            db.close()
        }
    }

    @Test
    fun provisionalEventsAreNotStored() = withStore { store, _ ->
        val provisional = DetectedEventDTO.create(
            kind = EventKind.UnusualPriceMove,
            occurredAt = epoch,
            headline = "Price rose 8.7% so far today",
            isProvisional = true
        )

        // Storing it would freeze a midday figure as what happened that day.
        assertEquals(
            0, store.recordEvents(listOf(provisional), "TEST", DataProviderID.Computed)
        )
        assertTrue(store.events("TEST").isEmpty())
    }

    @Test
    fun eventsAreIdempotent() = withStore { store, _ ->
        val events = listOf(event(EventKind.UnusualVolume, 0))

        val first = store.recordEvents(events, "TEST", DataProviderID.Computed)
        val second = store.recordEvents(events, "TEST", DataProviderID.Computed)

        assertEquals(1, first)
        assertEquals(0, second, "Detection re-runs on every visit over the same bars")
    }

    @Test
    fun differentDaysAreDistinct() = withStore { store, _ ->
        store.recordEvents(
            listOf(event(EventKind.UnusualVolume, 0)), "TEST", DataProviderID.Computed
        )
        store.recordEvents(
            listOf(event(EventKind.UnusualVolume, 1)), "TEST", DataProviderID.Computed
        )

        assertEquals(2, store.events("TEST").size)
    }

    @Test
    fun eventsReadBackOrdered() = withStore { store, _ ->
        store.recordEvents(
            listOf(
                event(EventKind.UnusualVolume, 0),
                event(EventKind.UnusualPriceMove, 5)
            ),
            "TEST", DataProviderID.Computed
        )

        val stored = store.events("TEST")
        assertEquals(EventKind.UnusualPriceMove, stored.first().kind)
        assertEquals(0.99, stored.first().unusualness)
    }

    @Test
    fun lastViewedStartsEmpty() = withStore { store, _ ->
        // Null is what makes a first visit report nothing as new, rather than
        // reporting the entire available history.
        assertNull(store.lastViewed("TEST"))
    }

    @Test
    fun markViewedRoundTrips() = withStore { store, _ ->
        store.markViewed("TEST", at = epoch)
        assertEquals(epoch, store.lastViewed("TEST"))
    }

    @Test
    fun unknownSymbolIsIgnored() = withStore { store, _ ->
        // History is kept only for companies the user actually follows, so a
        // stray symbol cannot quietly populate the store.
        assertEquals(
            0,
            store.recordEvents(
                listOf(event(EventKind.UnusualVolume, 0)), "NOPE", DataProviderID.Computed
            )
        )
    }

    @Test
    fun acknowledgeIsTargeted() = withStore { store, db ->
        val target = event(EventKind.UnusualVolume, 0)
        store.recordEvents(
            listOf(target, event(EventKind.UnusualPriceMove, 0)),
            "TEST", DataProviderID.Computed
        )

        store.acknowledge("TEST", target.naturalKey)

        val acknowledged = db.events().forSymbol("TEST", 50).filter { it.isAcknowledged }
        assertEquals(1, acknowledged.size)
        assertEquals(EventKind.UnusualVolume, acknowledged.first().kind)
    }

    @Test
    fun feedCarriesSymbol() = withStore { store, db ->
        db.securities().upsert(Security(symbol = "OTHER", name = "Other Corp"))

        store.recordEvents(
            listOf(event(EventKind.UnusualVolume, 0)), "TEST", DataProviderID.Computed
        )
        store.recordEvents(
            listOf(event(EventKind.UnusualPriceMove, 1)), "OTHER", DataProviderID.Computed
        )

        val feed = store.recentEvents()
        assertEquals(2, feed.size)
        // The stored row carries a symbol but not a name, and a feed row has to
        // be able to label itself.
        assertEquals("OTHER", feed.first().symbol)
        assertEquals(setOf("TEST", "OTHER"), feed.map { it.symbol }.toSet())
    }
}
