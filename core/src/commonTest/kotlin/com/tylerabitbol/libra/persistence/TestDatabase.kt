package com.tylerabitbol.libra.persistence

/**
 * A database that never touches disk — Swift's `AppModelContainer.preview`.
 *
 * `expect` rather than a shared function because Room's in-memory builder is
 * declared per platform, exactly as the on-disk one is. Both actuals route
 * through [buildLibraDatabase], so a test database cannot drift from the
 * shipped schema.
 */
expect fun inMemoryLibraDatabase(): LibraDatabase
