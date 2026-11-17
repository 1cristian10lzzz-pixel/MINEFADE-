#pragma once

#include <mutex>
#include <string>

#include <soloud.h>
#include <soloud_wavstream.h>

namespace minefade {

// Independent long-form soundtrack channel; short gameplay/UI SFX keep using
// the existing Android SoundPool implementation.
class MusicPlayer {
public:
    MusicPlayer() = default;
    ~MusicPlayer();

    MusicPlayer(const MusicPlayer&) = delete;
    MusicPlayer& operator=(const MusicPlayer&) = delete;

    bool SetTrack(const std::string& path);
    void SetVolume(float volume);
    void SetEnabled(bool enabled);
    void Pause();
    void Resume();

private:
    bool EnsureInitialisedLocked();
    void ApplyVolumeLocked();

    std::mutex mutex_;
    SoLoud::Soloud soloud_;
    SoLoud::WavStream track_;
    SoLoud::handle voice_{0};
    std::string trackPath_;
    float volume_{0.46f};
    bool enabled_{true};
    bool initialised_{false};
    bool loaded_{false};
    bool paused_{false};
};

MusicPlayer& GetMusicPlayer();

} // namespace minefade
