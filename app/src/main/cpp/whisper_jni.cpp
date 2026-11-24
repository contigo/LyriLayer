#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <string>
#include <cstdio>
#include <vector>

extern "C" JNIEXPORT jlong JNICALL
Java_com_chaoseye_lyrilayer_NativeWhisper_open(JNIEnv *e, jobject, jstring path) {
    const char *p = e->GetStringUTFChars(path, nullptr);
    if (!p) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *ctx = whisper_init_from_file_with_params(p, params);
    e->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_chaoseye_lyrilayer_NativeWhisper_decode(JNIEnv *e, jobject, jlong handle,
    jfloatArray pcm, jint threads, jstring language, jlong offsetMs) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    const int size = e->GetArrayLength(pcm);
    if (!ctx || size <= 0 || size > 480000) return nullptr;
    std::vector<float> samples(size);
    e->GetFloatArrayRegion(pcm, 0, size, samples.data());
    if (e->ExceptionCheck()) return nullptr;
    const char *lang = e->GetStringUTFChars(language, nullptr);
    if (!lang) return nullptr;
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, std::min(4, (int) threads));
    params.language = lang;
    params.translate = false;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    const int rc = whisper_full(ctx, params, samples.data(), size);
    e->ReleaseStringUTFChars(language, lang);
    if (rc != 0) return nullptr;
    std::string out;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) {
        long long ms = (long long) offsetMs + 10LL * whisper_full_get_segment_t0(ctx, i);
        if (ms < offsetMs || ms >= offsetMs + (long long)size * 1000 / 16000) continue;
        const char *raw = whisper_full_get_segment_text(ctx, i);
        if (!raw) continue;
        std::string text(raw);
        const auto begin = text.find_first_not_of(" \t\r\n");
        if (begin == std::string::npos) continue;
        text.erase(0, begin);
        std::replace(text.begin(), text.end(), '\n', ' ');
        std::replace(text.begin(), text.end(), '\r', ' ');
        char stamp[64];
        std::snprintf(stamp, sizeof(stamp), "[%02lld:%02lld.%02lld]", ms / 60000, (ms / 1000) % 60, (ms / 10) % 100);
        out += stamp; out += text; out += "\n";
    }
    auto result = e->NewByteArray(static_cast<jsize>(out.size()));
    if (result && !out.empty()) e->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_chaoseye_lyrilayer_NativeWhisper_close(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
