package com.sidsaxena.notes.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Small manual factory so the app doesn't need a DI framework. */
class ViewModelFactory(private val builder: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = builder() as T
}
