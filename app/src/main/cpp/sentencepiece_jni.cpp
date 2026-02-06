#include <jni.h>
#include <string>
#include <vector>
#include <mutex>

#include "sentencepiece_processor.h"

namespace {
std::mutex g_mutex;
std::unique_ptr<sentencepiece::SentencePieceProcessor> g_sp;

jstring makeJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

bool ensureLoaded(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_sp != nullptr;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yayahiwo_similarity_tokenizer_SentencePieceNative_loadFromSerializedProto(
    JNIEnv* env,
    jclass,
    jbyteArray modelBytes
) {
    if (modelBytes == nullptr) return JNI_FALSE;

    const jsize len = env->GetArrayLength(modelBytes);
    if (len <= 0) return JNI_FALSE;

    std::string data;
    data.resize(static_cast<size_t>(len));
    env->GetByteArrayRegion(modelBytes, 0, len, reinterpret_cast<jbyte*>(&data[0]));

    auto sp = std::make_unique<sentencepiece::SentencePieceProcessor>();
    auto status = sp->LoadFromSerializedProto(data);
    if (!status.ok()) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    g_sp = std::move(sp);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_yayahiwo_similarity_tokenizer_SentencePieceNative_encodeAsIds(
    JNIEnv* env,
    jclass,
    jstring text
) {
    if (!ensureLoaded(env) || text == nullptr) {
        return env->NewIntArray(0);
    }

    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string input = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(text, chars);

    std::vector<int> ids;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_sp) return env->NewIntArray(0);
        auto status = g_sp->Encode(input, &ids);
        if (!status.ok()) {
            return env->NewIntArray(0);
        }
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (!out) return nullptr;
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(ids.size()), reinterpret_cast<const jint*>(ids.data()));
    return out;
}
