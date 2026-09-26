package com.tylerabitbol.libra.persistence

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.CoroutineContext

/** The file name the on-disk database uses on every platform. */
const val LIBRA_DATABASE_FILE = "libra.db"

/**
 * The settings every build of the database shares.
 *
 * Each platform supplies only what it alone knows — a path, and on Android a
 * `Context` — and routes through here, so the on-disk database and the
 * in-memory one used by tests cannot drift apart. That was the point of
 * Swift's single `Schema` declaration and it is the point of this.
 *
 * Builders themselves are per platform, which is Room's own KMP shape rather
 * than a choice: Android's needs a `Context`, iOS builds a path under
 * Documents, and there is no common overload that covers both.
 *
 * [BundledSQLiteDriver] ships its own SQLite rather than binding whatever
 * version the OS happens to carry, so a query that works on one iOS release
 * cannot fail on another.
 */
fun RoomDatabase.Builder<LibraDatabase>.buildLibraDatabase(
    queryContext: CoroutineContext
): LibraDatabase = this
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(queryContext)
    .build()
