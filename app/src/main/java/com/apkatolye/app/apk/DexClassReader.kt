package com.apkatolye.app.apk

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Minimal DEX class-name reader for educational / own-app inspection.
 * Reads class descriptors from classes*.dex inside an APK.
 */
object DexClassReader {

    fun listClasses(apkFile: File, limit: Int = 50_000): List<String> {
        val names = linkedSetOf<String>()
        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".dex", ignoreCase = true) }
                .sortedBy { it.name }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    names += parseDexClassNames(bytes)
                    if (names.size >= limit) return names.take(limit)
                }
        }
        return names.sorted()
    }

    fun listClassesFromExtracted(dir: File, limit: Int = 50_000): List<String> {
        val names = linkedSetOf<String>()
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dex", ignoreCase = true) }
            .sortedBy { it.name }
            .forEach { file ->
                names += parseDexClassNames(file.readBytes())
                if (names.size >= limit) return names.take(limit)
            }
        return names.sorted()
    }

    private fun parseDexClassNames(bytes: ByteArray): List<String> {
        if (bytes.size < 112) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(8)
        buf.get(magic)
        if (String(magic, 0, 3) != "dex") return emptyList()

        buf.position(56)
        val stringIdsSize = buf.int
        val stringIdsOff = buf.int
        buf.position(64)
        // typeIdsSize
        buf.int
        val typeIdsOff = buf.int
        buf.position(96)
        val classDefsSize = buf.int
        val classDefsOff = buf.int

        if (stringIdsSize <= 0 || classDefsSize <= 0) return emptyList()
        if (stringIdsOff < 0 || typeIdsOff < 0 || classDefsOff < 0) return emptyList()

        fun readString(index: Int): String? {
            if (index < 0 || index >= stringIdsSize) return null
            val offPos = stringIdsOff + index * 4
            if (offPos + 4 > bytes.size) return null
            buf.position(offPos)
            val dataOff = buf.int
            if (dataOff < 0 || dataOff >= bytes.size) return null
            return decodeMUtf8(bytes, dataOff)
        }

        fun typeToString(typeIdx: Int): String? {
            if (typeIdx < 0) return null
            val pos = typeIdsOff + typeIdx * 4
            if (pos + 4 > bytes.size) return null
            buf.position(pos)
            val stringIdx = buf.int
            return readString(stringIdx)?.let { descriptorToClassName(it) }
        }

        val result = ArrayList<String>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val pos = classDefsOff + i * 32
            if (pos + 4 > bytes.size) break
            buf.position(pos)
            val classIdx = buf.int
            typeToString(classIdx)?.let { result += it }
        }
        return result
    }

    private fun descriptorToClassName(descriptor: String): String {
        // Lcom/example/Foo; -> com.example.Foo
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        }
        return descriptor
    }

    private fun decodeMUtf8(bytes: ByteArray, offset: Int): String? {
        var pos = offset
        // Skip uleb128 length
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            if (b and 0x80 == 0) break
        }
        if (pos >= bytes.size) return null

        val out = StringBuilder()
        while (pos < bytes.size) {
            val a = bytes[pos].toInt() and 0xFF
            when {
                a == 0 -> break
                a < 0x80 -> {
                    out.append(a.toChar())
                    pos++
                }
                a and 0xE0 == 0xC0 -> {
                    if (pos + 1 >= bytes.size) break
                    val b = bytes[pos + 1].toInt() and 0xFF
                    out.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                    pos += 2
                }
                a and 0xF0 == 0xE0 -> {
                    if (pos + 2 >= bytes.size) break
                    val b = bytes[pos + 1].toInt() and 0xFF
                    val c = bytes[pos + 2].toInt() and 0xFF
                    out.append(
                        (((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar()
                    )
                    pos += 3
                }
                else -> pos++
            }
            if (out.length > 10_000) break
        }
        return out.toString()
    }

    /** Debug helper unused in release flow but kept for local checks. */
    @Suppress("unused")
    fun peekHeader(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(8)
            raf.readFully(magic)
            return String(magic)
        }
    }
}
