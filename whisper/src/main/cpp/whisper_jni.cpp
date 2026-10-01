// whisper_jni.cpp — JNI bridge between
// com.timestampbeatstudio.whisper.WhisperNative and whisper.cpp (v1.7.4).
//
// Threading: nativeTranscribe is blocking and must be called from a worker
// thread. The progress callback is invoked on that same thread after every
// decoded segment. The native whisper_context is NOT thread-safe for
// concurrent use — the Kotlin layer serializes access with a Mutex.
//
// Error policy: every failure path returns 0 / an empty array. This file
// never throws across the JNI boundary and never dereferences null.

#include <jni.h>

#include <string>
#include <vector>

#include "whisper.h"

namespace {

// whisper.cpp token timestamps (t0/t1) are in 10 ms units.
constexpr double kTimestampUnitSec = 0.01;

struct JniWord {
    std::string text;
    double start_sec = 0.0;
    double end_sec = 0.0;
    double confidence = -1.0; // mean token probability in [0,1]; -1.0 = unknown
};

bool starts_with(const std::string & s, const char * prefix) {
    const size_t n = std::char_traits<char>::length(prefix);
    return s.size() >= n && s.compare(0, n, prefix) == 0;
}

std::string trim(const std::string & s) {
    static const char * ws = " \t\n\r";
    const size_t b = s.find_first_not_of(ws);
    if (b == std::string::npos) {
        return "";
    }
    const size_t e = s.find_last_not_of(ws);
    return s.substr(b, e - b + 1);
}

// Empty NativeWord[] for error paths. Returns nullptr only if the
// NativeWord class itself cannot be found (unreachable in a correct build).
jobjectArray new_empty_word_array(JNIEnv * env) {
    jclass word_cls = env->FindClass("com/timestampbeatstudio/whisper/NativeWord");
    if (word_cls == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    return env->NewObjectArray(0, word_cls, nullptr);
}

} // namespace

// ---------------------------------------------------------------------------
// nativeInit(modelPath: String): Long
// Returns the native whisper_context* as a jlong, or 0 on failure.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_timestampbeatstudio_whisper_WhisperNative_nativeInit(
        JNIEnv * env, jobject /*thiz*/, jstring model_path) {
    if (model_path == nullptr) {
        return 0;
    }
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0; // OOM
    }
    // whisper_init_from_file is deprecated in v1.7.4; use the _with_params form.
    whisper_context * ctx =
        whisper_init_from_file_with_params(path, whisper_context_default_params());
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx); // 0 (null) on failure
}

