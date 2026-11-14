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
    std::string LastInitError() const;
    void SetLastInitError(std::string message);
    void Shutdown();
    void Resize(int width, int height);
    void Render();
    void Touch(int action, int pointerId, float x, float y);
    void Pause();
    void Resume();
    void SetStoragePath(std::string path);
    void SetWorldOptions(int gameMode, bool allowCheats, bool starterChest, std::uint32_t seed);
    void SetModsDirectory(std::string path);
    std::string ExecuteCommand(const std::string& command);
    bool ConsumeExitRequest();
    std::uint32_t ConsumeSoundEvents();
    void SetMultiplayerMode(bool enabled, int playerSlot);
    void SetGraphicsOptions(int quality, int renderDistanceChunks, bool waterWaves, float fieldOfView, bool contactShadows);
    void SetInputOptions(float sensitivityX, float sensitivityY, float joystickDeadzone, bool invertLookY, bool leftHanded);
    void SetHudOptions(bool showStats);
    void SetAnimationOptions(int quality, bool enabled);
    void SetAvatarAppearance(const int* values, std::size_t count);
    void GetPlayerState(float output[4]) const;
    void SetRemotePlayers(const float* values, std::size_t floatCount);
    void ApplyRemoteBlockEdits(const int* values, std::size_t valueCount);
    std::vector<int> ConsumeLocalBlockEdits();

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
    static CameraFrustum ExtractFrustum(const Mat4& mvp);
    static GLuint CompileShader(GLenum type, const char* source);
    bool CreateWorldProgram();
    bool UploadBlockAtlas(const std::uint8_t* pixels, std::size_t bytes, int width, int height);
    void RebuildWorldMesh();
    void UploadWorldMesh();
    void UploadTransparentWorldMesh();
    void RebuildEntityMesh();
    void UploadEntityMesh();
    void UploadItemMesh();
    void HandleUiAction(const std::string& action);
    void SaveWorld();
    void LoadEnabledMods();
    void OpenStarterChest();
    void TakeStarterChestContents();
    void RefreshInventoryUi();
    void UpdateMining(float deltaSeconds);
    Vec3 ViewPosition() const;
    Vec3 ViewTarget() const;
    Vec3 ViewDirection() const;
    double ElapsedSeconds() const;

    GLuint worldProgram_{0};
    GLuint worldVao_{0};
    GLuint worldVbo_{0};
    GLint mvpLocation_{-1};
    GLint lightLocation_{-1};
    GLint daylightLocation_{-1};
    GLint atlasLocation_{-1};
    GLint useAtlasLocation_{-1};
    GLint timeLocation_{-1};
    GLint qualityLocation_{-1};
    GLint waterWavesLocation_{-1};
    GLint cameraLocation_{-1};
    GLint fogColorLocation_{-1};
    GLint fogNearLocation_{-1};
    GLint fogFarLocation_{-1};
    GLuint blockAtlasTexture_{0};
    GLuint entityVao_{0};
    GLuint entityVbo_{0};
    GLuint transparentVao_{0};
    GLuint transparentVbo_{0};
    GLuint itemVao_{0};
    GLuint itemVbo_{0};
    GLsizei worldVertexCount_{0};
    GLsizei transparentVertexCount_{0};
    GLsizei entityVertexCount_{0};
    GLsizei itemVertexCount_{0};
    int lastChunkX_{999999};
    int lastChunkZ_{999999};
    int width_{1};
    int height_{1};
    bool ready_{false};
    bool paused_{false};
    bool multiplayerMode_{false};
    int multiplayerSlot_{0};
    bool pendingMultiplayerMode_{false};
    int pendingMultiplayerSlot_{0};
    int graphicsQuality_{1};
    int pendingGraphicsQuality_{1};
    int renderDistanceChunks_{2};
    int pendingRenderDistanceChunks_{2};
    bool waterWaves_{true};
    bool pendingWaterWaves_{true};
    float fieldOfView_{68.0f};
    float pendingFieldOfView_{68.0f};
    bool contactShadows_{true};
    bool pendingContactShadows_{true};
    float lookSensitivityX_{1.0f};
    float pendingLookSensitivityX_{1.0f};
    float lookSensitivityY_{1.0f};
    float pendingLookSensitivityY_{1.0f};
    bool invertLookY_{false};
    bool pendingInvertLookY_{false};
    bool leftHandedControls_{false};
    bool pendingLeftHandedControls_{false};
    float joystickDeadzone_{0.12f};
    float pendingJoystickDeadzone_{0.12f};
    bool showHudStats_{true};
    bool pendingShowHudStats_{true};
    int animationQuality_{2};
    int pendingAnimationQuality_{2};
    bool animationsEnabled_{true};
    bool pendingAnimationsEnabled_{true};
    std::array<int, 6> avatarAppearance_{{0,0,0,0,0,0}};
    bool inventoryVisible_{false};
    bool pauseVisible_{false};
    bool thirdPerson_{false};
    bool crouching_{false};
    int gameMode_{0};
    bool allowCheats_{false};
    bool starterChestEnabled_{false};
    bool starterChestOpen_{false};
    Vec3 starterChestPosition_{};
    std::uint32_t requestedWorldSeed_{0x4D464445u};
    std::string modsDirectory_;
    int modSheepSpawns_{0};
    int modCowSpawns_{0};
    int modBirdSpawns_{0};
    float modPlayerSpeed_{1.0f};
    float modDaySpeed_{1.0f};
    std::array<std::uint32_t, 9> starterChestContents_{{
        1064u, 5064u, 64016u, 6004u, 3064u, 0u, 0u, 0u, 0u
    }};
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
    std::vector<ChunkMeshRange> worldChunkRanges_;
    std::vector<WorldVertex> entityMesh_;
    std::vector<WorldVertex> itemMesh_;
    std::vector<float> remotePlayers_;
    std::vector<int> pendingBlockEdits_;
    std::unordered_map<int, TouchState> touches_;
    int miningPointerId_{-1};
    Vec3 miningBlock_{};
    BlockId miningBlockType_{BlockId::Air};
    float miningElapsed_{0.0f};
    float miningRequired_{0.0f};
    float footstepElapsed_{0.0f};
    float entityMeshAccumulator_{0.0f};
    float itemMeshAccumulator_{0.0f};
    std::string pickupToastText_;
    std::chrono::steady_clock::time_point pickupToastUntil_{};
    std::chrono::steady_clock::time_point lastFrame_{};
    std::chrono::steady_clock::time_point lastStats_{};
    int framesSinceStats_{0};
    float fps_{0.0f};
    std::atomic_bool exitRequested_{false};
    std::atomic<std::uint32_t> soundEvents_{0};
    // Kept after GL cleanup so Java can show the exact native startup failure.
    std::string lastInitError_;
};

Engine& GetEngine();

} // namespace minefade
