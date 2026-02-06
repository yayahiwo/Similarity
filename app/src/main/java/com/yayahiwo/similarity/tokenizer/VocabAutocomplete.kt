package com.yayahiwo.similarity.tokenizer

import android.content.Context
import android.util.JsonReader
import com.yayahiwo.similarity.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.Locale

object VocabAutocomplete {
    private val wordRegex = Regex("^[a-z0-9]{2,32}$")
    private val loadLock = Any()

    @Volatile
    private var sortedWords: List<String>? = null

    suspend fun ensureLoaded(context: Context) {
        if (sortedWords != null) return
        withContext(Dispatchers.IO) {
            if (sortedWords != null) return@withContext
            synchronized(loadLock) {
                if (sortedWords != null) return@synchronized
                sortedWords = loadWords(context.applicationContext)
            }
        }
    }

    fun wordsOrEmpty(): List<String> {
        return sortedWords ?: emptyList()
    }

    fun suggest(prefixRaw: String, limit: Int = 10): List<String> {
        val prefix = prefixRaw.lowercase(Locale.US)
        if (prefix.length < 2) return emptyList()

        val words = sortedWords ?: return emptyList()
        val startIdx = words.binarySearch(prefix).let { if (it < 0) -it - 1 else it }

        val out = ArrayList<String>(limit)
        for (i in startIdx until words.size) {
            val word = words[i]
            if (!word.startsWith(prefix)) break
            out.add(word)
            if (out.size >= limit) break
        }
        return out
    }

    private fun loadWords(context: Context): List<String> {
        val set = HashSet<String>(50_000)
        context.resources.openRawResource(R.raw.vocab).use { input ->
            val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            reader.beginObject()
            while (reader.hasNext()) {
                val token = reader.nextName()
                reader.skipValue()

                if (!token.endsWith("</w>")) continue
                val word = token.removeSuffix("</w>").lowercase(Locale.US)
                if (!wordRegex.matches(word)) continue
                set.add(word)
            }
            reader.endObject()
            reader.close()
        }
        return set.toList().sorted()
    }
}
