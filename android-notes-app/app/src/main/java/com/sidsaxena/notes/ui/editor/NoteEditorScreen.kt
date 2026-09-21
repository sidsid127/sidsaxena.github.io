package com.sidsaxena.notes.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val UNCHECKED = "☐ "
private const val CHECKED = "☑ "

private val TitleTransformation = VisualTransformation { text ->
    val newlineIndex = text.text.indexOf('\n').let { if (it == -1) text.text.length else it }
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)) {
            append(text.text.substring(0, newlineIndex))
        }
        append(text.text.substring(newlineIndex))
    }
    TransformedText(annotated, OffsetMapping.Identity)
}

/**
 * Turns the line the cursor is on into a checklist item, or flips its checkbox if it
 * already is one. Operating on the line under the cursor (rather than trying to catch a
 * tap on a specific glyph inside the text field) keeps this reliable.
 */
private fun applyChecklistToggle(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
    val lineEndRaw = text.indexOf('\n', lineStart)
    val lineEnd = if (lineEndRaw == -1) text.length else lineEndRaw
    val line = text.substring(lineStart, lineEnd)

    val newLine: String
    val cursorShift: Int
    when {
        line.startsWith(UNCHECKED) -> {
            newLine = CHECKED + line.substring(UNCHECKED.length)
            cursorShift = 0
        }
        line.startsWith(CHECKED) -> {
            newLine = UNCHECKED + line.substring(CHECKED.length)
            cursorShift = 0
        }
        else -> {
            newLine = UNCHECKED + line
            cursorShift = UNCHECKED.length
        }
    }

    val newText = text.substring(0, lineStart) + newLine + text.substring(lineEnd)
    val newCursor = (cursor + cursorShift).coerceIn(0, newText.length)
    return TextFieldValue(newText, selection = TextRange(newCursor))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    viewModel: NoteEditorViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf<TextFieldValue?>(null) }

    LaunchedEffect(state.loaded) {
        if (state.loaded && fieldValue == null) {
            fieldValue = TextFieldValue(state.text, selection = TextRange(state.text.length))
        }
    }

    val latestViewModel = rememberUpdatedState(viewModel)
    DisposableEffect(Unit) {
        onDispose { latestViewModel.value.saveNow() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    Row(
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(start = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Notes", color = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        fieldValue?.let { fieldValue = applyChecklistToggle(it) }
                    }) {
                        Icon(Icons.Filled.Checklist, contentDescription = "Checklist item")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(if (state.isPinned) "Unpin" else "Pin") },
                                leadingIcon = {
                                    Icon(
                                        if (state.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.togglePin()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.deleteAndClear(onBack)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        val current = fieldValue ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BasicTextField(
                value = current,
                onValueChange = { newValue ->
                    fieldValue = newValue
                    viewModel.onTextChange(newValue.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = TitleTransformation,
                decorationBox = { innerTextField ->
                    if (current.text.isEmpty()) {
                        Text(
                            "New Note",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}
