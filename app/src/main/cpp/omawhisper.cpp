// The bridge between Oma's voice input (voice/Whisper.kt) and whisper.cpp: load a model, turn 16 kHz mono
// samples into text, free the model. Whisper runs on the phone's CPU; nothing leaves the phone.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "omawhisper"

static void quiet(ggml_log_level, const char *, void *) {}

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

    // Greedy for the final reading too: beam search was a second too slow after he stops (Boss, 2026-10-15).
    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = threads;
    wp.language = "en";
    wp.translate = false;
    wp.no_context = true;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.suppress_blank = true;
    wp.suppress_nst = true;
    wp.temperature_inc = live ? 0.0f : 0.2f;
    // Short audio needs no 30 s window: the encoder is told to look at what there is (as whisper.cpp's stream does).
    wp.audio_ctx = 0;
    const char *pr = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    wp.initial_prompt = pr;

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
