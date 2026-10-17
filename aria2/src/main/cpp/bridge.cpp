#include <jni.h>
#include <aria2/aria2.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

namespace {
aria2::Session* session = nullptr;
bool initialized = false;
std::thread::id owner;
std::mutex gate;

// Convert UTF-16 explicitly: JNI modified UTF-8 is not filesystem UTF-8.
std::string text(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("null string");
    const auto size = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::runtime_error("string allocation failed");
    std::string result;
    try {
        for (jsize i = 0; i < size; ++i) {
            uint32_t code = chars[i];
            if (code >= 0xd800 && code <= 0xdbff) {
                if (i + 1 >= size || chars[i + 1] < 0xdc00 || chars[i + 1] > 0xdfff)
                    throw std::invalid_argument("invalid surrogate");
                code = 0x10000 + ((code - 0xd800) << 10) + (chars[++i] - 0xdc00);
            } else if (code >= 0xdc00 && code <= 0xdfff) {
                throw std::invalid_argument("invalid surrogate");
            }
            if (code == 0) throw std::invalid_argument("embedded null");
            if (code < 0x80) result += static_cast<char>(code);
            else if (code < 0x800) {
                result += static_cast<char>(0xc0 | (code >> 6));
                result += static_cast<char>(0x80 | (code & 63));
            } else if (code < 0x10000) {
                result += static_cast<char>(0xe0 | (code >> 12));
                result += static_cast<char>(0x80 | ((code >> 6) & 63));
                result += static_cast<char>(0x80 | (code & 63));
            } else {
                result += static_cast<char>(0xf0 | (code >> 18));
                result += static_cast<char>(0x80 | ((code >> 12) & 63));
                result += static_cast<char>(0x80 | ((code >> 6) & 63));
                result += static_cast<char>(0x80 | (code & 63));
            }
        }
    } catch (...) {
        env->ReleaseStringChars(value, chars);
        throw;
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

aria2::KeyVals pairs(JNIEnv* env, jobjectArray values) {
    if (!values || env->GetArrayLength(values) % 2 != 0)
        throw std::invalid_argument("invalid options");
    aria2::KeyVals result;
    for (jsize i = 0; i < env->GetArrayLength(values); i += 2) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i + 1));
        try { result.emplace_back(text(env, key), text(env, value)); }
        catch (...) {
            env->DeleteLocalRef(key);
            env->DeleteLocalRef(value);
            throw;
        }
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    return result;
}

void requireSession() {
    if (!session || owner != std::this_thread::get_id())
        throw std::logic_error("session ownership violation");
}

void checked(int result) {
    if (result < 0) throw std::runtime_error("native command failed");
}

template <typename Action> void guarded(JNIEnv* env, Action action) {
    std::lock_guard<std::mutex> lock(gate);
    try { action(); }
    catch (...) {
        // Never forward native messages: they may contain URLs or credentials.
        if (!env->ExceptionCheck()) {
            jclass type = env->FindClass("com/julien_wff/aria_android/aria2/EngineException");
            if (type) {
                env->ThrowNew(type, "Native engine operation failed");
                env->DeleteLocalRef(type);
            }
        }
    }
}
}

#define JNI_METHOD(name) Java_com_julien_1wff_aria_1android_aria2_JniBackend_##name

