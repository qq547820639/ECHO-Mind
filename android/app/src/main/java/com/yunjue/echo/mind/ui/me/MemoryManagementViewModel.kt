package com.yunjue.echo.mind.ui.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yunjue.echo.mind.AppContainer
import com.yunjue.echo.mind.me.MemoryManagementEvent
import com.yunjue.echo.mind.me.MemoryManagementUiState
import com.yunjue.echo.mind.memory.MemoryType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ERA 13.1 §36 — MemoryManagementViewModel（What ECHO Knows）。
 *
 * confirm / edit / forget / filter 全部在此；pin 属 ERA 15.5 Memory Maturity
 * （当前 EchoMemory 无 pinned 字段，不预置死事件）。
 */
class MemoryManagementViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {

    private val _filter = MutableStateFlow<MemoryType?>(null)

    val uiState: StateFlow<MemoryManagementUiState> = combine(
        container.memoryRepository.observeMemories(),
        _filter,
    ) { memories, filter ->
        MemoryManagementUiState(memories = memories, filter = filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MemoryManagementUiState(),
    )

    /** Section 唯一交互入口。 */
    fun onEvent(event: MemoryManagementEvent) {
        when (event) {
            is MemoryManagementEvent.SetFilter -> _filter.value = event.type
            is MemoryManagementEvent.Confirm ->
                viewModelScope.launch { container.memoryRepository.confirm(event.id) }
            is MemoryManagementEvent.Edit ->
                viewModelScope.launch { container.memoryRepository.edit(event.id, event.content) }
            is MemoryManagementEvent.Forget ->
                viewModelScope.launch { container.memoryRepository.forget(event.id) }
            is MemoryManagementEvent.AddContextException ->
                viewModelScope.launch {
                    container.memoryRepository.recordContextException(
                        kind = event.kind,
                        note = event.note,
                        date = java.time.LocalDate.now().toString(),
                    )
                }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MemoryManagementViewModel(container.applicationContext as Application, container)
            }
        }
    }
}
