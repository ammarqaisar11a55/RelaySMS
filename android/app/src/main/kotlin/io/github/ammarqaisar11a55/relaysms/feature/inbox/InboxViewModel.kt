package io.github.ammarqaisar11a55.relaysms.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ammarqaisar11a55.relaysms.AppContainer
import io.github.ammarqaisar11a55.relaysms.inbox.InboxFilter
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage
import io.github.ammarqaisar11a55.relaysms.inbox.InboxQuery
import io.github.ammarqaisar11a55.relaysms.inbox.InboxSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface InboxUiState {
    data object Loading : InboxUiState
    data object StorageError : InboxUiState
    data class Ready(
        val visible: List<InboxMessage>,
        val total: Int,
        val unread: Int,
        val query: String,
        val filter: InboxFilter,
    ) : InboxUiState
}

class InboxViewModel(private val container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(InboxFilter.ALL)
    val refreshing = MutableStateFlow(false)

    val queryText: StateFlow<String> = query

    @OptIn(FlowPreview::class)
    val state: StateFlow<InboxUiState> = combine(
        container.inboxRepository.messages,
        // Typing should not re-filter a large inbox on every keystroke.
        query.debounce { if (it.isEmpty()) 0 else 150 }.distinctUntilChanged(),
        filter,
        container.preferences.settings.map { it.newestFirst }.distinctUntilChanged(),
    ) { all, q, f, newestFirst ->
        InboxUiState.Ready(
            visible = InboxQuery.apply(all, q, f, if (newestFirst) InboxSort.NEWEST_FIRST else InboxSort.OLDEST_FIRST),
            total = all.size,
            unread = all.count { !it.isRead },
            query = q,
            filter = f,
        ) as InboxUiState
    }
        .flowOn(Dispatchers.Default)
        .catch { emit(InboxUiState.StorageError) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState.Loading)

    fun setQuery(text: String) {
        query.value = text.take(100)
    }

    fun setFilter(value: InboxFilter) {
        filter.value = value
    }

    fun toggleSort() = viewModelScope.launch {
        container.preferences.setNewestFirst(!container.preferences.current().newestFirst)
    }

    fun markAllRead() = viewModelScope.launch { container.inboxRepository.markAllRead() }

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        container.relaySync.syncOnce()
        container.linkMonitor.refresh()
        refreshing.value = false
    }
}
