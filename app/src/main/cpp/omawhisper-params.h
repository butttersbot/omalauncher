// How Oma asks whisper.cpp to read a clip: shared by the JNI bridge (omawhisper.cpp) and the desktop bench
// (tools/whisper-bench), so what is measured is what ships.
#pragma once
#include "whisper.h"

// The encoder's window, in frames (50 a second; whisper's full window is 1500, 30 s). Left at 0, whisper.cpp
// reads all 30 s however short the clip is. A window cut to the clip is faster but less accurate: on 73 real
// speech clips (LibriSpeech, 2026-10-24) every cut tried raised the word error rate, small.en's from 6% to 15-30%
// and base.en's from 10% to 13-21%, with some readings coming back empty or cut short. So only the live readings,
// which the whole reading replaces when he stops, use a cut window: twice the clip, plus 64 frames, never under
// 256 (base.en: 13.4% instead of 9.7%, 1.7x faster, 4x on clips under 4 s). The final reading keeps all 30 s.
#define OMA_AUDIO_CTX_MIN 256
#define OMA_AUDIO_CTX_PAD 64

static inline int oma_audio_ctx(int n_samples) {
    const int full = 1500;
    int ctx = n_samples / 320 * 2 + OMA_AUDIO_CTX_PAD;
    if (ctx < OMA_AUDIO_CTX_MIN) ctx = OMA_AUDIO_CTX_MIN;
    // 0 is whisper.cpp's "the whole window": for a clip of 15 s or more.
    return ctx >= full ? 0 : ctx;
}

static inline whisper_full_params oma_params(int n_samples, int threads, const char *prompt, bool live) {
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
    // A reading that loops on a word ("and, and, and…") is read again warmer, live too: whisper.cpp tells a loop by
    // how little its text compresses (entropy_thold).
    wp.temperature_inc = 0.2f;
    wp.audio_ctx = live ? oma_audio_ctx(n_samples) : 0;
    wp.initial_prompt = prompt;
    return wp;
}
