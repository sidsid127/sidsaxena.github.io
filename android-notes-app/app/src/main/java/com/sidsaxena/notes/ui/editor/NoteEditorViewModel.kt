package com.sidsaxena.notes.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sidsaxena.notes.data.Note
import com.sidsaxena.notes.data.NotesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteEditorState(
    val id: Long = 0L,
    val text: String = "",
    val isPinned: Boolean = false,
    val loaded: Boolean = false
)

/** Splits "First line\nRest of the note" into a Room-friendly title/body pair. */
private fun splitTitleAndBody(text: String): Pair<String, String> {
    val newlineIndex = text.indexOf('\n')
    return if (newlineIndex == -1) {
        text.trim() to ""
    } else {
        text.substring(0, newlineIndex).trim() to text.substring(newlineIndex + 1)
    }
}

class NoteEditorViewModel(
    private val repository: NotesRepository,
    private val noteId: Long?
) : ViewModel() {

    private val _state = MutableStateFlow(NoteEditorState())
    val state: StateFlow<NoteEditorState> = _state.asStateFlow()

    private var createdAt: Long = System.currentTimeMillis()
    private var saveJob: Job? = null
    private var isDeleted = false

    init {
        viewModelScope.launch {
            if (noteId != null) {
                val existing = repository.getNote(noteId)
                if (existing != null) {
                    createdAt = existing.createdAt
                    val combined = if (existing.body.isEmpty()) existing.title else "${existing.title}\n${existing.body}"
                    _state.value = NoteEditorState(
                        id = existing.id,
                        text = combined,
                        isPinned = existing.isPinned,
                        loaded = true
                    )
                    return@launch
                }
            }
            _state.value = _state.value.copy(loaded = true)
        }
    }

    fun onTextChange(newText: String) {
        _state.update { it.copy(text = newText) }
        scheduleSave()
    }

    fun togglePin() {
        _state.update { it.copy(isPinned = !it.isPinned) }
        scheduleSave(debounce = false)
    }

    private fun scheduleSave(debounce: Boolean = true) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (debounce) delay(400)
            persist()
        }
    }

    /** Call when leaving the screen to make sure the latest keystrokes aren't lost. */
    fun saveNow() {
        if (isDeleted) return
        saveJob?.cancel()
        viewModelScope.launch { persist() }
    }

    private suspend fun persist() {
        if (isDeleted) return
        val current = _state.value
        val (title, body) = splitTitleAndBody(current.text)
        if (title.isEmpty() && body.isEmpty()) return
        val now = System.currentTimeMillis()
        val saved = repository.save(
            Note(
                id = current.id,
                title = title,
                body = body,
                createdAt = createdAt,
                updatedAt = now,
                isPinned = current.isPinned
            )
        )
        if (current.id == 0L) {
            _state.update { it.copy(id = saved) }
        }
    }

    fun deleteAndClear(onDeleted: () -> Unit) {
        viewModelScope.launch {
            saveJob?.cancel()
            isDeleted = true
            val current = _state.value
            if (current.id != 0L) {
                repository.getNote(current.id)?.let { repository.delete(it) }
            }
            onDeleted()
        }
    }
}
