#include "Engine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <string>
#include <utility>

namespace minefade {
namespace {
constexpr const char* kTag = "MinefadeNative";
constexpr float kPi = 3.14159265358979323846f;
#define ENGINE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define ENGINE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

constexpr char kWorldVertexShader[] = R"(
#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec3 aColor;
layout(location=3) in vec2 aTexCoord;
uniform mat4 uMvp;
uniform vec3 uLightDirection;
uniform float uDaylight;
out mediump vec3 vColor;
out mediump vec2 vTexCoord;
void main() {
    gl_Position = uMvp * vec4(aPosition, 1.0);
    float diffuse = max(dot(normalize(aNormal), normalize(-uLightDirection)), 0.0);
    float ambient = mix(0.22, 0.38, uDaylight);
    float illumination = ambient + diffuse * (0.42 + 0.58 * uDaylight);
    vColor = aColor * illumination;
    vTexCoord = aTexCoord;
}
)";

constexpr char kWorldFragmentShader[] = R"(
#version 300 es
precision mediump float;
in mediump vec3 vColor;
in mediump vec2 vTexCoord;
uniform sampler2D uBlockAtlas;
uniform int uUseAtlas;
out vec4 outColor;
void main() {
    vec4 texel = (uUseAtlas == 1) ? texture(uBlockAtlas, vTexCoord) : vec4(1.0);
    outColor = vec4(texel.rgb * vColor, texel.a);
}
)";

Vec3 Add(const Vec3& a, const Vec3& b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }

} // namespace

Engine& GetEngine() {
    static Engine engine;
    return engine;
}

std::uint32_t Engine::ConsumeSoundEvents() {
    return soundEvents_.exchange(0u, std::memory_order_acq_rel);
}

Engine::Mat4 Engine::Perspective(float fovRadians, float aspect, float nearPlane, float farPlane) {
    const float safeAspect = std::max(aspect, 0.001f);
    const float f = 1.0f / std::tan(fovRadians * 0.5f);
    Mat4 result{};
    result.v[0] = f / safeAspect;
    result.v[5] = f;
    result.v[10] = (farPlane + nearPlane) / (nearPlane - farPlane);
    result.v[11] = -1.0f;
    result.v[14] = (2.0f * farPlane * nearPlane) / (nearPlane - farPlane);
    return result;
}

Engine::Mat4 Engine::LookAt(const Vec3& eye, const Vec3& target, const Vec3& up) {
    const auto normalize = [](const Vec3& value) {
        const float length = std::sqrt(std::max(value.x * value.x + value.y * value.y + value.z * value.z, 0.000001f));
        return Vec3{value.x / length, value.y / length, value.z / length};
    };
    const auto cross = [](const Vec3& a, const Vec3& b) {
        return Vec3{a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
    };
    const auto dot = [](const Vec3& a, const Vec3& b) { return a.x*b.x + a.y*b.y + a.z*b.z; };
    const Vec3 forward = normalize({target.x - eye.x, target.y - eye.y, target.z - eye.z});
    const Vec3 side = normalize(cross(forward, up));
    const Vec3 correctedUp = cross(side, forward);
    Mat4 result{};
    result.v[0] = side.x; result.v[1] = correctedUp.x; result.v[2] = -forward.x; result.v[3] = 0.0f;
    result.v[4] = side.y; result.v[5] = correctedUp.y; result.v[6] = -forward.y; result.v[7] = 0.0f;
    result.v[8] = side.z; result.v[9] = correctedUp.z; result.v[10] = -forward.z; result.v[11] = 0.0f;
    result.v[12] = -dot(side, eye); result.v[13] = -dot(correctedUp, eye); result.v[14] = dot(forward, eye); result.v[15] = 1.0f;
    return result;
}

Engine::Mat4 Engine::Multiply(const Mat4& a, const Mat4& b) {
    Mat4 result{};
    for (int column = 0; column < 4; ++column) {
        for (int row = 0; row < 4; ++row) {
            float value = 0.0f;
            for (int k = 0; k < 4; ++k)
                value += a.v[static_cast<std::size_t>(k * 4 + row)] * b.v[static_cast<std::size_t>(column * 4 + k)];
            result.v[static_cast<std::size_t>(column * 4 + row)] = value;
        }
    }
    return result;
}

GLuint Engine::CompileShader(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::string log(static_cast<std::size_t>(std::max(length, 1)), '\0');
        glGetShaderInfoLog(shader, length, nullptr, log.data());
        ENGINE_LOGE("World shader failed: %s", log.c_str());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool Engine::CreateWorldProgram() {
    const GLuint vertex = CompileShader(GL_VERTEX_SHADER, kWorldVertexShader);
    const GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, kWorldFragmentShader);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return false;
    }
    worldProgram_ = glCreateProgram();
    glAttachShader(worldProgram_, vertex);
    glAttachShader(worldProgram_, fragment);
    glLinkProgram(worldProgram_);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(worldProgram_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        GLint length = 0;
        glGetProgramiv(worldProgram_, GL_INFO_LOG_LENGTH, &length);
        std::string log(static_cast<std::size_t>(std::max(length, 1)), '\0');
        glGetProgramInfoLog(worldProgram_, length, nullptr, log.data());
        ENGINE_LOGE("World program failed: %s", log.c_str());
        glDeleteProgram(worldProgram_);
        worldProgram_ = 0;
        return false;
    }
    mvpLocation_ = glGetUniformLocation(worldProgram_, "uMvp");
    lightLocation_ = glGetUniformLocation(worldProgram_, "uLightDirection");
    daylightLocation_ = glGetUniformLocation(worldProgram_, "uDaylight");
    atlasLocation_ = glGetUniformLocation(worldProgram_, "uBlockAtlas");
    useAtlasLocation_ = glGetUniformLocation(worldProgram_, "uUseAtlas");
    if (mvpLocation_ < 0 || lightLocation_ < 0 || daylightLocation_ < 0 ||
        atlasLocation_ < 0 || useAtlasLocation_ < 0) return false;
    glUseProgram(worldProgram_);
    glUniform1i(atlasLocation_, 0);
    glUniform1i(useAtlasLocation_, 1);
    glUseProgram(0);
    return true;
}

