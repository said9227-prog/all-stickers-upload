package com.example.wastickers

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import java.util.Locale

/**
 * Professional WASTICKERS browser: discovery history with persistent NEW
 * badges, live search, multi-selection and a bounded, cancellable import with
 * per-file and overall progress. Material 3, dark-mode aware and RTL/LTR aware.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WastickersImportScreen(
    onNavigateBack: () -> Unit,
    onOpenPack: (Long) -> Unit,
    viewModel: WastickersImportViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val isRtl = Locale.getDefault().language == "ar"

    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onTreeUriGranted(uri) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onLegacyPermissionResult(granted) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    LaunchedEffect(Unit) {
        viewModel.openPackEvents.collect { packId -> onOpenPack(packId) }
    }

    val visible = remember(state.files, state.query) {
        WastickersFileOps.filterByQuery(state.files, state.query)
    }
    val hasNew = remember(state.files) { state.files.any { it.isNew } }
    val importProgress = state.import
    val importRunning = importProgress?.isRunning == true

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.wst_title),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.wst_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.wst_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refresh() }, enabled = !importRunning) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.wst_refresh)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                ImportBottomBar(
                    state = state,
                    onImport = { viewModel.importSelected() },
                    onCancel = { viewModel.cancelImport() }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (state.permission != WastickersPermission.GRANTED) {
                    PermissionCard(
                        denied = state.permission == WastickersPermission.DENIED,
                        onChooseFolder = { treeLauncher.launch(null) },
                        onGrantRead = {
                            if (Build.VERSION.SDK_INT <= 32) {
                                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            } else {
                                treeLauncher.launch(null)
                            }
                        }
                    )
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.wst_clear_search)
                                )
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.wst_search_hint)) },
                    shape = RoundedCornerShape(14.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.wst_files_count, state.files.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasNew) {
                        TextButton(onClick = { viewModel.markNewAsSeen() }) {
                            Text(
                                stringResource(R.string.wst_mark_seen),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            R.string.wst_selected_format,
                            state.selectedIds.size,
                            state.files.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.selectAll(visible.map { it.id }) }) {
                        Icon(
                            Icons.Default.SelectAll,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.wst_select_all))
                    }
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text(stringResource(R.string.wst_clear_selection))
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        state.isLoading && state.files.isEmpty() -> CenteredMessage(
                            showProgress = true,
                            title = stringResource(R.string.wst_loading)
                        )
                        state.error != null && state.files.isEmpty() -> CenteredMessage(
                            showProgress = false,
                            title = state.error ?: stringResource(R.string.wst_error),
                            actionLabel = stringResource(R.string.wst_retry),
                            onAction = { viewModel.refresh() }
                        )
                        visible.isEmpty() && state.query.isNotBlank() -> CenteredMessage(
                            showProgress = false,
                            title = stringResource(R.string.wst_no_results),
                            subtitle = stringResource(R.string.wst_no_results_hint)
                        )
                        visible.isEmpty() -> CenteredMessage(
                            showProgress = false,
                            title = stringResource(R.string.wst_empty),
                            subtitle = stringResource(R.string.wst_empty_desc),
                            actionLabel = stringResource(R.string.wst_refresh),
                            onAction = { viewModel.refresh() }
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(items = visible, key = { it.id }) { file ->
                                WastickersFileCard(
                                    file = file,
                                    selected = file.id in state.selectedIds,
                                    progress = importProgress?.perFile?.get(file.id),
                                    onToggle = { viewModel.toggleSelection(file.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    denied: Boolean,
    onChooseFolder: () -> Unit,
    onGrantRead: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.wst_permission_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (denied) {
                    stringResource(R.string.wst_permission_denied)
                } else {
                    stringResource(R.string.wst_permission_reason)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onChooseFolder) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.wst_grant_folder))
                }
                if (Build.VERSION.SDK_INT <= 32) {
                    OutlinedButton(onClick = onGrantRead) {
                        Text(stringResource(R.string.wst_grant_read))
                    }
                }
            }
        }
    }
}

@Composable
private fun WastickersFileCard(
    file: WastickersFile,
    selected: Boolean,
    progress: FileImportProgress?,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = file.displayName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (file.isNew) {
                            Spacer(modifier = Modifier.width(6.dp))
                            NewBadge()
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    MetaLine(
                        icon = Icons.Default.Schedule,
                        text = stringResource(
                            R.string.wst_modified,
                            WastickersFileOps.formatDate(file.lastModified)
                        )
                    )
                    MetaLine(
                        icon = Icons.Default.Info,
                        text = stringResource(
                            R.string.wst_size,
                            WastickersFileOps.formatSize(file.sizeBytes)
                        )
                    )
                    if (file.firstSeenAt > 0L) {
                        MetaLine(
                            icon = Icons.Default.Download,
                            text = stringResource(
                                R.string.wst_discovered_at,
                                WastickersFileOps.formatDate(file.firstSeenAt)
                            )
                        )
                    }
                }
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = file.status)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (file.source) {
                        WastickersSource.MEDIA_STORE -> "MediaStore"
                        WastickersSource.SAF_TREE -> "SAF"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (progress != null && progress.status == WastickersStatus.IMPORTING) {
                Spacer(modifier = Modifier.height(8.dp))
                ProgressBar(
                    progress = progress.progress,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.wst_per_file_progress,
                        (progress.progress * 100).toInt()
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (progress?.message != null &&
                progress.status in setOf(
                    WastickersStatus.FAILED,
                    WastickersStatus.CORRUPT,
                    WastickersStatus.DUPLICATE
                )
            ) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = progress.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NewBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.wst_new_badge),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusChip(status: WastickersStatus) {
    val label = when (status) {
        WastickersStatus.READY -> stringResource(R.string.wst_status_ready)
        WastickersStatus.IMPORTING -> stringResource(R.string.wst_status_importing)
        WastickersStatus.IMPORTED -> stringResource(R.string.wst_status_imported)
        WastickersStatus.DUPLICATE -> stringResource(R.string.wst_status_duplicate)
        WastickersStatus.CORRUPT -> stringResource(R.string.wst_status_corrupt)
        WastickersStatus.FAILED -> stringResource(R.string.wst_status_failed)
    }
    val color = when (status) {
        WastickersStatus.READY -> MaterialTheme.colorScheme.secondaryContainer
        WastickersStatus.IMPORTING -> MaterialTheme.colorScheme.primaryContainer
        WastickersStatus.IMPORTED -> Color(0xFF1B7F52)
        WastickersStatus.DUPLICATE -> MaterialTheme.colorScheme.surfaceVariant
        WastickersStatus.CORRUPT, WastickersStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }
    val textColor = when (status) {
        WastickersStatus.CORRUPT, WastickersStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

@Composable
private fun ProgressBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(color)
        )
    }
}

@Composable
private fun CenteredMessage(
    showProgress: Boolean,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showProgress) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(52.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ImportBottomBar(
    state: WastickersUiState,
    onImport: () -> Unit,
    onCancel: () -> Unit
) {
    val progress = state.import
    val running = progress?.isRunning == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.wst_overall_progress,
                        (progress.overall * 100).toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${progress.completed}/${progress.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            ProgressBar(
                progress = progress.overall,
                color = if (progress.isCancelled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (!running) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (progress.isCancelled) {
                        stringResource(R.string.wst_import_cancelled)
                    } else {
                        stringResource(
                            R.string.wst_import_done,
                            progress.succeeded,
                            progress.failed,
                            progress.skipped
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (progress.isCancelled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onImport,
                enabled = state.selectedIds.isNotEmpty() && !running,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.wst_import_selected) +
                        " (${state.selectedIds.size})",
                    fontWeight = FontWeight.Bold
                )
            }
            if (running) {
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.wst_cancel_import))
                }
            }
        }
    }
}
