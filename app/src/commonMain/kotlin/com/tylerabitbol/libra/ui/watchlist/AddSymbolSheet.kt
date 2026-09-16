package com.tylerabitbol.libra.ui.watchlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tylerabitbol.libra.services.providers.CompanyProfileDTO
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.viewmodels.SymbolSearchUiState

/**
 * Search-and-add sheet.
 *
 * Swift's `.searchable` has no Compose equivalent; a plain field driving the
 * same view model is the whole of it. The debounce stays in
 * `SymbolSearchViewModel`, where it protects the request budget regardless of
 * which platform's text field is calling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSymbolSheet(
    state: SymbolSearchUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (CompanyProfileDTO) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LibraSpacing.large)
                .padding(bottom = LibraSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LibraSpacing.medium),
        ) {
            Text("Add security", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                label = { Text("Ticker or company name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when {
                state.isSearching -> Row(
                    horizontalArrangement = Arrangement.spacedBy(LibraSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Text("Searching…", style = MaterialTheme.typography.bodySmall)
                }

                state.error != null -> Text(
                    state.error!!.recoverySuggestion ?: state.error!!.shortDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = LibraTheme.colors.caution,
                )

                query.isNotEmpty() && state.results.isEmpty() -> Text(
                    "No matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LibraTheme.colors.secondaryText,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(LibraSpacing.small)) {
                items(state.results, key = { it.symbol }) { profile ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(profile) }
                            .padding(vertical = LibraSpacing.tight),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            profile.symbol,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = LibraTheme.colors.secondaryText,
                        )
                    }
                }
            }
        }
    }
}
