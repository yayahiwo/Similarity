#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yayahiwo_similarity_editor_JpegTurboNative_isAvailable(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yayahiwo_similarity_editor_JpegTurboNative_rotate90ClockwiseLosslessJpeg(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray /* jpegBytes */,
    jint /* exifOrientation */) {
    // Stub implementation compiled when libjpeg-turbo is not present.
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yayahiwo_similarity_editor_JpegTurboNative_rotate90CounterClockwiseLosslessJpeg(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray /* jpegBytes */,
    jint /* exifOrientation */) {
    // Stub implementation compiled when libjpeg-turbo is not present.
    return nullptr;
}
