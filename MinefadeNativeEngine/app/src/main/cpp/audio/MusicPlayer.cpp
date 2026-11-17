#include "MusicPlayer.h"

#include <algorithm>

#include <android/log.h>

namespace minefade {
namespace {
constexpr const char* kTag = "MinefadeMusic";
}

MusicPlayer::~MusicPlayer() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (initialised_) {
        soloud_.stopAll();
        soloud_.deinit();
        initialised_ = false;
    }
}

bool MusicPlayer::EnsureInitialisedLocked() {
    if (initialised_) return true;
    const SoLoud::result result = soloud_.init(
        SoLoud::Soloud::CLIP_ROUNDOFF,
        SoLoud::Soloud::OPENSLES,
        44100,
        2048,
        2
    );
    if (result != SoLoud::SO_NO_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "SoLoud OpenSL ES initialisation failed: %u",
                            static_cast<unsigned int>(result));
        return false;
    }
    initialised_ = true;
    ApplyVolumeLocked();
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "SoLoud music channel ready (OpenSL ES, 44.1 kHz)");
    return true;
}

void MusicPlayer::ApplyVolumeLocked() {
    if (!initialised_) return;
    soloud_.setGlobalVolume(enabled_ ? volume_ : 0.0f);
}

bool MusicPlayer::SetTrack(const std::string& path) {
    if (path.empty()) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (loaded_ && trackPath_ == path) {
        ApplyVolumeLocked();
        return true;
    }
    if (!EnsureInitialisedLocked()) return false;

    soloud_.stopAll();
    voice_ = 0;
    loaded_ = false;
    const SoLoud::result loadResult = track_.load(path.c_str());
    if (loadResult != SoLoud::SO_NO_ERROR) {
        trackPath_.clear();
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "Unable to load local soundtrack '%s' (SoLoud error %u)",
                            path.c_str(), static_cast<unsigned int>(loadResult));
        return false;
    }

    // The bundled WAV is streamed locally from app-private storage. There is
    // no radio, streaming, network request, or static-noise fallback.
    voice_ = soloud_.play(track_, 1.0f);
    if (voice_ == 0) {
        trackPath_.clear();
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "SoLoud could not start the soundtrack voice");
        return false;
    }
    soloud_.setLooping(voice_, true);
    soloud_.setVolume(voice_, 0.0f);
    soloud_.fadeVolume(voice_, 1.0f, 1.8);
    trackPath_ = path;
    loaded_ = true;
    ApplyVolumeLocked();
    if (paused_) soloud_.setPauseAll(true);
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Local ambient soundtrack loaded and looping");
    return true;
}

void MusicPlayer::SetVolume(float volume) {
    std::lock_guard<std::mutex> lock(mutex_);
    volume_ = std::clamp(volume, 0.0f, 1.0f);
    ApplyVolumeLocked();
}

void MusicPlayer::SetEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    enabled_ = enabled;
    ApplyVolumeLocked();
}

void MusicPlayer::Pause() {
    std::lock_guard<std::mutex> lock(mutex_);
    paused_ = true;
    if (initialised_) soloud_.setPauseAll(true);
}

void MusicPlayer::Resume() {
    std::lock_guard<std::mutex> lock(mutex_);
    paused_ = false;
    if (initialised_) soloud_.setPauseAll(false);
}

MusicPlayer& GetMusicPlayer() {
    static MusicPlayer player;
    return player;
}

} // namespace minefade
