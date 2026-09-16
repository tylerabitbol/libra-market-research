package com.tylerabitbol.libra.app

import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.persistence.LibraDatabase
import com.tylerabitbol.libra.persistence.Security
import com.tylerabitbol.libra.persistence.WatchlistEntry
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.services.secrets.SecretsStore
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.time.Clock

/**
 * What the process was launched with, supplied by the platform shell.
 *
 * Swift reads `CommandLine.arguments` and `ProcessInfo.environment` directly
 * and compiles the whole file out of release builds with `#if DEBUG`. Neither
 * has a Kotlin equivalent that works on both targets: an Android process has no
 * argv at all, and a common-source library has no build-configuration flag. So
 * the three inputs are passed in instead — which also makes every option below
 * testable without a process to launch.
 *
 * The defaults are the release ones: no arguments, no environment, not a debug
 * build. A shell that supplies nothing gets a [DeveloperOptions] that does
 * nothing, which is the behaviour Swift got from the compiler.
 */
data class LaunchEnvironment(
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    /**
     * Whether this is a debug build. Every option below is gated on it, so a
     * release build ignores an argument that somehow reaches it.
     */
    val isDebugBuild: Boolean = false,
) {
    companion object {
        /** The release default: every developer option off. */
        val release = LaunchEnvironment()
    }
}

/**
 * Debug-build conveniences for testing against live APIs.
 *
 * Keys are read from the environment, never compiled in. Hardcoding them would
 * put live credentials in source control, in every build artefact, and in any
 * crash log — and the repository is exactly where secrets are hardest to recall
 * once they have been committed.
 *
 * On iOS, set them on the Run scheme (Product → Scheme → Edit Scheme → Run →
 * Arguments → Environment Variables), or pass them to a simulator launch with
 * simctl's `SIMCTL_CHILD_` prefix:
 *
 * ```
 * SIMCTL_CHILD_LIBRA_FINNHUB_KEY=… \
 * xcrun simctl launch booted com.tylerabitbol.libra -LibraSeedKeys
 * ```
 *
 * Seeding requires the `-LibraSeedKeys` launch argument as well as the
 * environment values, so a stray variable in a shell profile cannot silently
 * overwrite the keys entered in Settings.
 */
class DeveloperOptions(private val launch: LaunchEnvironment = LaunchEnvironment.release) {

    private val arguments: List<String>
        get() = if (launch.isDebugBuild) launch.arguments else emptyList()

    val isSeedRequested: Boolean get() = seedArgument in arguments

    val isWatchlistSeedRequested: Boolean get() = seedWatchlistArgument in arguments

    /**
     * `-LibraOpenSymbol AAPL` opens straight to a security's detail page.
     *
     * Exists because capturing that screen previously meant editing the root
     * view by hand, and one of those edits was committed — leaving the Watchlist
     * tab wired to a hardcoded symbol. A supported route costs a few lines and
     * removes the need to touch navigation at all.
     */
    val debugSymbol: String?
        get() = value("-LibraOpenSymbol")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    /** The value following [flag], or null when it is absent or last. */
    private fun value(flag: String): String? {
        val index = arguments.indexOf(flag)
        if (index < 0 || index + 1 >= arguments.size) return null
        return arguments[index + 1]
    }

    /**
     * Populates the watchlist with a few well-known symbols.
     *
     * Debug builds only, and only on an explicit launch argument. Existing
     * entries are left alone so this never clobbers a real watchlist.
     */
    suspend fun seedWatchlist(database: LibraDatabase) {
        if (!isWatchlistSeedRequested) return

        val securities = database.securities()
        val watchlist = database.watchlist()
        for (seed in watchlistSeeds) {
            val existing = securities.find(seed.symbol)
            if (existing == null) {
                securities.upsert(
                    Security(
                        symbol = seed.symbol, name = seed.name,
                        sector = seed.sector, cik = seed.cik,
                    ),
                )
            }
            if (watchlist.find(seed.symbol) != null) continue
            watchlist.upsert(WatchlistEntry(symbol = seed.symbol))
        }
        logger.i { "Seeded watchlist with ${watchlistSeeds.size} securities." }
    }

    /**
     * `-LibraBackdateVisits 30` moves every recorded visit stamp back by that
     * many days.
     *
     * Exists because "what changed since you last looked" cannot be exercised on
     * demand: it needs a *past* visit and closed sessions after it, which
     * otherwise means waiting days between runs. Backdating the stamp makes the
     * detectors treat already-stored bars as unseen, which is exactly the state
     * a returning user is in.
     *
     * It moves the reference point and nothing else — no event is fabricated,
     * and anything that appears was detected from real stored bars.
     */
    suspend fun backdateVisits(database: LibraDatabase) {
        val days = value("-LibraBackdateVisits")?.toIntOrNull() ?: return
        if (days <= 0) return

        val cutoff = Clock.System.now().minus(DatePeriod(days = days), TimeZone.currentSystemDefault())
        val securities = database.securities().all()
        for (security in securities) {
            database.securities().markViewed(security.symbol, cutoff)
        }
        logger.i { "Backdated ${securities.size} visit stamps by $days days." }
    }

    /**
     * Copies any provided environment values into secure storage.
     *
     * Does nothing outside a debug build. Logs which keys were seeded and never
     * their values — a log line is not a place for a credential.
     */
    fun seedSecretsFromEnvironment(secrets: SecretsStore): List<SecretKey> {
        if (!isSeedRequested) return emptyList()

        val seeded = mutableListOf<SecretKey>()
        for ((key, variable) in mapping) {
            val raw = launch.environment[variable]?.trim()
            if (raw.isNullOrEmpty()) continue
            try {
                secrets.set(raw, key)
                seeded.add(key)
            } catch (error: Exception) {
                logger.e { "Could not seed ${key.raw}: ${error.message}" }
            }
        }

        if (seeded.isEmpty()) {
            logger.i { "Seed requested but no LIBRA_* environment variables were set." }
        } else {
            logger.i { "Seeded from environment: ${seeded.joinToString(", ") { it.raw }}" }
        }
        return seeded
    }

    private data class WatchlistSeed(
        val symbol: String,
        val name: String,
        val sector: String,
        val cik: String,
    )

    companion object {
        const val seedArgument = "-LibraSeedKeys"
        const val seedWatchlistArgument = "-LibraSeedWatchlist"

        private val logger = Logger.withTag("devoptions")

        /** Environment variable backing each secret. */
        private val mapping: List<Pair<SecretKey, String>> = listOf(
            SecretKey.FinnhubAPIKey to "LIBRA_FINNHUB_KEY",
            SecretKey.TiingoAPIKey to "LIBRA_TIINGO_KEY",
            SecretKey.FredAPIKey to "LIBRA_FRED_KEY",
            SecretKey.SecContactEmail to "LIBRA_SEC_EMAIL",
        )

        private val watchlistSeeds = listOf(
            WatchlistSeed("AAPL", "Apple Inc.", "Information Technology", "0000320193"),
            WatchlistSeed("NVDA", "NVIDIA Corporation", "Information Technology", "0001045810"),
            WatchlistSeed("COST", "Costco Wholesale Corporation", "Consumer Staples", "0000909832"),
        )
    }
}
