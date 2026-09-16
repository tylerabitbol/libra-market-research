package com.tylerabitbol.libra.persistence

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers

/**
 * `getDatabasePath` rather than a hand-built path: it puts the file where the
 * platform expects it, so it is removed with the app and excluded from the
 * backup of arbitrary files.
 */
fun openLibraDatabase(
    context: Context,
    fileName: String = LIBRA_DATABASE_FILE
): LibraDatabase = Room.databaseBuilder<LibraDatabase>(
    context = context.applicationContext,
    name = context.getDatabasePath(fileName).absolutePath
).buildLibraDatabase(Dispatchers.IO)
