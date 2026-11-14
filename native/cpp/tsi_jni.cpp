#include <jni.h>
#include <string>

extern "C" {
int tsi_sha1_matches(const char *path, const char *expected);
int tsi_is_verified_file(const char *path, const char *expected, long long expected_size);
int tsi_download_verified(const char *url, const char *destination,
                          const char *expected_sha1, long long expected_size);
int tsi_extract_zip(const char *archive, const char *destination,
                    long long max_entries, long long max_bytes);
int tsi_ensure_game_folders(const char *game_directory);
int tsi_ensure_instance_layout(const char *minecraft_root, const char *instance_id,
                               const char *version_id);
int tsi_delete_instance(const char *minecraft_root, const char *instance_id);
int tsi_copy_tree(const char *source, const char *destination);
const char *tsi_last_error(void);
}

namespace {
std::string toUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool requireString(JNIEnv *env, jstring value, const char *label,
                   std::string *output) {
    if (value == nullptr) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(error, (std::string(label) + " is required").c_str());
        return false;
    }
    *output = toUtf8(env, value);
    if (output->empty()) {
        jclass error = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(error, (std::string(label) + " is empty").c_str());
        return false;
    }
    return true;
}

void throwNativeError(JNIEnv *env, const char *fallback) {
    const char *message = tsi_last_error();
    jclass error = env->FindClass("java/io/IOException");
    env->ThrowNew(error, (message != nullptr && message[0] != '\0') ? message : fallback);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeSha1Matches(
        JNIEnv *env, jclass, jstring path, jstring expected) {
    std::string nativePath, nativeExpected;
    if (!requireString(env, path, "File path", &nativePath) ||
        !requireString(env, expected, "Expected SHA-1", &nativeExpected)) return JNI_FALSE;
    int result = tsi_sha1_matches(nativePath.c_str(), nativeExpected.c_str());
    if (result < 0) {
        throwNativeError(env, "Native SHA-1 verification failed");
        return JNI_FALSE;
    }
    return result == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeIsVerifiedFile(
        JNIEnv *env, jclass, jstring path, jstring expected, jlong expectedSize) {
    std::string nativePath;
    if (!requireString(env, path, "File path", &nativePath)) return JNI_FALSE;
    std::string nativeExpected = toUtf8(env, expected);
    const char *expectedPointer = expected == nullptr ? nullptr : nativeExpected.c_str();
    int result = tsi_is_verified_file(nativePath.c_str(), expectedPointer,
                                      static_cast<long long>(expectedSize));
    if (result < 0) {
        throwNativeError(env, "Native file verification failed");
        return JNI_FALSE;
    }
    return result == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeDownloadVerified(
        JNIEnv *env, jclass, jstring url, jstring destination,
        jstring expected, jlong expectedSize) {
    std::string nativeUrl, nativeDestination;
    if (!requireString(env, url, "Download URL", &nativeUrl) ||
        !requireString(env, destination, "Download destination", &nativeDestination)) return;
    std::string nativeExpected = toUtf8(env, expected);
    const char *expectedPointer = expected == nullptr ? nullptr : nativeExpected.c_str();
    int result = tsi_download_verified(nativeUrl.c_str(), nativeDestination.c_str(),
                                       expectedPointer, static_cast<long long>(expectedSize));
    if (result != 0) throwNativeError(env, "Native download failed");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeExtractZip(
        JNIEnv *env, jclass, jstring archive, jstring destination,
        jint maxEntries, jlong maxBytes) {
    std::string nativeArchive, nativeDestination;
    if (!requireString(env, archive, "ZIP archive", &nativeArchive) ||
        !requireString(env, destination, "ZIP destination", &nativeDestination)) return -1;
    int result = tsi_extract_zip(nativeArchive.c_str(), nativeDestination.c_str(),
                                 static_cast<long long>(maxEntries),
                                 static_cast<long long>(maxBytes));
    if (result < 0) throwNativeError(env, "Native ZIP extraction failed");
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeEnsureGameFolders(
        JNIEnv *env, jclass, jstring gameDirectory) {
    std::string nativePath;
    if (!requireString(env, gameDirectory, "Game directory", &nativePath)) return;
    if (tsi_ensure_game_folders(nativePath.c_str()) != 0) {
        throwNativeError(env, "Could not prepare Minecraft game folders");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeEnsureInstanceLayout(
        JNIEnv *env, jclass, jstring minecraftRoot, jstring instanceId, jstring versionId) {
    std::string nativeRoot, nativeId, nativeVersion;
    if (!requireString(env, minecraftRoot, "Minecraft root", &nativeRoot) ||
        !requireString(env, instanceId, "Instance ID", &nativeId) ||
        !requireString(env, versionId, "Minecraft version ID", &nativeVersion)) return;
    if (tsi_ensure_instance_layout(nativeRoot.c_str(), nativeId.c_str(),
                                   nativeVersion.c_str()) != 0) {
        throwNativeError(env, "Could not prepare Minecraft instance directories");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeDeleteInstance(
        JNIEnv *env, jclass, jstring minecraftRoot, jstring instanceId) {
    std::string nativeRoot, nativeId;
    if (!requireString(env, minecraftRoot, "Minecraft root", &nativeRoot) ||
        !requireString(env, instanceId, "Instance ID", &nativeId)) return;
    if (tsi_delete_instance(nativeRoot.c_str(), nativeId.c_str()) != 0) {
        throwNativeError(env, "Could not delete Minecraft instance");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_tsi_launcher_nativecore_NativeBridge_nativeCopyTree(
        JNIEnv *env, jclass, jstring source, jstring destination) {
    std::string nativeSource, nativeDestination;
    if (!requireString(env, source, "Copy source", &nativeSource) ||
        !requireString(env, destination, "Copy destination", &nativeDestination)) return;
    if (tsi_copy_tree(nativeSource.c_str(), nativeDestination.c_str()) != 0) {
        throwNativeError(env, "Could not copy Minecraft instance data");
    }
}
