package com.sidsaxena.notes.data

import kotlinx.coroutines.flow.Flow

class NotesRepository(private val dao: NoteDao) {

    fun observeNotes(): Flow<List<Note>> = dao.observeAll()

    suspend fun getNote(id: Long): Note? = dao.getById(id)

    suspend fun save(note: Note): Long = dao.upsert(note)

    suspend fun delete(note: Note) = dao.delete(note)

    suspend fun getAllOnce(): List<Note> = dao.getAllOnce()

    suspend fun replaceAll(notes: List<Note>) {
        dao.deleteAll()
        dao.insertAll(notes)
    }
}
