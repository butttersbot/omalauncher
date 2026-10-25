// The bridge between Oma's voice input (voice/Whisper.kt) and whisper.cpp: load a model, turn 16 kHz mono
// samples into text, free the model. Whisper runs on the phone's CPU; nothing leaves the phone.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"
#include "omawhisper-params.h"

#define TAG "omawhisper"

static void quiet(ggml_log_level, const char *, void *) {}

// Set when a dictation ends while a live reading is under way: the accurate reading of the whole of it supersedes
// that one, so it stops at whisper.cpp's next check instead of holding the model for the rest of its run.
static std::atomic<bool> stop_live{false};

static bool live_stopped(void *) { return stop_live.load(); }

extern "C" JNIEXPORT void JNICALL
Java_dev_omalauncher_voice_WhisperNative_stopLive(JNIEnv *, jobject, jboolean stop) {
    stop_live.store(stop);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_omalauncher_voice_WhisperNative_load(JNIEnv *env, jobject, jstring path) {
    whisper_log_set(quiet, nullptr);
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    if (!ctx) __android_log_print(ANDROID_LOG_WARN, TAG, "could not load the model");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_omalauncher_voice_WhisperNative_free(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

// The text of [samples]. Each segment comes back as "t0 t1 text" on a line of its own (centiseconds), so the
// live view can tell where in the audio the words it keeps end.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_omalauncher_voice_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray samples,
                                                    jstring prompt, jint threads, jboolean live) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    const char *pr = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    whisper_full_params wp = oma_params((int) pcm.size(), threads, pr, live);
    if (live) {
        wp.abort_callback = live_stopped;
        wp.abort_callback_user_data = nullptr;
    }

    std::string out;
    if (whisper_full(ctx, wp, pcm.data(), (int) pcm.size()) == 0) {
        int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; i++) {
            out += std::to_string(whisper_full_get_segment_t0(ctx, i)) + " " +
                   std::to_string(whisper_full_get_segment_t1(ctx, i)) + " " +
                   whisper_full_get_segment_text(ctx, i) + "\n";
        }
    }
    if (pr) env->ReleaseStringUTFChars(prompt, pr);
    return env->NewStringUTF(out.c_str());
}
