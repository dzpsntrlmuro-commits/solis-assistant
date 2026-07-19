package com.apklab.app.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight Android binary XML (AXML) decoder focused on AndroidManifest.xml.
 * Returns a readable text approximation of tags/attributes.
 */
object ManifestDecoder {

    fun decode(extractDir: File): String {
        val manifest = File(extractDir, "AndroidManifest.xml")
        if (!manifest.exists()) return "AndroidManifest.xml bulunamadı"
        return try {
            decodeAxml(manifest.readBytes())
        } catch (e: Exception) {
            "Manifest okunamadı: ${e.message}"
        }
    }

    fun extractPackageName(extractDir: File): String? {
        val text = decode(extractDir)
        val regex = Regex("""package="([^"]+)"""")
        return regex.find(text)?.groupValues?.getOrNull(1)
            ?: Regex("""android:package="([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
    }

    fun extractPermissions(extractDir: File): List<String> {
        val text = decode(extractDir)
        val regex = Regex("""android:name="(android\.permission\.[^"]+|[^"]*PERMISSION[^"]*)"""")
        return regex.findAll(text).map { it.groupValues[1] }.distinct().sorted().toList()
    }

    private fun decodeAxml(data: ByteArray): String {
        if (data.size < 8) return "Geçersiz AXML"
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.short.toInt() and 0xFFFF
        if (type != 0x0003) {
            // Already text XML?
            val asText = String(data)
            if (asText.contains("<manifest")) return asText
            return "Bilinmeyen AXML tipi: $type"
        }

        buf.position(8)
        // RES_STRING_POOL_TYPE = 0x0001
        val poolType = buf.short.toInt() and 0xFFFF
        buf.short // header size
        val poolSize = buf.int
        if (poolType != 0x0001) return "String pool bulunamadı"

        val stringCount = buf.int
        buf.int // style count
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // styles start
        val isUtf8 = (flags and (1 shl 8)) != 0

        val offsets = IntArray(stringCount) { buf.int }
        val poolBase = 8 + stringsStart
        val strings = Array(stringCount) { i ->
            readPoolString(data, poolBase + offsets[i], isUtf8)
        }

        val out = StringBuilder()
        var depth = 0
        var pos = 8 + poolSize
        // Skip optional XML resource map chunk
        if (pos + 8 <= data.size) {
            val mapType = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            if (mapType == 0x0180) {
                val mapSize = ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                pos += mapSize
            }
        }

        while (pos + 8 <= data.size) {
            val chunkType = ByteBuffer.wrap(data, pos, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val headerSize = ByteBuffer.wrap(data, pos + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val chunkSize = ByteBuffer.wrap(data, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkSize <= 0 || pos + chunkSize > data.size) break

            when (chunkType) {
                0x0102 -> { // START_ELEMENT
                    val nameIdx = ByteBuffer.wrap(data, pos + 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val attrCount = ByteBuffer.wrap(data, pos + 28, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    val name = strings.getOrNull(nameIdx) ?: "node"
                    out.append("  ".repeat(depth)).append('<').append(name)
                    var attrPos = pos + headerSize
                    repeat(attrCount) {
                        if (attrPos + 20 <= pos + chunkSize) {
                            val attrNameIdx = ByteBuffer.wrap(data, attrPos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            val rawValueIdx = ByteBuffer.wrap(data, attrPos + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            val valueType = data[attrPos + 15].toInt() and 0xFF
                            val valueData = ByteBuffer.wrap(data, attrPos + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            val attrName = strings.getOrNull(attrNameIdx) ?: "attr"
                            val attrValue = when {
                                rawValueIdx >= 0 -> strings.getOrNull(rawValueIdx) ?: ""
                                valueType == 0x03 -> strings.getOrNull(valueData) ?: valueData.toString()
                                valueType == 0x10 -> valueData.toString()
                                valueType == 0x12 -> if (valueData != 0) "true" else "false"
                                else -> "0x${Integer.toHexString(valueData)}"
                            }
                            out.append(' ').append(attrName).append("=\"").append(attrValue).append('"')
                            attrPos += 20
                        }
                    }
                    out.append(">\n")
                    depth++
                }
                0x0103 -> { // END_ELEMENT
                    depth = (depth - 1).coerceAtLeast(0)
                    val nameIdx = ByteBuffer.wrap(data, pos + 20, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val name = strings.getOrNull(nameIdx) ?: "node"
                    out.append("  ".repeat(depth)).append("</").append(name).append(">\n")
                }
                0x0104 -> { // CDATA / text
                    val nameIdx = ByteBuffer.wrap(data, pos + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val text = strings.getOrNull(nameIdx)
                    if (!text.isNullOrBlank()) {
                        out.append("  ".repeat(depth)).append(text).append('\n')
                    }
                }
            }
            pos += chunkSize
        }
        return out.toString().ifBlank { "Manifest içeriği çözümlenemedi" }
    }

    private fun readPoolString(data: ByteArray, offset: Int, utf8: Boolean): String {
        return try {
            if (utf8) {
                var pos = offset
                // UTF-8: u16len then u8len as uleb-like single/char lengths
                val charLen = data[pos].toInt() and 0xFF
                pos += if (charLen and 0x80 != 0) 2 else 1
                var byteLen = data[pos].toInt() and 0xFF
                pos += if (byteLen and 0x80 != 0) {
                    byteLen = ((byteLen and 0x7F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    2
                } else 1
                String(data, pos, byteLen, Charsets.UTF_8)
            } else {
                val len = ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                val chars = CharArray(len)
                var p = offset + 2
                for (i in 0 until len) {
                    chars[i] = ByteBuffer.wrap(data, p, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt().toChar()
                    p += 2
                }
                String(chars)
            }
        } catch (_: Exception) {
            ""
        }
    }
}