bool Engine::UploadBlockAtlas(const std::uint8_t* pixels, std::size_t bytes, int width, int height) {
    if (pixels == nullptr || width <= 0 || height <= 0) return false;
    const std::size_t expected = static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 4u;
    if (bytes != expected) return false;
    glGenTextures(1, &blockAtlasTexture_);
    if (blockAtlasTexture_ == 0) return false;
    glBindTexture(GL_TEXTURE_2D, blockAtlasTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glBindTexture(GL_TEXTURE_2D, 0);
    GLint error = GL_NO_ERROR;
    error = glGetError();
    if (error != GL_NO_ERROR) {
        glDeleteTextures(1, &blockAtlasTexture_);
        blockAtlasTexture_ = 0;
        ENGINE_LOGE("Block atlas upload failed: GL error %d", error);
        return false;
    }
    return true;
}

bool Engine::Initialise(float displayDensity, const std::uint8_t* atlasPixels,
                        std::size_t atlasBytes, int atlasWidth, int atlasHeight) {
    // Preserve the previous world first (Shutdown saves it when appropriate), then
    // reset every gameplay subsystem before loading or creating the next session.
    // Without this reset, a failed/missing save could silently reuse stale chunks,
    // entities, drops, inventory and player state from the previous GL session.
    Shutdown();
    world_ = VoxelWorld(0x4D464445u);
    player_ = Player();
    inventory_ = Inventory();
    droppedItems_ = DroppedItemSystem();
    entities_ = EntitySystem();
    environment_ = EnvironmentSystem();
    remotePlayers_.clear();
    pendingBlockEdits_.clear();
    touches_.clear();
    inventoryVisible_ = false;
    pauseVisible_ = false;
    thirdPerson_ = false;
    miningPointerId_ = -1;
    miningElapsed_ = 0.0f;
    miningRequired_ = 0.0f;
    pickupToastText_.clear();
    exitRequested_.store(false, std::memory_order_release);
    soundEvents_.store(0u, std::memory_order_release);

    multiplayerMode_ = pendingMultiplayerMode_;
    multiplayerSlot_ = pendingMultiplayerSlot_;
    ENGINE_LOGI("Starting Minefade Native Engine C++20 / OpenGL ES 3");
    const auto* glVersion = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const auto* glRenderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    ENGINE_LOGI("OpenGL version=%s renderer=%s", glVersion != nullptr ? glVersion : "unknown",
                glRenderer != nullptr ? glRenderer : "unknown");

    const auto failInitialisation = [this]() {
        ENGINE_LOGE("World initialisation failed; releasing partial GL and UI state");
        Shutdown();
        return false;
    };

    if (!CreateWorldProgram()) return failInitialisation();
    if (!UploadBlockAtlas(atlasPixels, atlasBytes, atlasWidth, atlasHeight)) {
        ENGINE_LOGE("Minefade block atlas is missing or invalid");
        return failInitialisation();
    }
    glGenVertexArrays(1, &worldVao_);
    glGenBuffers(1, &worldVbo_);
    glGenVertexArrays(1, &entityVao_);
    glGenBuffers(1, &entityVbo_);
    glGenVertexArrays(1, &transparentVao_);
    glGenBuffers(1, &transparentVbo_);
    if (worldVao_ == 0 || worldVbo_ == 0 || entityVao_ == 0 || entityVbo_ == 0 ||
        transparentVao_ == 0 || transparentVbo_ == 0) return failInitialisation();

    if (multiplayerMode_) {
        // All LAN peers start from the same deterministic seed. The host's block
        // edits are then replicated as authoritative block-change events.
        world_ = VoxelWorld(0x4D464445u);
        world_.GenerateAround(8, 8, 1);
        const int spawnX = 8 + (multiplayerSlot_ % 4) * 2;
        const int spawnZ = 8 + ((multiplayerSlot_ / 4) % 4) * 2;
        player_.position = {static_cast<float>(spawnX),
                            static_cast<float>(world_.GetSurfaceHeight(spawnX, spawnZ)) + 1.05f,
                            static_cast<float>(spawnZ)};
    } else if (!storagePath_.empty()) {
        if (!world_.Load(storagePath_, player_.position, inventory_.counts)) {
            world_.GenerateAround(8, 8, 0);
            player_.position = {8.0f, static_cast<float>(world_.GetSurfaceHeight(8, 8)) + 1.05f, 8.0f};
        }
    } else {
        world_.GenerateAround(8, 8, 0);
        player_.position = {8.0f, static_cast<float>(world_.GetSurfaceHeight(8, 8)) + 1.05f, 8.0f};
    }
    world_.GenerateAround(static_cast<int>(std::floor(player_.position.x)),
                          static_cast<int>(std::floor(player_.position.z)), 1);
    if (!player_.ResolveSafeSpawn(world_)) {
        ENGINE_LOGE("Saved spawn is obstructed and no nearby safe position was found");
        world_.GenerateAround(8, 8, 0);
        player_.position = {8.0f, static_cast<float>(world_.GetSurfaceHeight(8, 8)) + 1.001f, 8.0f};
        if (!player_.ResolveSafeSpawn(world_)) {
            ENGINE_LOGE("Fresh spawn recovery failed; generated terrain has no safe player location");
            return failInitialisation();
        }
    }
    footstepElapsed_ = 0.0f;
    lastChunkX_ = static_cast<int>(std::floor(player_.position.x / 16.0f));
    lastChunkZ_ = static_cast<int>(std::floor(player_.position.z / 16.0f));
    entities_.SpawnInitial(world_);
    RebuildWorldMesh();
    UploadWorldMesh();
    UploadTransparentWorldMesh();
    RebuildEntityMesh();
    UploadEntityMesh();

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glFrontFace(GL_CCW);
    glDisable(GL_BLEND);
    glViewport(0, 0, width_, height_);

    if (!ui_.Initialise(width_, height_, displayDensity,
                        [this](const std::string& action) { HandleUiAction(action); })) {
        ENGINE_LOGE("RmlUi failed to initialise; game rendering remains available without the HUD");
    }
    lastFrame_ = std::chrono::steady_clock::now();
    lastStats_ = lastFrame_;
    ready_ = true;
    paused_ = false;
    thirdPerson_ = false;
    ui_.SetPerspectiveEnabled(false);
    RefreshInventoryUi();
    ENGINE_LOGI("World ready: %zu chunks, %zu vertices, seed=%u",
                world_.ChunkCount(), worldMesh_.size(), world_.Seed());
    // Commit the first generated world immediately. This makes the first launch
    // distinguishable from an empty menu and ensures the next launch can continue it.
    SaveWorld();
    return true;
}

void Engine::Shutdown() {
    if (ready_) SaveWorld();
    ui_.Shutdown();
    if (worldVbo_ != 0) glDeleteBuffers(1, &worldVbo_);
    if (worldVao_ != 0) glDeleteVertexArrays(1, &worldVao_);
    if (entityVbo_ != 0) glDeleteBuffers(1, &entityVbo_);
    if (entityVao_ != 0) glDeleteVertexArrays(1, &entityVao_);
    if (transparentVbo_ != 0) glDeleteBuffers(1, &transparentVbo_);
    if (transparentVao_ != 0) glDeleteVertexArrays(1, &transparentVao_);
    if (worldProgram_ != 0) glDeleteProgram(worldProgram_);
    if (blockAtlasTexture_ != 0) glDeleteTextures(1, &blockAtlasTexture_);
    worldVbo_ = worldVao_ = entityVbo_ = entityVao_ = transparentVbo_ = transparentVao_ = 0;
    worldProgram_ = blockAtlasTexture_ = 0;
    mvpLocation_ = lightLocation_ = daylightLocation_ = atlasLocation_ = useAtlasLocation_ = -1;
    worldVertexCount_ = transparentVertexCount_ = entityVertexCount_ = 0;
    ready_ = false;
    touches_.clear();
}

void Engine::Resize(int width, int height) {
    width_ = std::max(width, 1);
    height_ = std::max(height, 1);
    glViewport(0, 0, width_, height_);
    ui_.Resize(width_, height_);
}

void Engine::RebuildWorldMesh() {
    world_.BuildMesh(worldMesh_, static_cast<int>(std::floor(player_.position.x)),
                     static_cast<int>(std::floor(player_.position.z)), 1, &transparentWorldMesh_);

    // Alpha blending is order-dependent. Sort transparent triangles from far to near
    // so overlapping water/glass surfaces blend more consistently on mobile GPUs.
    if (transparentWorldMesh_.size() < 6u) return;
    const Vec3 eye = ViewPosition();
    using Triangle = std::array<WorldVertex, 3>;
    std::vector<std::pair<float, Triangle>> sorted;
    sorted.reserve(transparentWorldMesh_.size() / 3u);
    for (std::size_t i = 0; i + 2u < transparentWorldMesh_.size(); i += 3u) {
        Triangle triangle{transparentWorldMesh_[i], transparentWorldMesh_[i + 1u], transparentWorldMesh_[i + 2u]};
        const float cx = (triangle[0].x + triangle[1].x + triangle[2].x) / 3.0f - eye.x;
        const float cy = (triangle[0].y + triangle[1].y + triangle[2].y) / 3.0f - eye.y;
        const float cz = (triangle[0].z + triangle[1].z + triangle[2].z) / 3.0f - eye.z;
        sorted.emplace_back(cx * cx + cy * cy + cz * cz, triangle);
    }
    std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) {
        return a.first > b.first;
    });
    std::size_t cursor = 0;
    for (const auto& entry : sorted) {
        for (const WorldVertex& vertex : entry.second) transparentWorldMesh_[cursor++] = vertex;
    }
}