// ---------------------------------------------------------------------------
// nativeTranscribe(ctx, samples, numThreads, progressCallback): Array<NativeWord>
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_timestampbeatstudio_whisper_WhisperNative_nativeTranscribe(
        JNIEnv * env, jobject /*thiz*/,
        jlong ctx_ptr,
        jfloatArray samples,
        jint num_threads,
        jobject progress_callback) {
    whisper_context * ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx == nullptr || samples == nullptr) {
        return new_empty_word_array(env);
    }

    const jsize n_samples = env->GetArrayLength(samples);
    if (n_samples <= 0) {
        return new_empty_word_array(env);
    }

    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) {
        return new_empty_word_array(env);
    }

    // --- decode ------------------------------------------------------------
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress   = false;
    wparams.single_segment   = false;
    wparams.n_threads        = num_threads > 0 ? num_threads : 4;
    wparams.language         = "en";   // this module ships .en models only
    wparams.token_timestamps = true;   // per-token t0/t1 (10 ms units)
    wparams.thold_pt         = 0.01f;  // timestamp token probability threshold
    wparams.thold_ptsum      = 0.01f;

    const int rc = whisper_full(ctx, wparams, pcm, n_samples);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (rc != 0) {
        return new_empty_word_array(env);
    }

    // --- progress callback ---------------------------------------------------
    // Java signature: boolean onProgress(float progress) — return true to cancel.
    jmethodID on_progress = nullptr;
    if (progress_callback != nullptr) {
        jclass cb_cls = env->GetObjectClass(progress_callback);
        if (cb_cls != nullptr) {
            on_progress = env->GetMethodID(cb_cls, "onProgress", "(F)Z");
            if (on_progress == nullptr) {
                env->ExceptionClear(); // no such method: continue without callbacks
            }
        } else {
            env->ExceptionClear();
        }
    }

    jclass word_cls = env->FindClass("com/timestampbeatstudio/whisper/NativeWord");
    if (word_cls == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    // NativeWord(String text, double startSec, double endSec, double confidence)
    jmethodID word_ctor = env->GetMethodID(word_cls, "<init>", "(Ljava/lang/String;DDD)V");
    if (word_ctor == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }

    // --- collect words -------------------------------------------------------
    // whisper.cpp token text uses a leading space to mark a new word
    // (e.g. "Hello", " world", "!" -> words "Hello", "world", "!").
    // Tokens with id >= whisper_token_eot() are special tokens
    // (<|startoftranscript|>, <|0.00|>, <|endoftext|>, ...) and are skipped.
    const whisper_token eot = whisper_token_eot(ctx);

    std::vector<JniWord> words;
    words.reserve(4096);

    const int n_segments = whisper_full_n_segments(ctx);
    bool cancelled = false;

    for (int i = 0; i < n_segments && !cancelled; ++i) {
        const int n_tokens = whisper_full_n_tokens(ctx, i);

        std::string cur;
        double cur_start = 0.0;
        double cur_end = 0.0;
        double prob_sum = 0.0;
        int prob_count = 0;
        bool word_open = false;

        const auto flush_word = [&]() {
            const std::string text = trim(cur);
            if (!text.empty()) {
                JniWord w;
                w.text = text;
                w.start_sec = cur_start;
                w.end_sec = cur_end;
                w.confidence = prob_count > 0 ? (prob_sum / prob_count) : -1.0;
                words.push_back(std::move(w));
            }
            cur.clear();
            cur_start = 0.0;
            cur_end = 0.0;
            prob_sum = 0.0;
            prob_count = 0;
            word_open = false;
        };

        for (int j = 0; j < n_tokens; ++j) {
            const whisper_token id = whisper_full_get_token_id(ctx, i, j);
            if (id >= eot) {
                continue; // special token
            }
            const char * raw = whisper_full_get_token_text(ctx, i, j);
            if (raw == nullptr || raw[0] == '\0') {
                continue;
            }
            std::string piece(raw);
            if (starts_with(piece, "<|")) {
                continue; // belt & braces: never leak special tokens into words
            }
            if (!piece.empty() && piece[0] == ' ') {
                flush_word(); // leading space => word boundary
                const size_t k = piece.find_first_not_of(' ');
                piece = (k == std::string::npos) ? "" : piece.substr(k);
            }
            if (piece.empty()) {
                continue;
            }

            const whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            const double t0 = td.t0 * kTimestampUnitSec;
            const double t1 = td.t1 * kTimestampUnitSec;

            if (!word_open) {
                word_open = true;
                cur_start = t0;
            }
            cur_end = t1;
            cur += piece;
            prob_sum += static_cast<double>(whisper_full_get_token_p(ctx, i, j));
            ++prob_count;
        }
        flush_word();

        if (on_progress != nullptr && progress_callback != nullptr) {
            const float p = n_segments > 0
                ? static_cast<float>(i + 1) / static_cast<float>(n_segments)
                : 1.0f;
            const jboolean cancel = env->CallBooleanMethod(progress_callback, on_progress, p);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                cancelled = true; // Java side threw: treat as cancel
            } else if (cancel == JNI_TRUE) {
                cancelled = true;
            }
        }
    }

    // --- build NativeWord[] ----------------------------------------------------
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(words.size()), word_cls, nullptr);
    if (out == nullptr) {
        return nullptr; // OOM
    }
    for (size_t k = 0; k < words.size(); ++k) {
        jstring jtext = env->NewStringUTF(words[k].text.c_str());
        if (jtext == nullptr) {
            continue; // OOM on this element: leave it null rather than crashing
        }
        jobject wobj = env->NewObject(word_cls, word_ctor, jtext,
                                      words[k].start_sec,
                                      words[k].end_sec,
                                      words[k].confidence);
        env->DeleteLocalRef(jtext);
        if (wobj == nullptr) {
            continue;
        }
        env->SetObjectArrayElement(out, static_cast<jsize>(k), wobj);
        env->DeleteLocalRef(wobj);
    }
    return out;
}

// ---------------------------------------------------------------------------
// nativeFree(ctx: Long)
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_timestampbeatstudio_whisper_WhisperNative_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr) {
    whisper_context * ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
