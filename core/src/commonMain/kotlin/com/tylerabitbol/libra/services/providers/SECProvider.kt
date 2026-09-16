package com.tylerabitbol.libra.services.providers

import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.networking.APIError
import com.tylerabitbol.libra.networking.Endpoint
import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.networking.serializers.VendorDate
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.services.secrets.SecretsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Filings and company identity from SEC EDGAR.
 *
 * EDGAR requires no API key. What it does require is a `User-Agent` that
 * identifies the requester — their documented format is
 * "Company Name contact@domain.com" — and requests without one are refused
 * outright. Their fair-access policy also caps traffic at about 10 requests a
 * second, which the shared rate limiter enforces at 8.
 *
 * Two hosts are involved and they are not interchangeable: the ticker-to-CIK
 * map is a static file on www.sec.gov, while the JSON APIs live on
 * data.sec.gov.
 */
class SECProvider(
    private val client: HTTPClient,
    private val secrets: SecretsStore,
) : SECDataProvider {

    override val id: DataProviderID = DataProviderID.SEC

    override suspend fun isConfigured(): Boolean = secrets.hasValue(SecretKey.SecContactEmail)

    /**
     * "Libra someone@example.com" — the identification EDGAR requires.
     *
     * Throws rather than substituting a placeholder address: sending an
     * unreachable contact would defeat the entire point of the policy.
     */
    private fun userAgentHeaders(): Map<String, String> {
        val email = secrets.require(SecretKey.SecContactEmail, DataProviderID.SEC)
        val organization = secrets.value(SecretKey.SecOrganizationName)?.trim()
        val name = if (!organization.isNullOrEmpty()) organization else DEFAULT_ORGANIZATION
        return mapOf(
            "User-Agent" to "$name $email",
            "Accept-Encoding" to "gzip, deflate",
            "Host" to "data.sec.gov",
        )
    }

    // MARK: - Identity

    /**
     * Maps a ticker to a zero-padded 10-digit CIK.
     *
     * The whole map is one ~1MB file with no per-ticker endpoint, so it is
     * fetched once and cached in memory for the process lifetime.
     */
    override suspend fun resolveCIK(symbol: String): String {
        val map = tickerMap(client, userAgentHeaders())
        return map[symbol.uppercase()]
            ?: throw APIError.NotFound(DataProviderID.SEC, endpoint = "company_tickers")
    }

    private suspend fun tickerMap(
        client: HTTPClient,
        headers: Map<String, String>,
    ): Map<String, String> {
        cache.value()?.let { return it }

        val fileHeaders = headers + mapOf("Host" to "www.sec.gov")
        val endpoint = Endpoint(
            provider = DataProviderID.SEC,
            baseURL = WWW_HOST,
            path = "/files/company_tickers.json",
            headers = fileHeaders,
            label = "company_tickers",
        )
        // Keyed by row index ("0", "1", …) rather than by ticker, so it decodes
        // as a map of records and is inverted here.
        val rows: Map<String, TickerRow> = client.get(endpoint)
        val map = buildMap {
            for (row in rows.values) {
                // First wins, matching Swift's `uniquingKeysWith`.
                putIfAbsent(row.ticker.uppercase(), padCIK(row.cikStr))
            }
        }
        cache.store(map)
        return map
    }

    // MARK: - Filings

    override suspend fun filings(
        cik: String,
        formTypes: List<String>,
        limit: Int,
    ): List<FilingDTO> {
        val padded = normalizedCIK(cik)
        val endpoint = Endpoint(
            provider = DataProviderID.SEC,
            baseURL = DATA_HOST,
            path = "/submissions/CIK$padded.json",
            headers = userAgentHeaders(),
            label = "submissions",
        )
        val response: SubmissionsResponse = client.get(endpoint)
        val all = response.filings.recent.filings(padded)

        val filtered = if (formTypes.isEmpty()) all else all.filter { it.formType in formTypes }
        return filtered.take(limit)
    }

    /**
     * Form 4 ownership documents are XML, and each must be fetched and parsed
     * individually.
     *
     * One request per filing. EDGAR is free and allows roughly ten a second,
     * so the cost is latency rather than quota — but it is still bounded by
     * [DOCUMENT_LIMIT], because a prolific filer can lodge hundreds a year and
     * no screen shows them all.
     *
     * A document that fails to fetch or parse is skipped rather than failing
     * the batch. One malformed filing among twenty must not turn the other
     * nineteen into "no insider trades" — the reading this method refused to
     * produce when it was unimplemented.
     */
    override suspend fun insiderTransactions(
        cik: String,
        since: Instant?,
    ): List<InsiderTransactionDTO> {
        val forms = filings(cik, formTypes = listOf("4"), limit = DOCUMENT_LIMIT)
        val relevant = if (since == null) forms else forms.filter { it.filedAt >= since }
        if (relevant.isEmpty()) return emptyList()

        val collected = mutableListOf<InsiderTransactionDTO>()
        for (filing in relevant) {
            val url = ownershipXMLURL(filing) ?: continue
            val endpoint = Endpoint(
                provider = DataProviderID.SEC,
                baseURL = url,
                path = "",
                headers = userAgentHeaders(),
                label = "form4",
            )
            val parsed = try {
                Form4Parser.parse(
                    client.data(endpoint).decodeToString(),
                    accessionNumber = filing.accessionNumber,
                    filedAt = filing.filedAt,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                continue
            }
            collected += parsed
        }
        return collected.sortedByDescending { it.transactionDate }
    }

    companion object {
        const val DATA_HOST = "https://data.sec.gov"
        const val WWW_HOST = "https://www.sec.gov"

        /** Used when the user hasn't supplied an organisation name. */
        const val DEFAULT_ORGANIZATION = "Libra"

        /** How many ownership documents one call will fetch. */
        const val DOCUMENT_LIMIT = 20

        /** Caches the ticker map for the process lifetime. */
        private val cache = TickerMapCache()

        /**
         * EDGAR paths need the CIK zero-padded to ten digits; the JSON carries
         * it as a bare integer.
         */
        fun padCIK(value: Int): String = value.toString().padStart(10, '0')

        /**
         * Normalises a CIK to ten digits, rejecting anything unparseable.
         *
         * Throws rather than defaulting to zero. A `?: 0` fallback builds a
         * perfectly well-formed request for CIK 0000000000 — a silent wrong
         * question, whose answer is either a 404 or, worse, some other filer.
         */
        fun normalizedCIK(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.length == 10 && trimmed.all { it.isDigit() }) return trimmed
            val value = trimmed.toIntOrNull()
            if (value == null || value <= 0) {
                throw APIError.NotFound(DataProviderID.SEC, endpoint = "CIK $raw")
            }
            return padCIK(value)
        }

        /**
         * The raw XML behind a Form 4, rather than the styled version.
         *
         * The submissions feed's `primaryDocument` for an ownership form
         * usually points at an XSL-rendered copy under a `xslF345X0N/`
         * directory. That path serves HTML; the machine-readable document is
         * the same filename one level up, so the styling directory is dropped
         * when present.
         */
        fun ownershipXMLURL(filing: FilingDTO): String? {
            val url = filing.primaryDocumentURL ?: return null
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return url

            val afterScheme = url.substring(schemeEnd + 3)
            val hostEnd = afterScheme.indexOf('/')
            if (hostEnd < 0) return url

            val origin = url.substring(0, schemeEnd + 3 + hostEnd)
            val path = afterScheme.substring(hostEnd)
            val segments = path.split('/').filter { it.isNotEmpty() }
            val styled = segments.indexOfFirst { it.lowercase().startsWith("xsl") }
            if (styled < 0) return url

            val rebuilt = segments.filterIndexed { index, _ -> index != styled }
            return origin + "/" + rebuilt.joinToString("/")
        }
    }
}

