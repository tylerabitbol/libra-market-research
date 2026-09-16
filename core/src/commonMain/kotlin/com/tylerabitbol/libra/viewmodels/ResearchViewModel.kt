package com.tylerabitbol.libra.viewmodels

import com.tylerabitbol.libra.models.core.EvidenceCategory
import com.tylerabitbol.libra.persistence.SecurityEvent
import com.tylerabitbol.libra.persistence.SnapshotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The cross-security feed of everything the detectors have found.
 *
 * Reads only from the store — it issues no network requests. Events are a
 * by-product of visiting securities, which keeps this screen free in a
 * rate-limited app and means the feed reflects what the user actually follows
 * rather than a universe they never asked about.
 */
class ResearchViewModel {

    private val _state = MutableStateFlow(ResearchUiState())
    val state: StateFlow<ResearchUiState> = _state.asStateFlow()

    enum class Sort(val displayName: String) {
        MostRecent("Most recent"),
        MostUnusual("Most unusual");

        val id: String get() = displayName
    }

    fun setSort(sort: Sort) {
        _state.update { it.copy(sort = sort) }
    }

    fun setKindFilter(category: EvidenceCategory?) {
        _state.update { it.copy(kindFilter = category) }
    }

    suspend fun load(snapshots: SnapshotStore?) {
        if (snapshots == null) {
            _state.update { it.copy(events = emptyList()) }
            return
        }
        _state.update { it.copy(isLoading = true) }
        try {
            val events = snapshots.recentEvents()
            _state.update { it.copy(events = events, loadFailure = null) }
        } catch (error: Exception) {
            _state.update { it.copy(loadFailure = error.message ?: "Could not read the store.") }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }
}

data class ResearchUiState(
    val events: List<SecurityEvent> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailure: String? = null,
    val sort: ResearchViewModel.Sort = ResearchViewModel.Sort.MostRecent,
    val kindFilter: EvidenceCategory? = null,
)

val ResearchUiState.visibleEvents: List<SecurityEvent>
    get() {
        val filtered = kindFilter?.let { category ->
            events.filter { it.event.kind.evidenceCategory == category }
        } ?: events

        return when (sort) {
            ResearchViewModel.Sort.MostRecent ->
                filtered.sortedByDescending { it.event.occurredAt }
            ResearchViewModel.Sort.MostUnusual ->
                // Ties broken by recency so the order is stable rather than
                // arbitrary — several events can share an unusualness of 1.0.
                filtered.sortedWith(
                    compareByDescending<SecurityEvent> { it.event.unusualness }
                        .thenByDescending { it.event.occurredAt },
                )
        }
    }

/** Categories actually present, so the filter never offers an empty bucket. */
val ResearchUiState.availableCategories: List<EvidenceCategory>
    get() {
        val present = events.map { it.event.kind.evidenceCategory }.toSet()
        return EvidenceCategory.entries.filter { it in present }
    }
