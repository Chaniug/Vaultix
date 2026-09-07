package com.bastion.app.notes.ui.model

import com.bastion.app.bitwarden.sync.SyncStatus
import java.util.Date

data class NoteListItemUiModel(
    val id: Long,
    val title: String,
    val rawContent: String,
    val isMarkdown: Boolean,
    val inlineImageIds: List<String>,
    val previewText: String,
    val tags: List<String>,
    val updatedAt: Date,
    val hasImageAttachment: Boolean,
    val syncStatus: SyncStatus?
)
