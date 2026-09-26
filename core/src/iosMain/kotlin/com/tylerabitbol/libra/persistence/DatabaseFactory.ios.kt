package com.tylerabitbol.libra.persistence

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The database lives in Documents, which is backed up and is where a user's
 * own data belongs — Caches would let the system delete a year of recorded
 * observations to reclaim space.
 */
@OptIn(ExperimentalForeignApi::class)
fun openLibraDatabase(fileName: String = LIBRA_DATABASE_FILE): LibraDatabase {
    val documents: NSURL = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    ) ?: error("Could not resolve the Documents directory")

    val path = requireNotNull(documents.URLByAppendingPathComponent(fileName)?.path) {
        "Could not build a database path"
    }
    // `Dispatchers.IO` is internal on Kotlin/Native in this coroutines
    // release, so queries run on Default. Both are multi-threaded pools here;
    // the distinction that matters on the JVM — not tying up CPU workers with
    // blocking IO — does not apply to the bundled driver's own threads.
    return Room.databaseBuilder<LibraDatabase>(name = path)
        .buildLibraDatabase(Dispatchers.Default)
}