/**
 * Caches the ticker map for the process lifetime.
 *
 * Swift used an `actor`; the Kotlin equivalent is a [Mutex]. The whole map is
 * one ~1MB file with no per-ticker endpoint, so fetching it per lookup would
 * be absurd.
 */
private class TickerMapCache {
    private val mutex = Mutex()
    private var stored: Map<String, String>? = null

    suspend fun value(): Map<String, String>? = mutex.withLock { stored }

    suspend fun store(map: Map<String, String>) {
        mutex.withLock { stored = map }
    }
}

// MARK: - Wire format

@Serializable
internal data class TickerRow(
    @SerialName("cik_str") val cikStr: Int,
    val ticker: String,
    val title: String? = null,
)

@Serializable
data class SubmissionsResponse(
    val cik: String? = null,
    val name: String? = null,
    val filings: Filings,
) {
    @Serializable
    data class Filings(val recent: Recent)

    /**
     * EDGAR returns filings as **parallel arrays**, not as an array of
     * objects: `form[i]` describes the same filing as `accessionNumber[i]`.
     * Zipping them by index is required, and any length mismatch means a row
     * cannot be trusted — so the shortest array bounds the result.
     */
    @Serializable
    data class Recent(
        val accessionNumber: List<String> = emptyList(),
        val filingDate: List<String> = emptyList(),
        val reportDate: List<String>? = null,
        val form: List<String> = emptyList(),
        val primaryDocument: List<String>? = null,
    ) {
        fun filings(cik: String): List<FilingDTO> {
            val count = minOf(accessionNumber.size, filingDate.size, form.size)
            // A CIK that will not parse cannot produce a correct archive URL,
            // and a URL pointing at the wrong filer is worse than no link.
            val numericCIK = cik.trim().toIntOrNull()
            if (numericCIK == null || numericCIK <= 0) return emptyList()

            return (0 until count).mapNotNull { index ->
                val filed = VendorDate.day(filingDate[index]) ?: return@mapNotNull null

                val accession = accessionNumber[index]
                val bare = accession.replace("-", "")
                val base = "https://www.sec.gov/Archives/edgar/data/$numericCIK/$bare"
                val document = primaryDocument?.getOrNull(index)

                FilingDTO(
                    accessionNumber = accession,
                    formType = form[index],
                    filedAt = filed,
                    periodOfReport = reportDate?.getOrNull(index)?.let { VendorDate.day(it) },
                    primaryDocumentURL = document?.let { "$base/$it" },
                    filingIndexURL = "$base/$accession-index.htm",
                )
            }
        }
    }
}
