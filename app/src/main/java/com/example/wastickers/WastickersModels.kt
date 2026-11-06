package com.example.wastickers

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a .wastickers file was discovered. */
enum class WastickersSource {
    MEDIA_STORE,
    SAF_TREE
}

/** Lifecycle/status of a discovered archive. */
enum class WastickersStatus {
    READY,
    IMPORTING,
    IMPORTED,
    DUPLICATE,
    CORRUPT,
    FAILED
}

/**
 * A single .wastickers archive that can be reached on the device.
 *
 * [id] is stable across scans (a content Uri string, prefixed by its source).
 * [firstSeenAt] and [isNew] come from the persistent discovery history, so the
 * NEW badge survives an app restart and old files are never flagged as new again.
 */
data class WastickersFile(
    val id: String,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val source: WastickersSource,
    val firstSeenAt: Long = 0L,
    val isNew: Boolean = false,
    val status: WastickersStatus = WastickersStatus.READY
)

/** Persisted discovery record for one archive. */
data class DiscoveryRecord(
    val id: String,
    val firstSeenAt: Long,
    val isNew: Boolean,
    val lastSeenAt: Long
)

/** Result of merging a fresh scan with the persisted discovery history. */
data class DiscoveryMerge(
    val files: List<WastickersFile>,
    val records: Map<String, DiscoveryRecord>,
    val newIds: Set<String>
)

/**
 * Pure, JVM-testable operations: discovery diffing, de-duplication, sorting,
 * searching, selection and formatting. No Android classes are used here.
 */
object WastickersFileOps {

    const val EXTENSION = ".wastickers"

    fun isWastickersName(name: String?): Boolean {
        if (name == null) return false
        return name.trim().endsWith(EXTENSION, ignoreCase = true)
    }

    fun sanitizeName(name: String?): String {
        if (name.isNullOrBlank()) return "sticker$EXTENSION"
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        return leaf.ifBlank { "sticker$EXTENSION" }
    }

    /**
     * Removes duplicates by stable id and by content fingerprint. The same
     * physical file is often returned by both MediaStore and a SAF tree, so a
     * fingerprint (name + size + modification time) is used to collapse those.
     */
    fun dedupe(files: List<WastickersFile>): List<WastickersFile> {
        val seenIds = HashSet<String>(files.size)
        val seenFingerprints = HashSet<String>(files.size)
        val result = ArrayList<WastickersFile>(files.size)
        for (file in files) {
            val fingerprint =
                "${file.displayName.lowercase(Locale.ROOT)}|${file.sizeBytes}|${file.lastModified}"
            if (seenIds.contains(file.id) || seenFingerprints.contains(fingerprint)) continue
            seenIds.add(file.id)
            seenFingerprints.add(fingerprint)
            result.add(file)
        }
        return result
    }

    fun sortNewestFirst(files: List<WastickersFile>): List<WastickersFile> =
        files.sortedWith(
            compareByDescending<WastickersFile> { it.lastModified }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )

    /** Live, case-insensitive search by file name. */
    fun filterByQuery(files: List<WastickersFile>, query: String): List<WastickersFile> {
        val needle = query.trim()
        if (needle.isEmpty()) return files
        return files.filter { it.displayName.contains(needle, ignoreCase = true) }
    }

    /**
     * Merges a scan into the persistent history.
     *
     * - A file that has never been seen gets firstSeenAt = now, isNew = true.
     * - An already known file keeps its original firstSeenAt and isNew flag.
     * - Records of files that disappeared are kept on purpose, so a returning
     *   file is never reported as new again.
     */
    fun mergeDiscovery(
        previous: Map<String, DiscoveryRecord>,
        scanned: List<WastickersFile>,
        now: Long
    ): DiscoveryMerge {
        val merged = LinkedHashMap<String, DiscoveryRecord>(previous)
        val newIds = LinkedHashSet<String>()
        val files = ArrayList<WastickersFile>(scanned.size)

        for (file in scanned) {
            val record = previous[file.id]
            if (record == null) {
                merged[file.id] = DiscoveryRecord(
                    id = file.id,
                    firstSeenAt = now,
                    isNew = true,
                    lastSeenAt = now
                )
                newIds.add(file.id)
                files.add(file.copy(firstSeenAt = now, isNew = true))
            } else {
                merged[file.id] = record.copy(lastSeenAt = now)
                files.add(
                    file.copy(
                        firstSeenAt = record.firstSeenAt,
                        isNew = record.isNew
                    )
                )
            }
        }
        return DiscoveryMerge(files = files, records = merged, newIds = newIds)
    }

    fun markSeen(
        records: Map<String, DiscoveryRecord>,
        ids: Collection<String>
    ): Map<String, DiscoveryRecord> {
        if (ids.isEmpty()) return records
        val target = ids.toHashSet()
        val updated = LinkedHashMap<String, DiscoveryRecord>(records)
        for ((id, record) in records) {
            if (target.contains(id) && record.isNew) {
                updated[id] = record.copy(isNew = false)
            }
        }
        return updated
    }

    fun toggleSelection(current: Set<String>, id: String): Set<String> {
        val next = current.toMutableSet()
        if (!next.add(id)) next.remove(id)
        return next
    }

    fun selectAll(current: Set<String>, ids: Collection<String>): Set<String> =
        current.toHashSet().apply { addAll(ids) }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        }
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    /** Stable numeric date so it reads well in both Arabic and English. */
    fun formatDate(epochMillis: Long): String {
        if (epochMillis <= 0L) return "-"
        return SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(epochMillis))
    }
}
