package com.tylerabitbol.libra.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.models.provenance.DataProviderID
import com.tylerabitbol.libra.services.secrets.SecretKey
import com.tylerabitbol.libra.ui.LibraSpacing
import com.tylerabitbol.libra.ui.LibraTheme
import com.tylerabitbol.libra.ui.LibraType
import com.tylerabitbol.libra.ui.components.Footnote

/**
 * Entry screen for one credential.
 *
 * The field starts empty and the stored value is never loaded back into it.
 * Showing an existing key would put a secret on screen for no benefit — the
 * user already has it wherever they got it from.
 */
@Composable
fun SecretEntryScreen(
    key: SecretKey,
    provider: DataProviderID,
    environment: AppEnvironment,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // `remember(key)` rather than `rememberSaveable`: a draft credential must
    // not be written into saved instance state, which outlives the screen and
    // is not secure storage.
    var draft by remember(key) { mutableStateOf("") }
    var isRevealed by remember(key) { mutableStateOf(false) }
    var errorMessage by remember(key) { mutableStateOf<String?>(null) }

    val configured = environment.configuredKeys.collectAsStateValue()

    fun save(value: String?) {
        try {
            val trimmed = value?.trim()
            environment.setSecret(if (trimmed.isNullOrEmpty()) null else trimmed, key)
            draft = ""
            errorMessage = null
            onSaved()
        } catch (error: Exception) {
            errorMessage = error.message ?: "Could not save the value."
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(LibraSpacing.large),
        verticalArrangement = Arrangement.spacedBy(LibraSpacing.medium),
    ) {
        Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
        SectionHeader(key.displayName)

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(key.displayName) },
            singleLine = true,
            visualTransformation = if (key.isSensitive && !isRevealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (key == SecretKey.SecContactEmail) {
                    KeyboardType.Email
                } else {
                    KeyboardType.Ascii
                },
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (key.isSensitive) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show what I'm typing", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = isRevealed, onCheckedChange = { isRevealed = it })
            }
        }

        Footnote(key.helpText)

        if (key in configured) {
            HorizontalDivider()
            Text(
                "A value is already saved. Typing here replaces it.",
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.secondaryText,
            )
            environment.secrets.fingerprint(key)?.let { fingerprint ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(
                        "Stored",
                        style = MaterialTheme.typography.bodySmall,
                        color = LibraTheme.colors.secondaryText,
                    )
                    Text(fingerprint, style = LibraType.figure)
                }
                Footnote(
                    "Compare this with the key shown in your provider's dashboard. A " +
                        "different length means it was truncated or pasted incompletely.",
                )
            }
            TextButton(onClick = { save(null) }) {
                Text("Remove saved value", color = LibraTheme.colors.negative)
            }
        }

        errorMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = LibraTheme.colors.negative,
            )
        }

        Button(
            onClick = { save(draft) },
            enabled = draft.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }

        if (key == SecretKey.SecContactEmail) {
            Footnote(
                "The SEC asks every automated client to identify itself with a contact " +
                    "address. Requests to EDGAR stay disabled until this is set.",
            )
        }
    }
}
