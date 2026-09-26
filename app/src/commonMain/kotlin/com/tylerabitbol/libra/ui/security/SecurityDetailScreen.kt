package com.tylerabitbol.libra.ui.security

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.calculations.ChangeWindow
import com.tylerabitbol.libra.models.core.ChartRange
import com.tylerabitbol.libra.models.core.EventKind
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.components.libraCard
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.components.PinnedFreshnessLabel
import com.tylerabitbol.libra.ui.components.ResearchProfileCard
import com.tylerabitbol.libra.ui.components.SampleDataBanner
import com.tylerabitbol.libra.viewmodels.SecurityDetailUiState
import com.tylerabitbol.libra.viewmodels.freshness
import com.tylerabitbol.libra.viewmodels.researchProfile

/**
 * The research page for one security (Section 5).
 *
 * Ordered by what answers "what changed and how unusual is it" fastest:
 * price context first, then valuation *with its history* — which is the part a
 * normal stock app doesn't show — then fundamentals, then the filings that
 * back them.
 */
@Composable
fun SecurityDetailScreen(
    state: SecurityDetailUiState,
    isUsingSampleData: Boolean,
    onSelectRange: (ChartRange) -> Unit,
    onSetChangeWindow: (ChangeWindow) -> Unit,
    onSetKindFilter: (Set<EventKind>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var heroHeight by remember { mutableIntStateOf(0) }
    Box(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(LibraSpacing.large)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            if (isUsingSampleData) SampleDataBanner()

            Box(Modifier.onSizeChanged { heroHeight = it.height }) { Overview(state) }
            ChangesSection(state, onSetChangeWindow, onSetKindFilter)
            ChartSection(state, onSelectRange)
            RelativeSection(state)
            ResearchProfileSection(state)
            ValuationSection(state)
            FundamentalsSection(state)
            InsiderSection(state)
            FilingsSection(state)
        }

        // How old the page is qualifies every figure on it, so it must not be
        // something you scroll past. The hero states it inline; once the hero
        // has scrolled away, the same stamp is pinned in its place, rather
        // than floating over the price and chart from the start.
        AnimatedVisibility(
            visible = scroll.value > heroHeight,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        ) {
            PinnedFreshnessLabel(freshness = state.freshness)
        }
    }
}

// MARK: - Research profile

/** Sections 12 and 13, as components rather than a score. */
@Composable
private fun ResearchProfileSection(state: SecurityDetailUiState) {
    val profile = state.researchProfile
    if (profile.measured.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Research profile", style = MaterialTheme.typography.titleMedium)
        Text(
            "Each dimension measured against this company's own history, and " +
                "grouped by which way it points.",
            style = MaterialTheme.typography.labelSmall,
            color = LibraTheme.colors.secondaryText,
        )
        ResearchProfileCard(profile)
    }
}

/** The grouped-background card every section on this page sits in. */
@Composable
internal fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .libraCard(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
