package com.aw950aw.smartbackup.backup

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Checks a TAR archive before anything is extracted with elevated privileges.
 *
 * Accepted: regular files, directories, symbolic links, and the GNU long-name (L/K) and pax (x)
 * extension headers that real app data needs for long paths.
 * Rejected: hard links, device files, absolute or ".." paths, duplicate paths, and any entry that
 * would be written through a symbolic link created earlier in the same archive.
 */
object TarValidator {
    private const val MAX_META_BYTES = 1L shl 20
    private const val MAX_ENTRIES = 500_000
    private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L

    fun validate(file: File, requiredTopLevel: String?) {
        FileInputStream(file).buffered().use { input ->
            val header = ByteArray(512)
            val seen = HashSet<String>()
            val symlinks = HashSet<String>()
            var entries = 0
            var total = 0L
            var pendingPath: String? = null
            var pendingLink: String? = null
            var pendingSize: Long? = null

            while (true) {
                var position = 0
                while (position < 512) {
                    val count = input.read(header, position, 512 - position)
                    if (count < 0) {
                        require(position == 0) { "קובץ TAR פגום: כותרת חלקית." }
                        return
                    }
                    position += count
                }
                if (header.all { it == 0.toByte() }) return
                entries++
                require(entries <= MAX_ENTRIES) { "קובץ TAR מכיל יותר מדי פריטים." }

                val type = (header[156].toInt() and 0xff).toChar()
                val headerSize = parseSize(header)

                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    require(headerSize in 0L..MAX_META_BYTES) { "כותרת TAR מורחבת גדולה מדי." }
                    val data = readFully(input, headerSize.toInt())
                    skipFully(input, padding(headerSize))
                    when (type) {
                        'L' -> pendingPath = cString(data)
                        'K' -> pendingLink = cString(data)
                        'x' -> {
                            val records = parsePax(data)
                            val path = records["path"]
                            if (path != null) pendingPath = path
                            val link = records["linkpath"]
                            if (link != null) pendingLink = link
                            val size = records["size"]
                            if (size != null) {
                                val parsed = size.toLongOrNull()
                                require(parsed != null && parsed >= 0L) { "גודל PAX אינו תקין." }
                                pendingSize = parsed
                            }
                        }
                        else -> {
                            val records = parsePax(data)
                            require(records.keys.none { it == "path" || it == "linkpath" || it == "size" }) {
                                "כותרת PAX גלובלית לא נתמכת."
                            }
                        }
                    }
                    continue
                }

                val isRegular = type == '0' || type == 0.toChar()
                val isDirectory = type == '5'
                val isSymlink = type == '2'
                require(isRegular || isDirectory || isSymlink) {
                    "הגיבוי כולל פריט מסוג שאינו נתמך (קישור קשיח או קובץ מיוחד). השחזור נעצר מטעמי בטיחות."
                }

                val rawPath = pendingPath ?: run {
                    val name = field(header, 0, 100)
                    val posix = String(header, 257, 6, Charsets.US_ASCII) == "ustar\u0000"
                    val prefix = if (posix) field(header, 345, 155) else ""
                    if (prefix.isBlank()) name else "$prefix/$name"
                }
                val linkTarget = pendingLink ?: field(header, 157, 100)
                val declaredSize = pendingSize ?: headerSize
                pendingPath = null
                pendingLink = null
                pendingSize = null

                var path = rawPath.removePrefix("./")
                if (isDirectory) path = path.trimEnd('/')
                require(isSafePath(path) && (path.isNotEmpty() || isDirectory)) { "נתיב לא בטוח בקובץ TAR." }
                if (path.isNotEmpty()) {
                    require(seen.add(path)) { "קובץ TAR מכיל פריט כפול: $path" }
                    val parts = path.split('/')
                    var prefixPath = ""
                    for (index in 0 until parts.size - 1) {
                        prefixPath = if (prefixPath.isEmpty()) parts[index] else prefixPath + "/" + parts[index]
                        require(prefixPath !in symlinks) { "קובץ TAR מנסה לכתוב דרך קישור סימבולי: $path" }
                    }
                    if (requiredTopLevel != null) {
                        require(parts.first() == requiredTopLevel) {
                            "קובץ TAR מכיל נתיב שאינו שייך לאפליקציה שבגיבוי."
                        }
                    }
                }
                if (isSymlink) {
                    require(path.isNotEmpty() && linkTarget.isNotEmpty() && linkTarget.none { it.code < 32 }) {
                        "קישור סימבולי לא תקין בקובץ TAR."
                    }
                    symlinks.add(path)
                }

                val dataSize = if (isRegular) declaredSize else 0L
                require(dataSize >= 0L) { "גודל פריט TAR אינו תקין." }
                total += dataSize
                require(total <= MAX_TOTAL_BYTES) { "קובץ TAR גדול מדי." }
                skipFully(input, dataSize + padding(dataSize))
            }
        }
    }

    private fun padding(size: Long): Long = (512L - size % 512L) % 512L

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseSize(header: ByteArray): Long {
        if ((header[124].toInt() and 0x80) != 0) {
            var value = (header[124].toInt() and 0x7f).toLong()
            for (index in 125 until 136) {
                require(value <= (Long.MAX_VALUE shr 8)) { "גודל פריט TAR אינו תקין." }
                value = (value shl 8) or (header[index].toLong() and 0xffL)
            }
            return value
        }
        val text = field(header, 124, 12).trim()
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: throw IllegalArgumentException("גודל פריט TAR אינו תקין.")
    }

    private fun cString(data: ByteArray): String {
        var end = 0
        while (end < data.size && data[end] != 0.toByte()) end++
        return String(data, 0, end, Charsets.UTF_8)
    }

    /** Parses pax records: "<length> <key>=<value>" followed by a newline. */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var position = 0
        while (position < data.size) {
            var space = position
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            require(space < data.size) { "רשומת PAX פגומה." }
            val length = String(data, position, space - position, Charsets.US_ASCII).toIntOrNull()
                ?: throw IllegalArgumentException("רשומת PAX פגומה.")
            require(length >= space - position + 2 && position + length <= data.size) { "רשומת PAX פגומה." }
            val record = String(data, space + 1, position + length - space - 2, Charsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            position += length
        }
        return result
    }

    private fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path == ".") return true
        if (path.startsWith("/") || path.startsWith("~") || path.contains('\\')) return false
        if (path.any { it.code < 32 }) return false
        return path.split('/').none { it == ".." || it.isEmpty() }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            require(count >= 0) { "קובץ TAR מסתיים לפני סוף הנתונים." }
            offset += count
        }
        return data
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                require(input.read() >= 0) { "קובץ TAR מסתיים לפני סוף הנתונים." }
                remaining--
            }
        }
    }
}
