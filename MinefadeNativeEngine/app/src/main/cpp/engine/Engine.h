#pragma once

#include "../game/Player.h"
#include "../ui/RmlUiRuntime.h"
#include "../world/VoxelWorld.h"

#include <GLES3/gl3.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace minefade {

class Engine {
public:
    bool Initialise(float displayDensity, const std::uint8_t* atlasPixels, std::size_t atlasBytes, int atlasWidth, int atlasHeight);
    void Shutdown();
    void Resize(int width, int height);
    void Render();
    void Touch(int action, int pointerId, float x, float y);
    void Pause();
    void Resume();
    void SetStoragePath(std::string path);
    bool ConsumeExitRequest();
    std::uint32_t ConsumeSoundEvents();

private:
    struct Mat4 { std::array<float, 16> v{}; };
    struct TouchState {
        float startX{0.0f};
        float startY{0.0f};
        float lastX{0.0f};
        float lastY{0.0f};
        double startedAt{0.0};
        bool uiHandled{false};
        bool joystick{false};
        bool moved{false};
        bool mining{false};
    };

    static Mat4 Perspective(float fovRadians, float aspect, float nearPlane, float farPlane);
    static Mat4 LookAt(const Vec3& eye, const Vec3& target, const Vec3& up);
    static Mat4 Multiply(const Mat4& a, const Mat4& b);
    static GLuint CompileShader(GLenum type, const char* source);
    bool CreateWorldProgram();
    bool UploadBlockAtlas(const std::uint8_t* pixels, std::size_t bytes, int width, int height);
    void RebuildWorldMesh();
    void UploadWorldMesh();
    void UploadTransparentWorldMesh();
    void RebuildEntityMesh();
    void UploadEntityMesh();
    void HandleUiAction(const std::string& action);
    void SaveWorld();
    void RefreshInventoryUi();
    void UpdateMining(float deltaSeconds);
    double ElapsedSeconds() const;

    GLuint worldProgram_{0};
    GLuint worldVao_{0};
    GLuint worldVbo_{0};
    GLint mvpLocation_{-1};
    GLint lightLocation_{-1};
    GLint daylightLocation_{-1};
    GLint atlasLocation_{-1};
    GLint useAtlasLocation_{-1};
    GLuint blockAtlasTexture_{0};
    GLuint entityVao_{0};
    GLuint entityVbo_{0};
    GLuint transparentVao_{0};
    GLuint transparentVbo_{0};
    GLsizei worldVertexCount_{0};
    GLsizei transparentVertexCount_{0};
    GLsizei entityVertexCount_{0};
    int lastChunkX_{999999};
    int lastChunkZ_{999999};
    int width_{1};
    int height_{1};
    bool ready_{false};
    bool paused_{false};
    bool inventoryVisible_{false};
    bool pauseVisible_{false};
    std::string storagePath_;
    VoxelWorld world_;
    Player player_;
    Inventory inventory_;
    DroppedItemSystem droppedItems_;
    EntitySystem entities_;
    EnvironmentSystem environment_;
    RmlUiRuntime ui_;
    std::vector<WorldVertex> worldMesh_;
    std::vector<WorldVertex> transparentWorldMesh_;
    std::vector<WorldVertex> entityMesh_;
    std::unordered_map<int, TouchState> touches_;
    int miningPointerId_{-1};
    Vec3 miningBlock_{};
    BlockId miningBlockType_{BlockId::Air};
    float miningElapsed_{0.0f};
    float miningRequired_{0.0f};
    std::string pickupToastText_;
    std::chrono::steady_clock::time_point pickupToastUntil_{};
    std::chrono::steady_clock::time_point lastFrame_{};
    std::chrono::steady_clock::time_point lastStats_{};
    int framesSinceStats_{0};
    float fps_{0.0f};
    std::atomic_bool exitRequested_{false};
    std::atomic<std::uint32_t> soundEvents_{0};
};

Engine& GetEngine();

} // namespace minefade
