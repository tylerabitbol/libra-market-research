package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.calculations.SavedScreens
import com.tylerabitbol.libra.calculations.Screen
import com.tylerabitbol.libra.calculations.ScreenRule
import com.tylerabitbol.libra.calculations.ScreenSubject
import com.tylerabitbol.libra.persistence.SnapshotStore
import com.tylerabitbol.libra.support.InMemoryPreferenceStore
import com.tylerabitbol.libra.support.PreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Backs the screener (Section 14).
 *
 * Reads the store and nothing else. No rule triggers a fetch, so running a
 * screen costs nothing against any provider's budget — which is what makes it
 * usable at all on a free tier.
 *
 * The preference store is injected rather than reached for statically, which
 * is the same substitution `SavedScreens` made in Phase 4: a test must not
 * write into the real user defaults.
 */
class ScreenerViewModel(
    private val preferences: PreferenceStore = InMemoryPreferenceStore(),
) {
    private val _state = MutableStateFlow(
        ScreenerUiState(savedScreens = SavedScreens.load(preferences)),
    )
    val state: StateFlow<ScreenerUiState> = _state.asStateFlow()

    suspend fun load(snapshots: SnapshotStore?) {
        if (snapshots == null) {
            _state.update { it.copy(subjects = emptyList()) }
            return
        }
        _state.update { it.copy(isLoading = true) }
        try {
            val subjects = snapshots.screenSubjects()
            _state.update { it.copy(subjects = subjects, loadFailure = null) }
        } catch (error: Exception) {
            _state.update { it.copy(loadFailure = error.message ?: "Could not read the store.") }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun setScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    fun addRule() {
        _state.update { it.copy(screen = it.screen.copy(rules = it.screen.rules + ScreenRule())) }
    }

    fun removeRules(offsets: Set<Int>) {
        _state.update { current ->
            val kept = current.screen.rules.filterIndexed { index, _ -> index !in offsets }
            current.copy(screen = current.screen.copy(rules = kept))
        }
    }

    fun saveCurrentScreen() {
        _state.update { current ->
            if (current.screen.rules.isEmpty()) return@update current
            val named = if (current.screen.name.trim().isEmpty()) {
                current.screen.copy(name = "Screen ${current.savedScreens.size + 1}")
            } else {
                current.screen
            }
            val saved = current.savedScreens.filterNot { it.id == named.id } + named
            SavedScreens.save(saved, preferences)
            current.copy(screen = named, savedScreens = saved)
        }
    }

    fun apply(saved: Screen) {
        _state.update { it.copy(screen = saved) }
    }

    fun delete(saved: Screen) {
        _state.update { current ->
            val remaining = current.savedScreens.filterNot { it.id == saved.id }
            SavedScreens.save(remaining, preferences)
            current.copy(savedScreens = remaining)
        }
    }

    fun newScreen() {
        _state.update { it.copy(screen = Screen()) }
    }
}

data class ScreenerUiState(
    val subjects: List<ScreenSubject> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailure: String? = null,
    val screen: Screen = Screen(),
    val savedScreens: List<Screen> = emptyList(),
)

val ScreenerUiState.results: List<ScreenSubject> get() = screen.run(subjects)

/**
 * How many held securities a rule could not be tested against.
 *
 * Surfaced rather than hidden: a screen quietly dropping the securities it
 * lacked figures for reports a smaller universe than the user thinks they
 * searched.
 */
val ScreenerUiState.untestableCount: Int get() = screen.untestable(subjects)