void Engine::UploadWorldMesh() {
    if (worldVao_ == 0 || worldVbo_ == 0) return;
    glBindVertexArray(worldVao_);
    glBindBuffer(GL_ARRAY_BUFFER, worldVbo_);
    glBufferData(GL_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(worldMesh_.size() * sizeof(WorldVertex)),
                 worldMesh_.empty() ? nullptr : worldMesh_.data(), GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, nx)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, r)));
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 2, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, u)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    worldVertexCount_ = static_cast<GLsizei>(worldMesh_.size());
}

void Engine::UploadTransparentWorldMesh() {
    if (transparentVao_ == 0 || transparentVbo_ == 0) return;
    glBindVertexArray(transparentVao_);
    glBindBuffer(GL_ARRAY_BUFFER, transparentVbo_);
    glBufferData(GL_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(transparentWorldMesh_.size() * sizeof(WorldVertex)),
                 transparentWorldMesh_.empty() ? nullptr : transparentWorldMesh_.data(), GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, nx)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, r)));
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 2, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, u)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    transparentVertexCount_ = static_cast<GLsizei>(transparentWorldMesh_.size());
}

void Engine::RebuildEntityMesh() {
    entityMesh_.clear();
    entities_.AppendMesh(entityMesh_);
    if (thirdPerson_) player_.AppendMesh(entityMesh_);
    // Remote players use the same articulated voxel model as the local avatar.
    for (std::size_t i = 0; i + 3u < remotePlayers_.size(); i += 4u) {
        Player remote;
        remote.position = {remotePlayers_[i], remotePlayers_[i + 1u], remotePlayers_[i + 2u]};
        remote.yaw = remotePlayers_[i + 3u];
        remote.movementX = 0.25f;
        remote.walkPhase = static_cast<float>(ElapsedSeconds() * 5.0);
        remote.AppendMesh(entityMesh_);
    }
    droppedItems_.AppendMesh(entityMesh_);
}

