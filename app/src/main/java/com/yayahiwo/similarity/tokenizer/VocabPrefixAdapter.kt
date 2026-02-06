package com.yayahiwo.similarity.tokenizer

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import java.util.Locale

class VocabPrefixAdapter(context: Context) :
    ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line) {
    private val items = ArrayList<String>(10)

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): String = items[position]

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val raw = constraint?.toString().orEmpty().lowercase(Locale.US)
            val prefix = raw.substringAfterLast(' ')
            val suggestions = VocabAutocomplete.suggest(prefix, limit = 10)
            return FilterResults().apply {
                values = suggestions
                count = suggestions.size
            }
        }

        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            items.clear()
            @Suppress("UNCHECKED_CAST")
            val values = results?.values as? List<String>
            if (!values.isNullOrEmpty()) {
                items.addAll(values)
            }
            notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence {
            return resultValue as? String ?: ""
        }
    }
}

