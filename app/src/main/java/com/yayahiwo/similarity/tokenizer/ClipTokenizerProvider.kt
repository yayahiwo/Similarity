package com.yayahiwo.similarity.tokenizer

import android.content.Context
import android.util.JsonReader
import com.yayahiwo.similarity.R
import java.io.BufferedReader
import java.io.InputStreamReader

object ClipTokenizerProvider {
    private val lock = Any()
    @Volatile private var cached: ClipTokenizer? = null

    fun get(context: Context): ClipTokenizer {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val out = load(context.applicationContext)
            cached = out
            return out
        }
    }

    private fun load(context: Context): ClipTokenizer {
        val encoder = HashMap<String, Int>(60_000)
        context.resources.openRawResource(R.raw.vocab).use { input ->
            val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            reader.beginObject()
            while (reader.hasNext()) {
                // OpenAI CLIP BPE vocab commonly encodes end-of-word as "</w>".
                // Our tokenizer appends a literal space to mark end-of-word, so normalize here.
                val token = reader.nextName().replace("</w>", " ")
                val id = reader.nextInt()
                encoder[token] = id
            }
            reader.endObject()
            reader.close()
        }

        val bpeRanks = HashMap<Pair<String, String>, Int>(60_000)
        context.resources.openRawResource(R.raw.merges).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                var rank = 0
                for (lineRaw in lines) {
                    val line = lineRaw.trim()
                    if (line.isEmpty()) continue
                    if (line.startsWith("#")) continue
                    val parts = line.split(Regex("\\s+"), limit = 2)
                    if (parts.size < 2) continue
                    val first = parts[0]
                    // Same normalization as vocab.
                    val second = parts[1].replace("</w>", " ")
                    bpeRanks[first to second] = rank
                    rank += 1
                }
            }
        }

        return ClipTokenizer(encoder = encoder, bpeRanks = bpeRanks)
    }
}
