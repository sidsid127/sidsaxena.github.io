package com.sidsaxena.notes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.sidsaxena.notes.data.NotesDatabase
import com.sidsaxena.notes.data.NotesRepository

/**
 * Extends FragmentActivity (not the plain ComponentActivity Compose templates default
 * to) because the optional app-lock feature needs a FragmentActivity host to show the
 * system biometric/PIN prompt.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = NotesRepository(NotesDatabase.getInstance(applicationContext).noteDao())

        setContent {
            NotesApp(repository = repository)
        }
    }
}