void Engine::UploadEntityMesh() {
    if (entityVao_ == 0 || entityVbo_ == 0) return;
    glBindVertexArray(entityVao_);
    glBindBuffer(GL_ARRAY_BUFFER, entityVbo_);
    glBufferData(GL_ARRAY_BUFFER,
                 static_cast<GLsizeiptr>(entityMesh_.size() * sizeof(WorldVertex)),
                 entityMesh_.empty() ? nullptr : entityMesh_.data(), GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, nx)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, r)));
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3, 2, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, u)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    entityVertexCount_ = static_cast<GLsizei>(entityMesh_.size());
}

double Engine::ElapsedSeconds() const {
    return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

Vec3 Engine::ViewPosition() const {
    if (!thirdPerson_) return player_.CameraPosition();
    const Vec3 flatForward{std::cos(player_.yaw), 0.0f, std::sin(player_.yaw)};
    return {
        player_.position.x - flatForward.x * 3.8f,
        player_.position.y + 2.25f,
        player_.position.z - flatForward.z * 3.8f
    };
}

Vec3 Engine::ViewTarget() const {
    if (!thirdPerson_) {
        const Vec3 eye = player_.CameraPosition();
        const Vec3 forward = player_.Forward();
        return Add(eye, forward);
    }
    const Vec3 flatForward{std::cos(player_.yaw), 0.0f, std::sin(player_.yaw)};
    return {
        player_.position.x + flatForward.x * 0.35f,
        player_.position.y + 1.18f + std::sin(player_.pitch) * 0.80f,
        player_.position.z + flatForward.z * 0.35f
    };
}

Vec3 Engine::ViewDirection() const {
    const Vec3 eye = ViewPosition();
    const Vec3 target = ViewTarget();
    const Vec3 delta{target.x - eye.x, target.y - eye.y, target.z - eye.z};
    const float length = std::sqrt(std::max(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z, 0.000001f));
    return {delta.x / length, delta.y / length, delta.z / length};
}

void Engine::Render() {
    if (!ready_) return;
    const auto now = std::chrono::steady_clock::now();
    const float delta = std::chrono::duration<float>(now - lastFrame_).count();
    lastFrame_ = now;
    if (!paused_) {
        const float safeDelta = std::clamp(delta, 0.0f, 0.05f);
        environment_.Update(safeDelta);
        player_.Update(world_, safeDelta);
        entities_.Update(world_, safeDelta);

        const bool walking = player_.grounded &&
            (std::abs(player_.movementX) + std::abs(player_.movementY) > 0.12f);
        if (walking) {
            footstepElapsed_ += safeDelta;
            const BlockId underPlayer = world_.GetBlock(
                static_cast<int>(std::floor(player_.position.x)),
                static_cast<int>(std::floor(player_.position.y + 0.08f)),
                static_cast<int>(std::floor(player_.position.z)));
            const float interval = HasBlockFlag(underPlayer, BlockFluid) ? 0.62f : 0.43f;
            if (footstepElapsed_ >= interval) {
                soundEvents_.fetch_or(32u, std::memory_order_release);
                footstepElapsed_ = 0.0f;
            }
        } else {
            footstepElapsed_ = 0.0f;
        }
        std::uint16_t pickedItemId = 0u;
        const std::size_t pickedCount =
            droppedItems_.Update(world_, player_, inventory_, safeDelta, &pickedItemId);
        if (pickedCount > 0u) {
            RefreshInventoryUi();
            const BlockDefinition* pickedDefinition =
                GetBlockDefinition(static_cast<BlockId>(pickedItemId));
            const char* pickedName = pickedDefinition != nullptr ? pickedDefinition->nameEs : "Objeto";
            pickupToastText_ = std::string(pickedName) + " ×" + std::to_string(pickedCount);
            pickupToastUntil_ = now + std::chrono::milliseconds(1400);
            ui_.SetPickupToast(pickupToastText_);
            soundEvents_.fetch_or(8u, std::memory_order_release);
        }
        if (!pickupToastText_.empty() && now >= pickupToastUntil_) {
            pickupToastText_.clear();
            ui_.SetPickupToast({});
        }

        UpdateMining(safeDelta);
        if (world_.PumpChunkGeneration()) {
            RebuildWorldMesh();
            UploadWorldMesh();
            UploadTransparentWorldMesh();
        }
        RebuildEntityMesh();
        UploadEntityMesh();
        const int playerChunkX = static_cast<int>(std::floor(player_.position.x / 16.0f));
        const int playerChunkZ = static_cast<int>(std::floor(player_.position.z / 16.0f));
        if (playerChunkX != lastChunkX_ || playerChunkZ != lastChunkZ_) {
            world_.RequestGenerateAround(static_cast<int>(std::floor(player_.position.x)),
                                         static_cast<int>(std::floor(player_.position.z)), 1);
            lastChunkX_ = playerChunkX;
            lastChunkZ_ = playerChunkZ;
            RebuildWorldMesh();
            UploadWorldMesh();
            UploadTransparentWorldMesh();
        }
    }
    const auto sky = environment_.SkyColor();
    glClearColor(sky[0], sky[1], sky[2], 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (worldProgram_ != 0 && worldVertexCount_ > 0) {
        const Vec3 eye = ViewPosition();
        const Vec3 target = ViewTarget();
        const Mat4 projection = Perspective(68.0f * (kPi / 180.0f),
                                            static_cast<float>(width_) / static_cast<float>(height_),
                                            0.08f, 120.0f);
        const Mat4 view = LookAt(eye, target, {0.0f, 1.0f, 0.0f});
        const Mat4 mvp = Multiply(projection, view);
        const auto sun = environment_.SunDirection();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glUseProgram(worldProgram_);
        glUniformMatrix4fv(mvpLocation_, 1, GL_FALSE, mvp.v.data());
        glUniform3f(lightLocation_, sun[0], sun[1], sun[2]);
        glUniform1f(daylightLocation_, environment_.Daylight());
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, blockAtlasTexture_);
        glBindVertexArray(worldVao_);
        glDrawArrays(GL_TRIANGLES, 0, worldVertexCount_);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);

        if (entityVertexCount_ > 0) {
            glUniform1i(useAtlasLocation_, 0);
            glBindVertexArray(entityVao_);
            glDrawArrays(GL_TRIANGLES, 0, entityVertexCount_);
            glBindVertexArray(0);
            glUniform1i(useAtlasLocation_, 1);
        }
        if (transparentVertexCount_ > 0) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(GL_FALSE);
            glBindVertexArray(transparentVao_);
            glDrawArrays(GL_TRIANGLES, 0, transparentVertexCount_);
            glBindVertexArray(0);
            glDepthMask(GL_TRUE);
            glDisable(GL_BLEND);
        }
        glUseProgram(0);
    }

    ++framesSinceStats_;
    if (now - lastStats_ >= std::chrono::seconds(1)) {
        fps_ = static_cast<float>(framesSinceStats_);
        framesSinceStats_ = 0;
        lastStats_ = now;
        char status[128]{};
        std::snprintf(status, sizeof(status), "FPS %.0f · Chunks %zu · Día %.0f%%",
                      fps_, world_.ChunkCount(), environment_.Daylight() * 100.0f);
        ui_.SetStatus(status);
    }

    ui_.UpdateAndRender();
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
}

