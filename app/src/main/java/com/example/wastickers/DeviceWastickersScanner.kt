package com.example.wastickers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface WastickersDataSource {
    /** Scans every reachable .wastickers archive. Runs on Dispatchers.IO. */
    suspend fun scan(): List<WastickersFile>

    /** Whether at least one persistent read grant is available. */
    fun hasAnyAccess(): Boolean

    /** Whether the scoped legacy read permission is granted (API <= 32). */
    fun hasLegacyReadPermission(): Boolean
}

/**
 * Discovers .wastickers archives from two complementary, permission-aware
 * sources:
 *
 * 1. MediaStore.Files (Downloads / Documents collection), only when the scoped
 *    READ_EXTERNAL_STORAGE permission is actually granted (API <= 32). On
 *    Android 13+ MediaStore returns only files owned by this app.
 * 2. SAF directory trees explicitly chosen by the user. The tree grant is
 *    persisted with takePersistableUriPermission and traversed safely with
 *    DocumentsContract (depth + document count limits to avoid jank).
 *
 * No MANAGE_EXTERNAL_STORAGE / all-files access is requested. A provider that
 * denies access is skipped instead of crashing the whole scan.
 */
class DeviceWastickersScanner(
    private val context: Context,
    private val treePermissionStore: TreePermissionStore
) : WastickersDataSource {

    override suspend fun scan(): List<WastickersFile> = withContext(Dispatchers.IO) {
        val found = ArrayList<WastickersFile>()
        scanMediaStore(found)

        for (treeUri in treePermissionStore.grantedTreeUris()) {
            if (!hasPersistedReadPermission(treeUri)) {
                treePermissionStore.remove(treeUri)
                continue
            }
            traverseTree(treeUri, found)
        }

        WastickersFileOps.sortNewestFirst(WastickersFileOps.dedupe(found))
    }

    override fun hasAnyAccess(): Boolean =
        treePermissionStore.hasAny() || hasLegacyReadPermission()

    override fun hasLegacyReadPermission(): Boolean {
        if (Build.VERSION.SDK_INT > 32) return false
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { grant ->
            grant.uri.toString() == uri.toString() && grant.isReadPermission
        }

    private fun scanMediaStore(out: MutableList<WastickersFile>) {
        // On API <= 32 a query over all shared files requires the scoped read
        // permission; on API 33+ MediaStore needs no permission but only exposes
        // files owned by this app.
        val mayQuery = Build.VERSION.SDK_INT >= 33 || hasLegacyReadPermission()
        if (!mayQuery) return

        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri(VOLUME_EXTERNAL_LEGACY)
            }

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%${WastickersFileOps.EXTENSION}")

            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext() && out.size < MAX_DOCUMENTS) {
                    val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        cursor.getString(nameIdx)
                    } else null
                    if (!WastickersFileOps.isWastickersName(name)) continue

                    val rowId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                    if (rowId < 0L) continue

                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        cursor.getLong(sizeIdx)
                    } else 0L
                    val modifiedSeconds = if (dateIdx >= 0 && !cursor.isNull(dateIdx)) {
                        cursor.getLong(dateIdx)
                    } else 0L

                    val uri = Uri.withAppendedPath(collection, rowId.toString()).toString()
                    out.add(
                        WastickersFile(
                            id = "media:$rowId",
                            uri = uri,
                            displayName = WastickersFileOps.sanitizeName(name),
                            sizeBytes = size,
                            lastModified = modifiedSeconds * 1000L,
                            source = WastickersSource.MEDIA_STORE
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked or provider restriction: SAF trees still work.
        } catch (_: Exception) {
            // A broken MediaStore provider must not break discovery.
        }
    }

    private fun traverseTree(treeUri: Uri, out: MutableList<WastickersFile>) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        } ?: return

        val visited = HashSet<String>()
        walk(treeUri, rootId, 0, out, visited)
    }

    private fun walk(
        treeUri: Uri,
        parentDocumentId: String,
        depth: Int,
        out: MutableList<WastickersFile>,
        visited: MutableSet<String>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_DOCUMENTS) return
        if (!visited.add(parentDocumentId)) return

        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        } catch (_: Exception) {
            return
        }

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        val children = ArrayList<ChildDoc>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
                ?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val flagsIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

                    while (cursor.moveToNext() && children.size < MAX_DOCUMENTS) {
                        val docId = if (idIdx >= 0 && !cursor.isNull(idIdx)) {
                            cursor.getString(idIdx)
                        } else null
                        if (docId.isNullOrEmpty()) continue
                        val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            cursor.getString(nameIdx)
                        } else null
                        val modified = if (dateIdx >= 0 && !cursor.isNull(dateIdx)) {
                            cursor.getLong(dateIdx)
                        } else 0L
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            cursor.getLong(sizeIdx)
                        } else 0L
                        val mime = if (mimeIdx >= 0 && !cursor.isNull(mimeIdx)) {
                            cursor.getString(mimeIdx)
                        } else ""
                        val flags = if (flagsIdx >= 0 && !cursor.isNull(flagsIdx)) {
                            cursor.getInt(flagsIdx)
                        } else 0
                        val isDirectory =
                            (flags and DocumentsContract.Document.FLAG_DIR) != 0 ||
                                mime == DocumentsContract.Document.MIME_TYPE_DIR
                        children.add(ChildDoc(docId, name, modified, size, isDirectory))
                    }
                }
        } catch (_: SecurityException) {
            return
        } catch (_: Exception) {
            return
        }

        for (child in children) {
            if (child.isDirectory) {
                walk(treeUri, child.id, depth + 1, out, visited)
            } else if (WastickersFileOps.isWastickersName(child.name)) {
                val documentUri = try {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id).toString()
                } catch (_: Exception) {
                    continue
                }
                out.add(
                    WastickersFile(
                        id = "saf:$documentUri",
                        uri = documentUri,
                        displayName = WastickersFileOps.sanitizeName(child.name),
                        sizeBytes = child.size,
                        lastModified = child.lastModified,
                        source = WastickersSource.SAF_TREE
                    )
                )
            }
        }
    }

    private data class ChildDoc(
        val id: String,
        val name: String?,
        val lastModified: Long,
        val size: Long,
        val isDirectory: Boolean
    )

    private companion object {
        const val VOLUME_EXTERNAL_LEGACY = "external"
        const val MAX_DEPTH = 8
        const val MAX_DOCUMENTS = 3000
    }
}
