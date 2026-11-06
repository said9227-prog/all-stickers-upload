package com.example.wastickers

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.StickerItem
import com.example.core.model.StickerPack
import com.example.data.db.AppDatabase
import com.example.data.repository.PackRepository
import com.example.data.repository.StickerRepository
import com.example.storage.TempFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class WastickersPermission {
    GRANTED,
    NEEDS_ACCESS,
    DENIED
}

data class WastickersUiState(
    val isLoading: Boolean = false,
    val files: List<WastickersFile> = emptyList(),
    val query: String = "",
    val selectedIds: Set<String> = emptySet(),
    val permission: WastickersPermission = WastickersPermission.NEEDS_ACCESS,
    val error: String? = null,
    val import: ImportProgress? = null
)

class WastickersImportViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()

    private val db = AppDatabase.getInstance(app)
    private val packRepository = PackRepository(db.stickerDao())
    private val stickerRepository = StickerRepository(db.stickerDao())
    private val tempFileManager = TempFileManager(app)

    private val history = DiscoveryHistoryStore(app)
    private val hashLog: ImportedHashLog = SharedPrefsImportedHashLog(app)
    private val treeStore = TreePermissionStore(app)
    private val scanner: WastickersDataSource = DeviceWastickersScanner(app, treeStore)

    private val _uiState = MutableStateFlow(WastickersUiState())
    val uiState: StateFlow<WastickersUiState> = _uiState.asStateFlow()

    private val _openPackEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val openPackEvents: SharedFlow<Long> = _openPackEvents.asSharedFlow()

    private val importer: WastickersImporter = WastickersImporter(
        opener = ArchiveOpener { file ->
            try {
                app.contentResolver.openInputStream(Uri.parse(file.uri))
            } catch (_: Exception) {
                null
            }
        },
        writer = PackWriter { pack -> persistPack(pack) },
        hashLog = hashLog,
        tempDirProvider = { File(app.cacheDir, "wastickers_import").apply { mkdirs() } }
    )

    private var importJob: Job? = null

    /** Persists one extracted pack using the existing Room + managed storage. */
    private suspend fun persistPack(pack: ImportedPack): Long {
        val normalized = pack.name.replace("[^\\w]".toRegex(), "_").lowercase(Locale.US)
        val shortName = "pack_${normalized.take(24)}_${System.currentTimeMillis() % 100000}"
        val packId = packRepository.createPack(
            StickerPack(
                name = pack.name,
                shortName = shortName,
                author = pack.author,
                defaultEmoji = "✨"
            )
        )

        val items = pack.stickers.mapIndexed { index, sticker ->
            val preferredName = "${sticker.displayName}.${sticker.file.extension}"
            val (stableFile, stableUri) =
                tempFileManager.saveToPackOriginals(packId, sticker.file, preferredName)
            val mime = when (sticker.type) {
                com.example.core.model.StickerType.WEBM -> "video/webm"
                com.example.core.model.StickerType.PNG,
                com.example.core.model.StickerType.JPG -> "image/png"
                else -> "image/webp"
            }
            StickerItem(
                packId = packId,
                originalUri = stableUri.toString(),
                originalName = stableFile.name,
                displayName = sticker.displayName,
                emoji = "✨",
                type = sticker.type,
                originalSize = stableFile.length(),
                width = sticker.width,
                height = sticker.height,
                durationMs = sticker.durationMs,
                sortOrder = index,
                sourceUri = "wastickers://${pack.sourceId}",
                sourceMimeType = mime,
                sourceDisplayName = sticker.displayName
            )
        }
        stickerRepository.addStickers(items)
        _openPackEvents.tryEmit(packId)
        return packId
    }

    /** Re-scans the device and merges the result with the persistent history. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val scanned = scanner.scan()
                val now = System.currentTimeMillis()
                val previous = withContext(Dispatchers.IO) { history.load() }
                val merge = withContext(Dispatchers.Default) {
                    WastickersFileOps.mergeDiscovery(previous, scanned, now)
                }
                withContext(Dispatchers.IO) { history.save(merge.records) }

                val permission = when {
                    scanner.hasAnyAccess() -> WastickersPermission.GRANTED
                    _uiState.value.permission == WastickersPermission.DENIED ->
                        WastickersPermission.DENIED
                    else -> WastickersPermission.NEEDS_ACCESS
                }
                val availableIds = merge.files.map { it.id }.toSet()
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        files = merge.files,
                        selectedIds = state.selectedIds.intersect(availableIds),
                        permission = permission,
                        error = null
                    )
                }
            } catch (e: SecurityException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        permission = WastickersPermission.DENIED,
                        error = "تم رفض صلاحية الوصول إلى الملفات"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "تعذر فحص الملفات")
                }
            }
        }
    }

    /** Persists a SAF tree grant and refreshes the list. */
    fun onTreeUriGranted(uri: Uri?) {
        if (uri == null) {
            onPermissionDenied()
            return
        }
        viewModelScope.launch {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers already hold the grant; scanning will tell us.
            }
            withContext(Dispatchers.IO) { treeStore.add(uri) }
            refresh()
        }
    }

    fun onLegacyPermissionResult(granted: Boolean) {
        if (!granted) {
            onPermissionDenied()
        } else {
            refresh()
        }
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(
                permission = WastickersPermission.DENIED,
                error = "تم رفض الصلاحية — يمكنك اختيار مجلد يدوياً"
            )
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun toggleSelection(id: String) {
        _uiState.update {
            it.copy(selectedIds = WastickersFileOps.toggleSelection(it.selectedIds, id))
        }
    }

    /** Selects all currently visible (filtered) files. */
    fun selectAll(ids: Collection<String>) {
        _uiState.update {
            it.copy(selectedIds = WastickersFileOps.selectAll(it.selectedIds, ids))
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    /** Clears the NEW badge but keeps the discovery date in history. */
    fun markNewAsSeen() {
        viewModelScope.launch {
            val newIds = _uiState.value.files.filter { it.isNew }.map { it.id }
            withContext(Dispatchers.IO) { history.markSeen(newIds) }
            _uiState.update { state ->
                state.copy(files = state.files.map { if (it.isNew) it.copy(isNew = false) else it })
            }
        }
    }

    /** Imports every selected archive in one bounded, cancellable operation. */
    fun importSelected() {
        val selected = _uiState.value.files.filter { it.id in _uiState.value.selectedIds }
        if (selected.isEmpty()) return
        importJob?.cancel()
        importJob = importer.import(selected)
            .onEach { progress ->
                _uiState.update { state ->
                    val files = state.files.map { file ->
                        progress.perFile[file.id]?.let { file.copy(status = it.status) } ?: file
                    }
                    state.copy(files = files, import = progress)
                }
            }
            .onCompletion { cause ->
                if (cause is CancellationException) {
                    _uiState.update { state ->
                        state.copy(
                            import = state.import?.copy(isRunning = false, isCancelled = true)
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _uiState.update { state ->
            state.copy(import = state.import?.copy(isRunning = false, isCancelled = true))
        }
    }

    override fun onCleared() {
        super.onCleared()
        importJob?.cancel()
    }
}
