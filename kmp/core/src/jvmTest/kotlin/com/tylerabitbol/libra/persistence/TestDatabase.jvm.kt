package com.tylerabitbol.libra.persistence

import androidx.room.Room
import kotlinx.coroutines.Dispatchers

actual fun inMemoryLibraDatabase(): LibraDatabase =
    Room.inMemoryDatabaseBuilder<LibraDatabase>().buildLibraDatabase(Dispatchers.IO)
