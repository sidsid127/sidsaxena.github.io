package com.sidsaxena.notes.backup

import android.content.Context
import android.net.Uri
import com.sidsaxena.notes.data.Note
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manual, local-only backup format (JSON written to a file the user picks with the
 * system file picker). Nothing here touches the network -- it's just a plain-text
 * export/import so notes can be backed up or moved between devices by hand.
 */
object NotesBackup {

    fun toJson(notes: List<Note>): String {
        val array = JSONArray()
        for (note in notes) {
            val obj = JSONObject()
            obj.put("id", note.id)
            obj.put("title", note.title)
            obj.put("body", note.body)
            obj.put("createdAt", note.createdAt)
            obj.put("updatedAt", note.updatedAt)
            obj.put("isPinned", note.isPinned)
            array.put(obj)
        }
        val root = JSONObject()
        root.put("version", 1)
        root.put("notes", array)
        return root.toString(2)
    }

    fun fromJson(json: String): List<Note> {
        val root = JSONObject(json)
        val array = root.optJSONArray("notes") ?: JSONArray()
        val notes = mutableListOf<Note>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            notes.add(
                Note(
                    id = 0, // let Room assign fresh ids on import
                    title = obj.optString("title"),
                    body = obj.optString("body"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    isPinned = obj.optBoolean("isPinned", false)
                )
            )
        }
        return notes
    }

    fun writeToUri(context: Context, uri: Uri, notes: List<Note>) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(toJson(notes).toByteArray(Charsets.UTF_8))
        }
    }

    fun readFromUri(context: Context, uri: Uri): List<Note> {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: return emptyList()
        return fromJson(text)
    }
}