void Engine::Touch(int action, int pointerId, float x, float y) {
    constexpr int kDown = 0;
    constexpr int kUp = 1;
    constexpr int kMove = 2;
    constexpr int kCancel = 3;
    const bool uiUnhandled = ui_.Touch(action, x, y);

    if (action == kDown) {
        TouchState state;
        state.startX = state.lastX = x;
        state.startY = state.lastY = y;
        state.startedAt = ElapsedSeconds();
        state.uiHandled = !uiUnhandled;
        state.joystick = uiUnhandled && x < static_cast<float>(width_) * 0.42f;
        state.mining = uiUnhandled && !state.joystick;
        touches_[pointerId] = state;
        if (state.joystick) player_.SetMovement(0.0f, 0.0f);
        return;
    }

    auto found = touches_.find(pointerId);
    if (found == touches_.end()) return;
    TouchState& state = found->second;
    if (state.uiHandled) {
        if (action == kUp || action == kCancel) touches_.erase(found);
        return;
    }

    if (action == kMove) {
        if (state.joystick) {
            const float dx = (x - state.startX) / 48.0f;
            const float dy = (state.startY - y) / 48.0f;
            state.moved = true;
            player_.SetMovement(dx, dy);
        } else {
            const float deltaX = x - state.lastX;
            const float deltaY = y - state.lastY;
            if (std::abs(x - state.startX) + std::abs(y - state.startY) > 7.0f) {
                state.moved = true;
                state.mining = false;
            }
            if (state.moved) player_.Rotate(deltaX, deltaY);
        }
        state.lastX = x;
        state.lastY = y;
    } else if (action == kUp || action == kCancel) {
        if (state.joystick) {
            player_.SetMovement(0.0f, 0.0f);
        } else if (action == kUp && !state.moved &&
                   ElapsedSeconds() - state.startedAt < 0.22) {
            // A brief tap is reserved for placing the selected block. Mining requires
            // a stationary hold; moving the finger turns the gesture into camera look.
            const std::uint16_t selected = inventory_.SelectedItemId();
            Vec3 placed{};
            if (selected > 0 && world_.PlaceRay(ViewPosition(), ViewDirection(), 6.0f,
                                                static_cast<BlockId>(selected), &placed)) {
                pendingBlockEdits_.insert(pendingBlockEdits_.end(), {
                    static_cast<int>(placed.x), static_cast<int>(placed.y), static_cast<int>(placed.z),
                    static_cast<int>(selected)
                });
                inventory_.ConsumeSelected();
                RefreshInventoryUi();
                soundEvents_.fetch_or(2u, std::memory_order_release);
                RebuildWorldMesh();
                UploadWorldMesh();
                UploadTransparentWorldMesh();
            }
        }
        if (miningPointerId_ == pointerId) {
            miningPointerId_ = -1;
            miningElapsed_ = 0.0f;
            miningRequired_ = 0.0f;
        }
        ui_.SetTargetBlock({}, 0.0f);
        touches_.erase(found);
    }
}

