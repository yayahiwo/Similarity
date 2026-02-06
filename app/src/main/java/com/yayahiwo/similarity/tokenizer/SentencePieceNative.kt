package com.yayahiwo.similarity.tokenizer

internal object SentencePieceNative {
    init {
        System.loadLibrary("sentencepiece_jni")
    }

    external fun loadFromSerializedProto(modelBytes: ByteArray): Boolean
    external fun encodeAsIds(text: String): IntArray
}
