// Times the phone's whisper.cpp readings on a desktop, with the app's own parameters (omawhisper-params.h):
//   bench MODEL MODE FILE.f32...   (MODE live, final, or an audio_ctx; FILE = 16 kHz mono float32 raw)
// Prints, per file: seconds of audio, audio_ctx used, milliseconds taken, and the text.
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>
#include "whisper.h"
#include "omawhisper-params.h"

static void quiet(ggml_log_level, const char *, void *) {}

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: bench MODEL MODE FILE.f32...\n"); return 2; }
    whisper_log_set(quiet, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context *ctx = whisper_init_from_file_with_params(argv[1], cp);
    if (!ctx) return 1;
    const char *prompt = getenv("OMA_PROMPT");
    int threads = getenv("OMA_THREADS") ? atoi(getenv("OMA_THREADS")) : 4;
    for (int i = 3; i < argc; i++) {
        FILE *f = fopen(argv[i], "rb");
        if (!f) continue;
        std::vector<float> pcm;
        float buf[4096];
        size_t n;
        while ((n = fread(buf, sizeof(float), 4096, f)) > 0) pcm.insert(pcm.end(), buf, buf + n);
        fclose(f);
        // MODE: "final" (the app's final reading), "live" (its live readings), or a fixed audio_ctx in frames.
        std::string mode = argv[2];
        whisper_full_params wp = oma_params((int) pcm.size(), threads, prompt, mode == "live");
        if (mode != "live" && mode != "final") wp.audio_ctx = atoi(argv[2]);
        auto t0 = std::chrono::steady_clock::now();
        int rc = whisper_full(ctx, wp, pcm.data(), (int) pcm.size());
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::string text;
        if (rc == 0)
            for (int s = 0; s < whisper_full_n_segments(ctx); s++)
                text += "[" + std::to_string(whisper_full_get_segment_t0(ctx, s)) + "-" +
                        std::to_string(whisper_full_get_segment_t1(ctx, s)) + "]" + whisper_full_get_segment_text(ctx, s);
        printf("%s\t%.2f\t%d\t%.0f\t%s\n", argv[i], pcm.size() / 16000.0, wp.audio_ctx, ms, text.c_str());
        fflush(stdout);
    }
    whisper_free(ctx);
}