void Engine::HandleUiAction(const std::string& action) {
    soundEvents_.fetch_or(4u, std::memory_order_release);
    if (action == "perspective") {
        thirdPerson_ = !thirdPerson_;
        ui_.SetPerspectiveEnabled(thirdPerson_);
        RebuildEntityMesh();
        UploadEntityMesh();
    } else if (action == "inventory") {
        inventoryVisible_ = !inventoryVisible_;
        if (inventoryVisible_) RefreshInventoryUi();
        ui_.SetPanelVisible("inventory-panel", inventoryVisible_);
        if (inventoryVisible_) {
            ui_.SetPanelVisible("pause-panel", false);
            pauseVisible_ = false;
            paused_ = true;
            player_.SetMovement(0.0f, 0.0f);
        } else {
            paused_ = pauseVisible_;
        }
    } else if (action == "inventory-close") {
        inventoryVisible_ = false;
        ui_.SetPanelVisible("inventory-panel", false);
        paused_ = pauseVisible_;
    } else if (action == "pause") {
        pauseVisible_ = !pauseVisible_;
        paused_ = pauseVisible_;
        if (pauseVisible_) {
            inventoryVisible_ = false;
            ui_.SetPanelVisible("inventory-panel", false);
            player_.SetMovement(0.0f, 0.0f);
        }
        ui_.SetPanelVisible("pause-panel", pauseVisible_);
        paused_ = pauseVisible_ || inventoryVisible_;
    } else if (action == "resume") {
        pauseVisible_ = false;
        paused_ = inventoryVisible_;
        ui_.SetPanelVisible("pause-panel", false);
    } else if (action == "save") {
        SaveWorld();
        ui_.SetStatus("Mundo guardado");
    } else if (action == "save-exit") {
        SaveWorld();
        exitRequested_.store(true, std::memory_order_release);
    } else if (action == "jump") {
        const bool wasGrounded = player_.grounded;
        player_.Jump();
        if (wasGrounded && !player_.grounded)
            soundEvents_.fetch_or(16u, std::memory_order_release);
    } else if (action.rfind("slot-", 0) == 0 && action.size() == 6) {
        const char digit = action[5];
        if (digit >= '0' && digit <= '8') {
            inventory_.selectedSlot = static_cast<std::uint16_t>(digit - '0');
            RefreshInventoryUi();
        }
    }
}

