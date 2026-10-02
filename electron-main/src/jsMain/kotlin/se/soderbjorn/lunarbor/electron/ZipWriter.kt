/* ZipWriter.kt — streams a folder into a `.zip` file, in the Electron main process.
 *
 * Used by VaultBackup.kt to zip the whole vault. Plain ZIP (PKWARE APPNOTE):
 * one local header + data per entry, then the central directory. Entries are
 * deflated with Node's `zlib` (stored instead when that does not shrink them),
 * names are UTF-8 (flag bit 11), folders get their own entries so empty ones
 * survive, and ZIP64 records are added only when the archive needs them (more
 * than 65 535 entries, or past 4 GB). Each file is read and compressed whole,
 * so a single file must stay under Node's buffer limit (~2 GB).
 *
 * Symbolic links are skipped (no loops, nothing outside the folder).
 *
 * Main-process glue only; nothing here knows about vaults or backups. */
package se.soderbjorn.lunarbor.electron

import kotlinx.coroutines.await
import kotlin.js.Promise

private val nodeRequireZip: dynamic = js("require")
private val zlibModule: dynamic = nodeRequireZip("zlib")
private val bufferClass: dynamic = nodeRequireZip("buffer").Buffer

private const val U16_MAX: Long = 0xFFFF
private const val U32_MAX: Long = 0xFFFFFFFFL

/** Unix mode bits stored in each entry's external attributes. */
private const val MODE_FILE: Long = 0x81A4 // 0o100644
private const val MODE_DIR: Long = 0x41ED // 0o040755

/**
 * One entry already written, kept for the central directory.
 *
 * @property name Path inside the archive, `/`-separated; folders end in `/`.
 * @property isDir Whether this is a folder entry.
 * @property method 0 (stored) or 8 (deflated).
 * @property dosTime DOS time / date of the entry, packed as `date << 16 | time`.
 * @property crc CRC-32 of the uncompressed bytes.
 * @property compressedSize Bytes stored in the archive.
 * @property size Uncompressed bytes.
 * @property offset Where the entry's local header starts in the archive.
 */
private class ZipEntry(
    val name: ByteArray,
    val isDir: Boolean,
    val method: Int,
    val dosTime: Long,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val offset: Long,
)

/**
 * Zips every file and folder under [sourceDir] into [targetFile], which is
 * created (or replaced). Entry names are relative to [sourceDir].
 *
 * Called by `BackupHost.runBackup`. Reads only; the caller makes sure nothing
 * writes to [sourceDir] meanwhile.
 *
 * @param sourceDir Absolute folder to archive.
 * @param targetFile Absolute path of the archive to write; its folder must exist.
 * @return The number of entries written.
 */
internal suspend fun zipFolder(sourceDir: String, targetFile: String): Int {
    val handle: dynamic = fsPromises.asDynamic().open(targetFile, "w").unsafeCast<Promise<dynamic>>().await()
    var offset = 0L
    val entries = ArrayList<ZipEntry>()

    suspend fun write(bytes: dynamic) {
        val length = (bytes.length as Number).toLong()
        if (length == 0L) return
        (handle.write(bytes) as Promise<dynamic>).await()
        offset += length
    }

    suspend fun addEntry(relName: String, isDir: Boolean, mtimeMs: Double, data: dynamic) {
        val name = (relName + if (isDir) "/" else "").encodeToByteArray()
        val size = if (isDir) 0L else (data.length as Number).toLong()
        val crc = if (isDir) 0L else crc32Of(data)
        var method = 0
        var body: dynamic = data
        if (!isDir && size > 0) {
            val deflated = deflateRaw(data)
            if ((deflated.length as Number).toLong() < size) {
                method = 8
                body = deflated
            }
        }
        val compressedSize = if (isDir) 0L else (body.length as Number).toLong()
        val entry = ZipEntry(name, isDir, method, dosDateTime(mtimeMs), crc, compressedSize, size, offset)
        write(toBuffer(localHeader(entry)))
        if (!isDir) write(body)
        entries += entry
    }

    suspend fun walk(dirAbs: String, relPrefix: String) {
        val opts: dynamic = js("({})")
        opts.withFileTypes = true
        val dirents = fsPromises.readdir(dirAbs, opts).await()
            .sortedBy { it.name as String }
        for (d in dirents) {
            val name = d.name as String
            val abs = pathModule.join(dirAbs, name)
            val rel = if (relPrefix.isEmpty()) name else "$relPrefix/$name"
            when {
                d.isSymbolicLink() as Boolean -> continue
                d.isDirectory() as Boolean -> {
                    val stat = fsPromises.stat(abs).await()
                    addEntry(rel, isDir = true, mtimeMs = stat.mtimeMs as Double, data = null)
                    walk(abs, rel)
                }
                d.isFile() as Boolean -> {
                    // A file can vanish between listing and reading (an
                    // outside program); skip it rather than fail the backup.
                    val stat = try { fsPromises.stat(abs).await() } catch (_: Throwable) { continue }
                    val data = try { fsPromises.readFile(abs).await() } catch (_: Throwable) { continue }
                    addEntry(rel, isDir = false, mtimeMs = stat.mtimeMs as Double, data = data)
                }
            }
        }
    }

    try {
        walk(sourceDir, "")
        val cdStart = offset
        for (entry in entries) write(toBuffer(centralHeader(entry)))
        val cdSize = offset - cdStart
        write(toBuffer(endRecords(entries.size.toLong(), cdStart, cdSize, offset)))
    } finally {
        (handle.close() as Promise<dynamic>).await()
    }
    return entries.size
}

