package com.snapknow.app.voice

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

class WhisperTokenizer private constructor(
    private val idToToken: Map<Int, String>
) {
    fun decode(tokenIds: List<Long>): String {
        if (tokenIds.isEmpty()) return ""

        val bytes = ArrayList<Byte>(tokenIds.size * 2)
        tokenIds.forEach { tokenId ->
            val token = idToToken[tokenId.toInt()] ?: return@forEach
            if (token.startsWith("<|") && token.endsWith("|>")) {
                return@forEach
            }
            token.forEach { char ->
                val byteValue = BYTE_DECODER[char] ?: return@forEach
                bytes += byteValue.toByte()
            }
        }

        val byteArray = ByteArray(bytes.size)
        bytes.forEachIndexed { index, value -> byteArray[index] = value }
        return byteArray.toString(Charset.forName("UTF-8")).trim()
    }

    companion object {
        fun fromAsset(context: Context, assetPath: String): WhisperTokenizer? {
            return runCatching {
                val json = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                fromJson(json)
            }.getOrNull()
        }

        fun fromFile(file: File): WhisperTokenizer? {
            return runCatching {
                fromJson(file.readText())
            }.getOrNull()
        }

        private fun fromJson(json: String): WhisperTokenizer {
            val root = JSONObject(json)
            val vocab = root.getJSONObject("model").getJSONObject("vocab")
            val idToToken = buildMap<Int, String> {
                val keys = vocab.keys()
                while (keys.hasNext()) {
                    val token = keys.next()
                    put(vocab.getInt(token), token)
                }
            }
            return WhisperTokenizer(idToToken)
        }

        private val BYTE_DECODER: Map<Char, Int> = buildByteDecoder()

        // GPT-2 / Whisper byte decoder mapping used for token-to-text reconstruction.
        private fun buildByteDecoder(): Map<Char, Int> {
            val bytes = mutableListOf<Int>()
            bytes += (33..126)
            bytes += (161..172)
            bytes += (174..255)

            val chars = bytes.map(Int::toChar).toMutableList()
            var extra = 0
            for (value in 0..255) {
                if (value !in bytes) {
                    bytes += value
                    chars += (256 + extra).toChar()
                    extra += 1
                }
            }
            return chars.zip(bytes).toMap()
        }
    }
}
