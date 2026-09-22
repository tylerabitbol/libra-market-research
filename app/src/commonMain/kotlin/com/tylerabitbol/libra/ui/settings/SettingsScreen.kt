package com.tylerabitbol.libra.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.app.SourceReadiness
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.providers.ConnectionTest
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.Footnote
import com.tylerabitbol.libra.ui.components.SampleDataBanner
import kotlinx.coroutines.launch

/**
 * Settings, with Data Sources as the substantive part.
 *
 * Section 19: keys never appear in source or in the UI beyond the field the
 * user types them into. Values go straight to secure storage; this screen only
 * ever asks whether a key *exists*, never reads one back for display.
 */
@Composable
fun SettingsScreen(
    environment: AppEnvironment,
    onOpenKey: (SecretKey, DataProviderID) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Read as snapshots rather than collected flows: these change only when the
    // user saves a key, and this screen is what saves it.
    val health = environment.secretsHealth.collectAsStateValue()
    val configured = environment.configuredKeys.collectAsStateValue()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.medium),
    ) {
        if (environment.isUsingSampleData) {
            item { SampleDataBanner() }
        }

        health.reason?.let { reason ->
            item { StorageUnavailableBanner(reason) }
        }

        item { SectionHeader("Data sources") }

        items(dataSources, key = { it.key.raw }) { source ->
            val readiness = environment.readiness(source.provider)
            Column(verticalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
                DataSourceRow(
                    source = source,
                    readiness = readiness,
                    // Typing a key into a store that cannot retain it is worse
                    // than saying up front that it won't work.
                    enabled = health.isAvailable,
                    onClick = { onOpenKey(source.key, source.provider) },
                )
                if (readiness.isReady) {
                    ConnectionTestRow(environment, source.provider)
                }
                HorizontalDivider()
            }
        }

        item {
            // The FRED sentence is required verbatim by their API terms, which
            // ask for it prominently in the application itself — a line in the
            // repository's README would not satisfy it.
            Footnote(
                "Keys are stored in this device's secure storage. They are never written " +
                    "to the app's database, never included in logs, and never sent " +
                    "anywhere except the provider they belong to.\n\nThis product uses " +
                    "the FRED® API but is not endorsed or certified by the Federal " +
                    "Reserve Bank of St. Louis.",
            )
        }

        item { SectionHeader("About") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(
                        "Research tool",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LibraTheme.colors.secondaryText,
                    )
                    Text(
                        "Not investment advice",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LibraTheme.colors.secondaryText,
                    )
                }
                Text(
                    "This app analyses and organises published information. It does not " +
                        "make recommendations, predict prices, or tell you what to buy " +
                        "or sell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LibraTheme.colors.secondaryText,
                )
                Text(
                    "For educational and informational purposes only. Nothing here is " +
                        "investment advice or a recommendation to buy or sell any " +
                        "security. Data comes from third-party providers and carries no " +
                        "warranty as to accuracy, completeness or timeliness.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LibraTheme.colors.secondaryText,
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = LibraTheme.colors.secondaryText,
    )
}

/** One credential, and whether it is set. */
data class DataSourceDescriptor(
    val key: SecretKey,
    val provider: DataProviderID,
    val purpose: String,
) {
    val id: String get() = key.raw
}

internal val dataSources: List<DataSourceDescriptor> = listOf(
    DataSourceDescriptor(
        SecretKey.FinnhubAPIKey, DataProviderID.Finnhub,
        "Live quotes, company profiles, ratings, news",
    ),
    DataSourceDescriptor(
        SecretKey.TiingoAPIKey, DataProviderID.Tiingo,
        "Daily price history — charts, volatility, momentum",
    ),
    // Both halves of the pair, as two rows — the same shape the SEC entries
    // already use. Optional: without them every range but 1D and 5D still works.
    DataSourceDescriptor(
        SecretKey.AlpacaKeyID, DataProviderID.Alpaca,
        "Optional — intraday bars for the 1D and 5D charts (IEX feed)",
    ),
    DataSourceDescriptor(
        SecretKey.AlpacaSecretKey, DataProviderID.Alpaca,
        "Optional — the secret half of the Alpaca key pair",
    ),
    DataSourceDescriptor(
        SecretKey.SecContactEmail, DataProviderID.SEC,
        "Filings, financial statements, insider transactions",
    ),
    DataSourceDescriptor(
        SecretKey.SecOrganizationName, DataProviderID.SEC,
        "Optional — the name half of the SEC User-Agent",
    ),
    DataSourceDescriptor(
        SecretKey.FredAPIKey, DataProviderID.FRED,
        "Index levels (S&P 500, Dow, Nasdaq, VIX) and macro series",
    ),
)

@Composable
private fun DataSourceRow(
    source: DataSourceDescriptor,
    readiness: SourceReadiness,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = "${source.provider.displayName}. ${source.purpose}. " +
        (readiness.message ?: "Configured.")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Swift uses `checkmark.circle.fill` / `circle.dashed`; a filled versus
        // hollow bullet carries the same two states without an icon set.
        Text(
            if (readiness.isReady) "●" else "○",
            style = MaterialTheme.typography.bodyMedium,
            color = if (readiness.isReady) {
                LibraTheme.colors.positive
            } else {
                LibraTheme.colors.secondaryText
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(source.provider.displayName, style = MaterialTheme.typography.bodyMedium)
                if (source.provider.isPrimarySource) {
                    Text(
                        "PRIMARY SOURCE",
                        style = LibraType.codeSmallEmphasis,
                        color = LibraTheme.colors.secondaryText,
                    )
                }
            }
            Text(
                source.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
            readiness.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = LibraTheme.colors.caution,
                )
            }
        }
    }
}

/**
 * Verifies a stored key by actually using it.
 *
 * Without this, a mistyped key looks identical to a working one until some
 * unrelated screen renders empty much later.
 */
@Composable
private fun ConnectionTestRow(environment: AppEnvironment, provider: DataProviderID) {
    var result by remember { mutableStateOf<ConnectionTest.Result?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.clickable(enabled = !isTesting) {
                scope.launch {
                    isTesting = true
                    try {
                        result = ConnectionTest.run(provider, environment.registry.value)
                    } finally {
                        isTesting = false
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isTesting) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
            }
            Text(
                if (isTesting) "Testing…" else "Test connection",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        result?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.labelSmall,
                color = if (it.isSuccess) {
                    LibraTheme.colors.positive
                } else {
                    LibraTheme.colors.negative
                },
            )
        }
    }
}

/**
 * Shown when secure storage itself is unusable, so the failure is attributed to
 * the build rather than to the user's key.
 */
@Composable
private fun StorageUnavailableBanner(reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LibraTheme.colors.negative.copy(alpha = 0.12f))
            .padding(horizontal = LibraSpacing.medium, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Secure storage unavailable. $reason"
            },
        horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "!",
            style = LibraType.figureEmphasis,
            color = LibraTheme.colors.negative,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Secure storage unavailable",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
        }
    }
}