/** The local file header of [e]. Sizes always fit 32 bits (each file is under ~2 GB). */
private fun localHeader(e: ZipEntry): ByteArray = ZipBytes().apply {
    u32(0x04034b50)
    u16(20) // version needed: 2.0 (deflate, folders)
    u16(0x0800) // UTF-8 names
    u16(e.method.toLong())
    u32(e.dosTime)
    u32(e.crc)
    u32(e.compressedSize)
    u32(e.size)
    u16(e.name.size.toLong())
    u16(0)
    bytes(e.name)
}.toByteArray()

/** The central directory header of [e], with a ZIP64 extra field when its offset needs one. */
private fun centralHeader(e: ZipEntry): ByteArray {
    val zip64 = e.offset >= U32_MAX
    return ZipBytes().apply {
        u32(0x02014b50)
        u16((3L shl 8) or 45) // made by: Unix, spec 4.5
        u16(if (zip64) 45 else 20)
        u16(0x0800)
        u16(e.method.toLong())
        u32(e.dosTime)
        u32(e.crc)
        u32(e.compressedSize)
        u32(e.size)
        u16(e.name.size.toLong())
        u16(if (zip64) 12 else 0)
        u16(0) // comment
        u16(0) // disk
        u16(0) // internal attributes
        u32(((if (e.isDir) MODE_DIR else MODE_FILE) shl 16) or (if (e.isDir) 0x10L else 0L))
        u32(if (zip64) U32_MAX else e.offset)
        bytes(e.name)
        if (zip64) {
            u16(0x0001)
            u16(8)
            u64(e.offset)
        }
    }.toByteArray()
}

/**
 * The end-of-central-directory record, preceded by the ZIP64 end record and
 * its locator when [count], [cdStart] or [cdSize] overflow the classic fields.
 *
 * @param endOffset Where these records start (just after the central directory).
 */
internal fun endRecords(count: Long, cdStart: Long, cdSize: Long, endOffset: Long): ByteArray {
    val zip64 = count >= U16_MAX || cdStart >= U32_MAX || cdSize >= U32_MAX
    return ZipBytes().apply {
        if (zip64) {
            u32(0x06064b50)
            u64(44) // size of the rest of this record
            u16((3L shl 8) or 45)
            u16(45)
            u32(0)
            u32(0)
            u64(count)
            u64(count)
            u64(cdSize)
            u64(cdStart)
            u32(0x07064b50) // locator
            u32(0)
            u64(endOffset)
            u32(1)
        }
        u32(0x06054b50)
        u16(0)
        u16(0)
        u16(minOf(count, U16_MAX))
        u16(minOf(count, U16_MAX))
        u32(minOf(cdSize, U32_MAX))
        u32(minOf(cdStart, U32_MAX))
        u16(0)
    }.toByteArray()
}

/**
 * [epochMs] in MS-DOS form (local time, 2-second resolution), packed as
 * `date << 16 | time`. Clamped to 1980, the earliest DOS date.
 */
internal fun dosDateTime(epochMs: Double): Long {
    val d: dynamic = js("new Date(epochMs)")
    val year = d.getFullYear() as Int
    if (year < 1980) return (1L shl 21) or (1L shl 16) // 1980-01-01 00:00
    val date = ((year - 1980).toLong() shl 9) or ((d.getMonth() as Int) + 1L shl 5) or (d.getDate() as Int).toLong()
    val time = ((d.getHours() as Int).toLong() shl 11) or ((d.getMinutes() as Int).toLong() shl 5) or
        ((d.getSeconds() as Int) / 2).toLong()
    return (date shl 16) or time
}

/** Little-endian byte builder for ZIP headers. */
private class ZipBytes {
    private val out = ArrayList<Byte>(64)
    fun u16(v: Long) { for (i in 0 until 2) out += (v ushr (8 * i)).toByte() }
    fun u32(v: Long) { for (i in 0 until 4) out += (v ushr (8 * i)).toByte() }
    fun u32(v: Int) = u32(v.toLong() and U32_MAX)
    fun u64(v: Long) { for (i in 0 until 8) out += (v ushr (8 * i)).toByte() }
    fun bytes(b: ByteArray) { for (x in b) out += x }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** A Node `Buffer` viewing [bytes] (a Kotlin/JS `ByteArray` is an `Int8Array`). */
private fun toBuffer(bytes: ByteArray): dynamic {
    val view: dynamic = bytes
    return bufferClass.from(view.buffer, view.byteOffset, view.length)
}

private suspend fun deflateRaw(data: dynamic): dynamic = Promise<dynamic> { resolve, reject ->
    zlibModule.deflateRaw(data) { err: dynamic, out: dynamic ->
        if (err != null) reject(err.unsafeCast<Throwable>()) else resolve(out)
    }
}.await()

/** CRC-32 of a Node `Buffer`: `zlib.crc32` where Node has it (20.15+), else [crc32]. */
private fun crc32Of(data: dynamic): Long {
    if (jsTypeOf(zlibModule.crc32) == "function") {
        return (zlibModule.crc32(data) as Number).toLong()
    }
    return crc32(data.unsafeCast<ByteArray>())
}

private val crcTable: LongArray by lazy {
    LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) 0xEDB88320L xor (c ushr 1) else c ushr 1 }
        c
    }
}

/** CRC-32 (IEEE, as ZIP uses) of [bytes]. Fallback for Node without `zlib.crc32`. */
internal fun crc32(bytes: ByteArray): Long {
    var c = 0xFFFFFFFFL
    for (b in bytes) c = crcTable[((c xor b.toLong()) and 0xFF).toInt()] xor (c ushr 8)
    return c xor 0xFFFFFFFFL
}