void Engine::UpdateMining(float deltaSeconds) {
    auto active = std::find_if(touches_.begin(), touches_.end(), [](const auto& entry) {
        const TouchState& state = entry.second;
        return state.mining && !state.moved && !state.uiHandled && !state.joystick;
    });

    if (active == touches_.end()) {
        miningPointerId_ = -1;
        miningElapsed_ = 0.0f;
        miningRequired_ = 0.0f;
        Vec3 target{};
        BlockId targetType = BlockId::Air;
        if (world_.GetRayTargetBlock(ViewPosition(), ViewDirection(), 6.0f,
                                     &target, &targetType)) {
            const BlockDefinition* definition = GetBlockDefinition(targetType);
            ui_.SetTargetBlock(definition != nullptr ? definition->nameEs : "", 0.0f);
        } else {
            ui_.SetTargetBlock({}, 0.0f);
        }
        return;
    }

    const int pointerId = active->first;
    Vec3 target{};
    BlockId targetType = BlockId::Air;
    if (!world_.GetRayTargetBlock(player_.CameraPosition(), player_.Forward(), 6.0f,
                                  &target, &targetType)) {
        miningPointerId_ = pointerId;
        miningElapsed_ = 0.0f;
        miningRequired_ = 0.0f;
        miningBlockType_ = BlockId::Air;
        ui_.SetTargetBlock({}, 0.0f);
        return;
    }

    const BlockDefinition* definition = GetBlockDefinition(targetType);
    if (definition == nullptr) {
        ui_.SetTargetBlock({}, 0.0f);
        return;
    }

    const bool changedTarget = miningPointerId_ != pointerId ||
        miningBlockType_ != targetType ||
        static_cast<int>(miningBlock_.x) != static_cast<int>(target.x) ||
        static_cast<int>(miningBlock_.y) != static_cast<int>(target.y) ||
        static_cast<int>(miningBlock_.z) != static_cast<int>(target.z);
    if (changedTarget) {
        miningPointerId_ = pointerId;
        miningBlock_ = target;
        miningBlockType_ = targetType;
        miningElapsed_ = 0.0f;
        miningRequired_ = 0.0f;
    }

    if (definition->hardness < 0.0f) {
        ui_.SetTargetBlock(definition->nameEs, 0.0f);
        return;
    }

    // Bare-hand time follows block hardness. Tool tiers and their bonuses are not
    // available in this nine-slot prototype inventory yet.
    miningRequired_ = std::clamp(definition->hardness * 1.5f, 0.18f, 10.0f);
    miningElapsed_ += std::clamp(deltaSeconds, 0.0f, 0.05f);
    const float progress = std::clamp(miningElapsed_ / miningRequired_, 0.0f, 1.0f);
    ui_.SetTargetBlock(definition->nameEs, progress);
    if (progress < 1.0f) return;

    Vec3 broken{};
    BlockId brokenType = BlockId::Air;
    if (world_.BreakRay(ViewPosition(), ViewDirection(), 6.0f, &broken, &brokenType)) {
        pendingBlockEdits_.insert(pendingBlockEdits_.end(), {
            static_cast<int>(broken.x), static_cast<int>(broken.y), static_cast<int>(broken.z),
            static_cast<int>(BlockId::Air)
        });
        const BlockDefinition* brokenDefinition = GetBlockDefinition(brokenType);
        if (brokenDefinition != nullptr && brokenDefinition->dropId > 0u) {
            droppedItems_.Spawn({broken.x + 0.5f, broken.y + 0.55f, broken.z + 0.5f},
                                brokenDefinition->dropId, 1u);
        }
        soundEvents_.fetch_or(1u, std::memory_order_release);
        RebuildWorldMesh();
        UploadWorldMesh();
        UploadTransparentWorldMesh();
    }

    active->second.mining = false;
    active->second.moved = true;
    miningPointerId_ = -1;
    miningElapsed_ = 0.0f;
    miningRequired_ = 0.0f;
    ui_.SetTargetBlock({}, 0.0f);
}