extern "C" JNIEXPORT void JNICALL JNI_METHOD(open)(JNIEnv* env, jobject, jobjectArray options) {
    guarded(env, [&] {
        if (session) throw std::logic_error("session already exists");
        auto opts = pairs(env, options);
        // libaria2's preference registry is process-global. libraryDeinit clears
        // it and cannot be followed by libraryInit in the same loaded library.
        if (!initialized) {
            checked(aria2::libraryInit());
            initialized = true;
        }
        aria2::SessionConfig config;
        config.keepRunning = true;
        config.useSignalHandler = false;
        session = aria2::sessionNew(opts, config);
        if (!session) throw std::runtime_error("session creation failed");
        owner = std::this_thread::get_id();
    });
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(add)(JNIEnv* env, jobject, jstring id,
                                                  jstring url, jobjectArray options) {
    guarded(env, [&] {
        requireSession();
        auto opts = pairs(env, options);
        opts.emplace_back("gid", text(env, id));
        aria2::A2Gid assigned;
        checked(aria2::addUri(session, &assigned, {text(env, url)}, opts));
    });
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(addMirrors)(JNIEnv* env, jobject, jstring id,
                                                       jobjectArray urls, jobjectArray options) {
    guarded(env, [&] {
        requireSession();
        auto opts = pairs(env, options);
        opts.emplace_back("gid", text(env, id));
        std::vector<std::string> uris;
        for (jsize i = 0; i < env->GetArrayLength(urls); ++i) {
            auto value = static_cast<jstring>(env->GetObjectArrayElement(urls, i));
            uris.push_back(text(env, value)); env->DeleteLocalRef(value);
        }
        aria2::A2Gid assigned;
        checked(aria2::addUri(session, &assigned, uris, opts));
    });
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(addTorrent)(JNIEnv* env, jobject, jstring id,
                                                         jstring path, jobjectArray options) {
    guarded(env, [&] {
        requireSession();
        auto opts = pairs(env, options);
        opts.emplace_back("gid", text(env, id));
        aria2::A2Gid assigned;
        checked(aria2::addTorrent(session, &assigned, text(env, path), opts));
    });
}
extern "C" JNIEXPORT jobjectArray JNICALL JNI_METHOD(files)(JNIEnv* env, jobject, jstring id) {
    jobjectArray result = nullptr;
    guarded(env, [&] {
        requireSession();
        std::unique_ptr<aria2::DownloadHandle, decltype(&aria2::deleteDownloadHandle)> handle(
            aria2::getDownloadHandle(session, aria2::hexToGid(text(env, id))), aria2::deleteDownloadHandle);
        if (!handle) throw std::runtime_error("unknown task");
        auto files = handle->getFiles();
        jclass stringType = env->FindClass("java/lang/String");
        result = env->NewObjectArray(files.size() * 4, stringType, nullptr);
        if (!result) { env->DeleteLocalRef(stringType); return; }
        auto constructor = env->GetMethodID(stringType, "<init>", "([BLjava/lang/String;)V");
        auto charset = env->NewStringUTF("UTF-8");
        if (!constructor || !charset) { env->DeleteLocalRef(stringType); return; }
        size_t index = 0;
        for (const auto& file : files) {
            for (const auto& value : {std::to_string(file.index), file.path,
                    std::to_string(file.length), std::to_string(file.completedLength)}) {
                auto bytes = env->NewByteArray(value.size());
                if (!bytes) return;
                env->SetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<const jbyte*>(value.data()));
                auto item = env->NewObject(stringType, constructor, bytes, charset);
                env->DeleteLocalRef(bytes);
                if (!item) return;
                env->SetObjectArrayElement(result, index++, item);
                env->DeleteLocalRef(item);
            }
        }
        env->DeleteLocalRef(charset);
        env->DeleteLocalRef(stringType);
    });
    return result;
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(pause)(JNIEnv* env, jobject, jstring id) {
    guarded(env, [&] { requireSession(); checked(aria2::pauseDownload(session, aria2::hexToGid(text(env, id)), true)); });
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(resume)(JNIEnv* env, jobject, jstring id) {
    guarded(env, [&] { requireSession(); checked(aria2::unpauseDownload(session, aria2::hexToGid(text(env, id)))); });
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(remove)(JNIEnv* env, jobject, jstring id) {
    guarded(env, [&] { requireSession(); checked(aria2::removeDownload(session, aria2::hexToGid(text(env, id)), true)); });
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(update)(JNIEnv* env, jobject, jstring id, jobjectArray options) {
    guarded(env, [&] { requireSession(); checked(aria2::changeOption(session, aria2::hexToGid(text(env, id)), pairs(env, options))); });
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(tick)(JNIEnv* env, jobject) {
    guarded(env, [&] { requireSession(); checked(aria2::run(session, aria2::RUN_ONCE)); });
}
extern "C" JNIEXPORT jlongArray JNICALL JNI_METHOD(snapshot)(JNIEnv* env, jobject, jstring id) {
    jlongArray result = nullptr;
    guarded(env, [&] {
        requireSession();
        std::unique_ptr<aria2::DownloadHandle, decltype(&aria2::deleteDownloadHandle)> handle(
            aria2::getDownloadHandle(session, aria2::hexToGid(text(env, id))), aria2::deleteDownloadHandle);
        if (!handle) throw std::runtime_error("unknown task");
        const jlong data[] = {handle->getStatus(), handle->getCompletedLength(),
            handle->getTotalLength(), handle->getDownloadSpeed(), handle->getErrorCode()};
        result = env->NewLongArray(5);
        if (result) env->SetLongArrayRegion(result, 0, 5, data);
    });
    return result;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(shutdown)(JNIEnv* env, jobject) {
    guarded(env, [&] {
        requireSession();
        std::exception_ptr error;
        try {
            checked(aria2::shutdown(session, true));
            int status;
            do { status = aria2::run(session, aria2::RUN_ONCE); } while (status == 1);
            checked(status);
        } catch (...) { error = std::current_exception(); }
        aria2::sessionFinal(session);
        session = nullptr;
        owner = {};
        if (error) std::rethrow_exception(error);
    });
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    std::lock_guard<std::mutex> lock(gate);
    if (initialized && !session) {
        aria2::libraryDeinit();
        initialized = false;
    }
}
