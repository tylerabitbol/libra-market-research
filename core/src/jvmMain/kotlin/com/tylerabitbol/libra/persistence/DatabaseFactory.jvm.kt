package com.tylerabitbol.libra.persistence

import androidx.room.Room
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * The JVM target exists so `commonTest` runs in seconds rather than through a
 * simulator. This builder is for local tooling; the shipped apps use the
 * Android and iOS ones.
 */
fun openLibraDatabase(directory: File, fileName: String = LIBRA_DATABASE_FILE): LibraDatabase =
    Room.databaseBuilder<LibraDatabase>(name = File(directory, fileName).absolutePath)
        .buildLibraDatabase(Dispatchers.IO)