void Engine::RefreshInventoryUi() {
    std::string markup;
    markup.reserve(1800);
    for (std::size_t i = 0; i < inventory_.counts.size(); ++i) {
        const std::uint32_t encoded = inventory_.counts[i];
        const std::uint16_t itemId = static_cast<std::uint16_t>(encoded / 1000u);
        const std::uint32_t quantity = encoded % 1000u;
        const BlockDefinition* definition = GetBlockDefinition(static_cast<BlockId>(itemId));
        const char* name = definition != nullptr ? definition->nameEs : "Vacío";
        markup += R"(<div class="inventory-row">)";
        markup += std::to_string(i + 1u) + ". " + name;
        if (quantity > 0u) markup += " × " + std::to_string(quantity);
        markup += "</div>";
        std::string label = "·";
        if (quantity > 0u) {
            std::string shortName = name;
            if (shortName.size() > 3u) shortName.resize(3u);
            label = shortName;
        }
        ui_.SetQuickbarSlot(static_cast<int>(i), label, i == inventory_.selectedSlot);
    }
    ui_.SetInventoryContents(markup);
}

void Engine::SaveWorld() {
    if (multiplayerMode_) return; // LAN sessions are transient until full host-world persistence is added.
    if (storagePath_.empty()) {
        ENGINE_LOGE("Cannot save: Android private storage path has not been supplied");
        return;
    }
    if (!world_.Save(storagePath_, player_.position, inventory_.counts))
        ENGINE_LOGE("Failed to save world to %s", storagePath_.c_str());
    else
        ENGINE_LOGI("World saved to private app storage");
}

void Engine::Pause() {
    if (!ready_) return;
    SaveWorld();
    paused_ = true;
    player_.SetMovement(0.0f, 0.0f);
}

void Engine::Resume() {
    if (!ready_) return;
    paused_ = pauseVisible_ || inventoryVisible_;
}

void Engine::SetStoragePath(std::string path) { storagePath_ = std::move(path) + "/worlds/default.mfworld"; }

bool Engine::ConsumeExitRequest() {
    return exitRequested_.exchange(false, std::memory_order_acq_rel);
}

void Engine::SetMultiplayerMode(bool enabled, int playerSlot) {
    pendingMultiplayerMode_ = enabled;
    pendingMultiplayerSlot_ = std::clamp(playerSlot, 0, 31);
    if (!ready_) {
        multiplayerMode_ = pendingMultiplayerMode_;
        multiplayerSlot_ = pendingMultiplayerSlot_;
    }
    remotePlayers_.clear();
    pendingBlockEdits_.clear();
}

void Engine::GetPlayerState(float output[4]) const {
    if (output == nullptr) return;
    output[0] = player_.position.x;
    output[1] = player_.position.y;
    output[2] = player_.position.z;
    output[3] = player_.yaw;
}

void Engine::SetRemotePlayers(const float* values, std::size_t floatCount) {
    if (values == nullptr || floatCount < 4u) {
        remotePlayers_.clear();
        return;
    }
    const std::size_t count = std::min<std::size_t>(floatCount - (floatCount % 4u), static_cast<std::size_t>(32u));
    remotePlayers_.assign(values, values + count);
}

void Engine::ApplyRemoteBlockEdits(const int* values, std::size_t valueCount) {
    if (values == nullptr || valueCount < 4u) return;
    bool changed = false;
    for (std::size_t i = 0; i + 3u < valueCount; i += 4u) {
        const int x = values[i], y = values[i + 1u], z = values[i + 2u], id = values[i + 3u];
        if (id < 0 || id >= static_cast<int>(GetBlockDefinitionCount())) continue;
        const BlockId previous = world_.GetBlock(x, y, z);
        if (world_.SetBlock(x, y, z, static_cast<BlockId>(id))) {
            changed = true;
            if (multiplayerMode_ && multiplayerSlot_ == 0 && id == static_cast<int>(BlockId::Air)
                    && previous != BlockId::Air) {
                const BlockDefinition* definition = GetBlockDefinition(previous);
                if (definition != nullptr && definition->dropId > 0u) {
                    droppedItems_.Spawn({static_cast<float>(x) + 0.5f,
                                         static_cast<float>(y) + 0.55f,
                                         static_cast<float>(z) + 0.5f},
                                        definition->dropId, 1u);
                }
            }
        }
    }
    if (changed && ready_) {
        RebuildWorldMesh();
        UploadWorldMesh();
        UploadTransparentWorldMesh();
    }
}

std::vector<int> Engine::ConsumeLocalBlockEdits() {
    std::vector<int> result;
    result.swap(pendingBlockEdits_);
    return result;
}

} // namespace minefade
