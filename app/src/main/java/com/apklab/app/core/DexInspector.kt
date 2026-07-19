package com.apklab.app.core

import com.apklab.app.model.DexClassInfo
import com.apklab.app.model.ExtractedString
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal DEX reader: extracts class descriptors and printable strings
 * without full decompilation. Useful for inspection and plugin feeds.
 */
object DexInspector {

    fun inspect(extractDir: File): Pair<List<DexClassInfo>, List<ExtractedString>> {
        val classes = mutableListOf<DexClassInfo>()
        val strings = mutableListOf<ExtractedString>()

        extractDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dex", ignoreCase = true) }
            .forEach { dex ->
                try {
                    val parsed = parseDex(dex)
                    classes += parsed.first
                    strings += parsed.second
                } catch (_: Exception) {
                    // Skip corrupt/partial dex files
                }
            }

        return classes.distinctBy { it.name } to strings
            .distinctBy { it.value }
            .sortedByDescending { it.value.length }
    }

    private fun parseDex(dexFile: File): Pair<List<DexClassInfo>, List<ExtractedString>> {
        val bytes = dexFile.readBytes()
        if (bytes.size < 112) return emptyList<DexClassInfo>() to emptyList()

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(bytes, 0, 4)
        require(magic == "dex\n") { "Not a DEX file" }

        val stringIdsSize = buf.getInt(56)
        val stringIdsOff = buf.getInt(60)
        val typeIdsSize = buf.getInt(64)
        val typeIdsOff = buf.getInt(68)
        val classDefsSize = buf.getInt(96)
        val classDefsOff = buf.getInt(100)

        val stringPool = ArrayList<String>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            val off = buf.getInt(stringIdsOff + i * 4)
            stringPool += readMutf8(bytes, off)
        }

        val typeStrings = ArrayList<String>(typeIdsSize)
        for (i in 0 until typeIdsSize) {
            val stringIdx = buf.getInt(typeIdsOff + i * 4)
            typeStrings += stringPool.getOrElse(stringIdx) { "?" }
        }

        val classes = ArrayList<DexClassInfo>(classDefsSize)
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            val classIdx = buf.getInt(base)
            val descriptor = typeStrings.getOrElse(classIdx) { "?" }
            classes += DexClassInfo(
                name = descriptorToJava(descriptor),
                dexFile = dexFile.name
            )
        }

        val extracted = stringPool
            .asSequence()
            .filter { isUsefulString(it) }
            .map { ExtractedString(it, dexFile.name) }
            .toList()

        return classes to extracted
    }

    private fun descriptorToJava(descriptor: String): String {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length - 1).replace('/', '.')
        }
        return descriptor
    }

    private fun isUsefulString(value: String): Boolean {
        if (value.length < 4 || value.length > 400) return false
        if (value.any { it < ' ' && it != '\t' && it != '\n' && it != '\r' }) return false
        val interesting = value.contains('/') ||
            value.contains('.') ||
            value.contains("http") ||
            value.contains("api", ignoreCase = true) ||
            value.contains("key", ignoreCase = true) ||
            value.any { it.isLetter() }
        return interesting
    }

    private fun readMutf8(data: ByteArray, offset: Int): String {
        var pos = offset
        // ULEB128 length
        var len = 0
        var shift = 0
        while (true) {
            val b = data[pos].toInt() and 0xFF
            pos++
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val chars = StringBuilder(len)
        var i = 0
        while (i < len) {
            val a = data[pos].toInt() and 0xFF
            when {
                a and 0x80 == 0 -> {
                    chars.append(a.toChar())
                    pos++
                    i++
                }
                a and 0xE0 == 0xC0 -> {
                    val b = data[pos + 1].toInt() and 0xFF
                    chars.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                    pos += 2
                    i += 2
                }
                a and 0xF0 == 0xE0 -> {
                    val b = data[pos + 1].toInt() and 0xFF
                    val c = data[pos + 2].toInt() and 0xFF
                    chars.append(
                        (((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar()
                    )
                    pos += 3
                    i += 3
                }
                else -> {
                    pos++
                    i++
                }
            }
        }
        return chars.toString()
    }
}
