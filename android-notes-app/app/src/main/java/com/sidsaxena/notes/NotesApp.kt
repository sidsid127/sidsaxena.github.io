package com.sidsaxena.notes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sidsaxena.notes.backup.NotesBackup
import com.sidsaxena.notes.data.NotesRepository
import com.sidsaxena.notes.data.ViewModelFactory
import com.sidsaxena.notes.security.AppLockManager
import com.sidsaxena.notes.security.canUseDeviceLock
import com.sidsaxena.notes.security.findActivity
import com.sidsaxena.notes.security.requestDeviceUnlock
import com.sidsaxena.notes.ui.editor.NoteEditorScreen
import com.sidsaxena.notes.ui.editor.NoteEditorViewModel
import com.sidsaxena.notes.ui.lock.LockScreen
import com.sidsaxena.notes.ui.notes.NotesListScreen
import com.sidsaxena.notes.ui.notes.NotesListViewModel
import com.sidsaxena.notes.ui.settings.SettingsSheet
import com.sidsaxena.notes.ui.theme.NotesTheme
import kotlinx.coroutines.launch

@Composable
fun NotesApp(repository: NotesRepository) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lockManager = remember { AppLockManager(context) }
    val deviceLockAvailable = remember { canUseDeviceLock(context) }

    var lockEnabled by remember { mutableStateOf(lockManager.isLockEnabled) }
    var unlocked by remember { mutableStateOf(!lockManager.isLockEnabled) }

    // Re-lock whenever the app is backgrounded, not just on cold start, so switching
    // away and back doesn't leave critical notes exposed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, lockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && lockEnabled) {
                unlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NotesTheme {
        if (lockEnabled && !unlocked) {
            LockScreen(
                onUnlockRequested = {
                    val current = activity
                    if (current != null) {
                        requestDeviceUnlock(current, onSuccess = { unlocked = true }, onFailure = {})
                    } else {
                        unlocked = true
                    }
                }
            )
        } else {
            NotesNavHost(
                repository = repository,
                lockEnabled = lockEnabled,
                deviceLockAvailable = deviceLockAvailable,
                onLockEnabledChange = { enabled ->
                    lockEnabled = enabled
                    lockManager.isLockEnabled = enabled
                }
            )
        }
    }
}

@Composable
private fun NotesNavHost(
    repository: NotesRepository,
    lockEnabled: Boolean,
    deviceLockAvailable: Boolean,
    onLockEnabledChange: (Boolean) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    val listViewModel: NotesListViewModel = viewModel(
        factory = ViewModelFactory { NotesListViewModel(repository) }
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val notes = listViewModel.exportSnapshot()
                NotesBackup.writeToUri(context, uri, notes)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val notes = NotesBackup.readFromUri(context, uri)
                listViewModel.importNotes(notes)
            }
        }
    }

    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            NotesListScreen(
                viewModel = listViewModel,
                onOpenNote = { id -> navController.navigate("editor/${id ?: -1L}") },
                onOpenSettings = { showSettings = true }
            )
        }
        composable(
            route = "editor/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.LongType })
        ) { backStackEntry ->
            val noteIdArg = backStackEntry.arguments?.getLong("noteId") ?: -1L
            val noteId = if (noteIdArg == -1L) null else noteIdArg
            val editorViewModel: NoteEditorViewModel = viewModel(
                key = "editor-$noteIdArg",
                factory = ViewModelFactory { NoteEditorViewModel(repository, noteId) }
            )
            NoteEditorScreen(viewModel = editorViewModel, onBack = { navController.popBackStack() })
        }
    }

    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            lockEnabled = lockEnabled,
            onLockToggle = onLockEnabledChange,
            canUseDeviceLock = deviceLockAvailable,
            onExport = { exportLauncher.launch("notes-backup.json") },
            onImport = { importLauncher.launch(arrayOf("application/json")) }
        )
    }
}
