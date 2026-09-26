package com.tylerabitbol.libra.persistence

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.tylerabitbol.libra.models.core.PriceBar

/**
 * The app's database.
 *
 * Swift's `AppModelContainer` held a single `Schema` declaration so that adding
 * a model was one edit and the in-memory test container could not drift from
 * the real one. Same intent here: the entity list lives once, and both
 * [LibraDatabase.inMemory] and the on-disk builder go through it.
 */
@Database(
    entities = [
        Security::class,
        WatchlistEntry::class,
        QuoteObservation::class,
        PriceBar::class,
        FinancialFactRecord::class,
        FilingRecord::class,
        InsiderTransaction::class,
        DetectedEvent::class,
        MacroObservation::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
@ConstructedBy(LibraDatabaseConstructor::class)
abstract class LibraDatabase : RoomDatabase() {
    abstract fun securities(): SecurityDao
    abstract fun watchlist(): WatchlistDao
    abstract fun quotes(): QuoteDao
    abstract fun priceBars(): PriceBarDao
    abstract fun financialFacts(): FinancialFactDao
    abstract fun filings(): FilingDao
    abstract fun insiderTransactions(): InsiderTransactionDao
    abstract fun events(): EventDao
    abstract fun macro(): MacroDao
}

/**
 * Generated per target by Room's compiler. The `expect` has no body by design —
 * KSP writes each `actual`.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object LibraDatabaseConstructor : RoomDatabaseConstructor<LibraDatabase> {
    override fun initialize(): LibraDatabase
}
