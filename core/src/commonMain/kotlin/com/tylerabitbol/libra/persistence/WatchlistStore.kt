package com.tylerabitbol.libra.persistence

import com.tylerabitbol.libra.services.providers.CompanyProfileDTO

/**
 * Membership: which securities the user follows, and in what order.
 *
 * Separate from [SnapshotStore] on purpose. That one records what was
 * *fetched* — an append-only log of observations — and membership is a
 * different thing entirely: it is what the user chose, it is edited rather
 * than appended, and removing it must leave every observation intact.
 *
 * It exists at all because SwiftUI hands a view its `modelContext` and lets
 * the watchlist screen write rows directly. Compose has no equivalent, and the
 * alternative was exposing `LibraDatabase` to `:app` — which would put Room on
 * the UI module's compile classpath for the sake of two calls.
 */
class WatchlistStore(private val db: LibraDatabase) {

    /** The list as shown: name and sector joined in, user order first. */
    suspend fun members(): List<WatchlistMember> = db.watchlist().members()

    /**
     * Adds a security, reusing the row if one already exists.
     *
     * Symbol is the primary key, so a second insert of the same company would
     * replace the profile rather than duplicate it — and would throw away a CIK
     * or sector the detail page had already learned.
     */
    suspend fun add(profile: CompanyProfileDTO, priority: Int) {
        val symbol = profile.symbol.uppercase()
        if (db.securities().find(symbol) == null) {
            db.securities().upsert(
                Security(
                    symbol = symbol, name = profile.name, exchange = profile.exchange,
                    sector = profile.sector, industry = profile.industry,
                    cik = profile.cik, currency = profile.currency,
                ),
            )
        }
        if (db.watchlist().find(symbol) != null) return
        db.watchlist().upsert(WatchlistEntry(symbol = symbol, priority = priority))
    }

    /**
     * Drops the membership only.
     *
     * The [Security] and its recorded history stay: Section 17 depends on not
     * discarding history, and re-adding later should find it intact.
     */
    suspend fun remove(symbol: String) {
        db.watchlist().remove(symbol.uppercase())
    }
}
