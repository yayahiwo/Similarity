#include <jni.h>
#include <turbojpeg.h>
#include <cstring>

static void throwRuntimeException(JNIEnv* env, const char* msg) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, msg);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yayahiwo_similarity_editor_JpegTurboNative_isAvailable(
    JNIEnv* /* env */,
    jobject /* thiz */) {
    return JNI_TRUE;
}

static int rotateExif90Clockwise(int o) {
    // ExifInterface orientation values:
    // 1 normal, 2 flip H, 3 rot180, 4 flip V, 5 transpose, 6 rot90, 7 transverse, 8 rot270
    switch (o) {
        case 1: return 6;
        case 2: return 7;
        case 3: return 8;
        case 4: return 5;
        case 5: return 2;
        case 6: return 3;
        case 7: return 4;
        case 8: return 1;
        default: return 6;
    }
}

static int rotateExif90CounterClockwise(int o) {
    // Inverse of rotateExif90Clockwise.
    switch (o) {
        case 1: return 8;
        case 2: return 5;
        case 3: return 6;
        case 4: return 7;
        case 5: return 4;
        case 6: return 1;
        case 7: return 2;
        case 8: return 3;
        default: return 8;
    }
}

static tjtransform makeTransformForDesiredExifOrientation(int desiredOrientation) {
    tjtransform xform;
    memset(&xform, 0, sizeof(xform));
    switch (desiredOrientation) {
        case 1: xform.op = TJXOP_NONE; break;
        case 2: xform.op = TJXOP_HFLIP; break;
        case 3: xform.op = TJXOP_ROT180; break;
        case 4: xform.op = TJXOP_VFLIP; break;
        case 5: xform.op = TJXOP_TRANSPOSE; break;
        case 6: xform.op = TJXOP_ROT90; break;
        case 7: xform.op = TJXOP_TRANSVERSE; break;
        case 8: xform.op = TJXOP_ROT270; break;
        default: xform.op = TJXOP_ROT90; break;
    }
    xform.options = TJXOPT_TRIM;
    return xform;
}

static jbyteArray transformJpeg(JNIEnv* env, jbyteArray jpegBytes, tjtransform xform) {
    if (jpegBytes == nullptr) return nullptr;

    const jsize inSize = env->GetArrayLength(jpegBytes);
    if (inSize <= 0) return nullptr;

    jboolean isCopy = JNI_FALSE;
    auto* inBuf = reinterpret_cast<unsigned char*>(env->GetByteArrayElements(jpegBytes, &isCopy));
    if (inBuf == nullptr) {
        throwRuntimeException(env, "Failed to access JPEG bytes");
        return nullptr;
    }

    tjhandle handle = tjInitTransform();
    if (handle == nullptr) {
        env->ReleaseByteArrayElements(jpegBytes, reinterpret_cast<jbyte*>(inBuf), JNI_ABORT);
        throwRuntimeException(env, tjGetErrorStr2(nullptr));
        return nullptr;
    }

    unsigned char* outBuf = nullptr;
    unsigned long outSize = 0;

    const int flags = 0;
    const int rc = tjTransform(handle, inBuf, static_cast<unsigned long>(inSize), 1, &outBuf, &outSize, &xform, flags);

    env->ReleaseByteArrayElements(jpegBytes, reinterpret_cast<jbyte*>(inBuf), JNI_ABORT);
    tjDestroy(handle);

    if (rc != 0 || outBuf == nullptr || outSize == 0) {
        if (outBuf != nullptr) tjFree(outBuf);
        throwRuntimeException(env, tjGetErrorStr2(nullptr));
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(outSize));
    if (out == nullptr) {
        tjFree(outBuf);
        throwRuntimeException(env, "Failed to allocate output byte array");
        return nullptr;
    }

    env->SetByteArrayRegion(out, 0, static_cast<jsize>(outSize), reinterpret_cast<jbyte*>(outBuf));
    tjFree(outBuf);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yayahiwo_similarity_editor_JpegTurboNative_rotate90ClockwiseLosslessJpeg(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray jpegBytes,
    jint exifOrientation) {
    const int desired = rotateExif90Clockwise(static_cast<int>(exifOrientation));
    tjtransform xform = makeTransformForDesiredExifOrientation(desired);
    return transformJpeg(env, jpegBytes, xform);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yayahiwo_similarity_editor_JpegTurboNative_rotate90CounterClockwiseLosslessJpeg(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray jpegBytes,
    jint exifOrientation) {
    const int desired = rotateExif90CounterClockwise(static_cast<int>(exifOrientation));
    tjtransform xform = makeTransformForDesiredExifOrientation(desired);
    return transformJpeg(env, jpegBytes, xform);
}
