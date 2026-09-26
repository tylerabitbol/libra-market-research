package com.tylerabitbol.libra.services.secrets

/**
 * Credentials the app needs, held in platform secure storage.
 *
 * Section 19 requires that keys never appear in the client UI or in source.
 * The user enters each value once in Settings → Data Sources; it goes straight
 * to the keystore and is never written to the snapshot store, never logged,
 * and never included in a cache key. Reads return the value only to the
 * provider that needs it for an Authorization header.
 *
 * `raw` is the storage account name. It is written into the keychain and into
 * SharedPreferences, so renaming a case orphans whatever the user already
 * entered — the strings are deliberately spelled out rather than derived from
 * [name].
 */
enum class SecretKey(val raw: String) {
    FinnhubAPIKey("finnhubAPIKey"),
    TiingoAPIKey("tiingoAPIKey"),

    /** Alpaca authenticates with a key *pair*, both halves sent as headers. */
    AlpacaKeyID("alpacaKeyID"),
    AlpacaSecretKey("alpacaSecretKey"),

    FredAPIKey("fredAPIKey"),

    /**
     * Not a credential, but it belongs with them: SEC EDGAR's fair-access
     * policy requires a contact address in the User-Agent header, and that
     * address is personal data that shouldn't be committed to the repo.
     */
    SecContactEmail("secContactEmail"),

    /**
     * The organisation half of the SEC User-Agent. Optional: defaults to the
     * app name, which satisfies the policy on its own.
     */
    SecOrganizationName("secOrganizationName");

    val displayName: String
        get() = when (this) {
            FinnhubAPIKey -> "Finnhub API key"
            TiingoAPIKey -> "Tiingo API key"
            AlpacaKeyID -> "Alpaca key ID"
            AlpacaSecretKey -> "Alpaca secret key"
            FredAPIKey -> "FRED API key"
            SecContactEmail -> "SEC contact email"
            SecOrganizationName -> "SEC organisation name"
        }

    val helpText: String
        get() = when (this) {
            FinnhubAPIKey ->
                "Free key from finnhub.io. Used for quotes, company profiles, " +
                    "fundamentals and news."
            TiingoAPIKey ->
                "Free key from tiingo.com. Used for daily price history — charts, " +
                    "moving averages and volatility all depend on it."
            AlpacaKeyID ->
                "Free from alpaca.markets — no funding required. Used only for the " +
                    "1D and 5D intraday charts."
            AlpacaSecretKey ->
                "The secret half of the Alpaca key pair. Both halves are needed; " +
                    "neither works alone."
            FredAPIKey ->
                "Free key from fred.stlouisfed.org. Used for macroeconomic series."
            SecContactEmail ->
                "Required by the SEC. They ask every automated client to identify " +
                    "itself with a contact address. Requests to EDGAR are disabled " +
                    "until this is set."
            SecOrganizationName ->
                "Optional. The SEC's documented User-Agent format is " +
                    "\"Company Name contact@domain.com\". Leave blank to identify as " +
                    "\"Libra\"."
        }

    /**
     * Neither the contact email nor the organisation name is a credential;
     * both are identification, and both are more useful shown in full so a
     * typo is visible.
     */
    val isSensitive: Boolean
        get() = this != SecContactEmail && this != SecOrganizationName

    companion object {
        fun fromRaw(raw: String?): SecretKey? = entries.firstOrNull { it.raw == raw }
    }
}
