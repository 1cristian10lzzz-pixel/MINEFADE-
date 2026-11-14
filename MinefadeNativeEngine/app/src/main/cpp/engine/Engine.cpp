#include "Engine.h"
#include "minefade_core.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <filesystem>
#include <limits>
#include <string>
#include <utility>

namespace minefade {
namespace {
constexpr const char* kTag = "MinefadeNative";
constexpr float kPi = 3.14159265358979323846f;
// Captures driver compiler/linker diagnostics for the startup error screen.
std::string gWorldShaderDiagnostic;
#define ENGINE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define ENGINE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)
#define ENGINE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)

constexpr char kWorldVertexShader[] = R"(#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec3 aColor;
layout(location=3) in vec2 aTexCoord;
layout(location=4) in float aAtlasTile;
uniform mat4 uMvp;
uniform vec3 uLightDirection;
uniform float uDaylight;
uniform float uTime;
uniform float uQuality;
uniform int uWaterWaves;
uniform vec3 uCameraPosition;
out mediump vec3 vColor;
out mediump vec2 vTexCoord;
flat out highp int vAtlasTile;
out mediump float vFogDistance;
void main() {
    vec3 position = aPosition;
    float fluidCode = aColor.r;
    bool waterSurface = fluidCode < -0.5 && fluidCode > -1.5 && aNormal.y > 0.8;
    if (waterSurface && uWaterWaves == 1) {
        float wave = sin(position.x * 0.72 + uTime * 1.25)
                   * cos(position.z * 0.61 - uTime * 0.92);
        position.y += wave * (uQuality > 1.5 ? 0.018 : 0.009);
    }
    gl_Position = uMvp * vec4(position, 1.0);
    vFogDistance = distance(position, uCameraPosition);
    float diffuse = max(dot(normalize(aNormal), normalize(-uLightDirection)), 0.0);
    float qualityFactor = clamp(uQuality * 0.5, 0.0, 1.0);
    float ambient = mix(0.17, 0.34, qualityFactor);
    float illumination = ambient + diffuse * (0.40 + 0.60 * uDaylight);
    vec3 material = aColor;
    if (fluidCode < -1.5) material = vec3(1.0, 0.43, 0.16);
    else if (fluidCode < -0.5) material = vec3(0.36, 0.72, 0.91);
    vColor = material * illumination;
    vTexCoord = aTexCoord;
    vAtlasTile = int(aAtlasTile + 0.5);
}
)";

constexpr char kWorldFragmentShader[] = R"(#version 300 es
precision mediump float;
in mediump vec3 vColor;
in mediump vec2 vTexCoord;
flat in highp int vAtlasTile;
in mediump float vFogDistance;
uniform sampler2D uBlockAtlas;
uniform int uUseAtlas;
uniform vec3 uFogColor;
uniform float uFogNear;
uniform float uFogFar;
out vec4 outColor;
vec2 AtlasUv(vec2 localUv, int tile) {
    vec2 atlasSize = vec2(textureSize(uBlockAtlas, 0));
    float column = float(tile - (tile / 32) * 32);
    float row = float(tile / 32);
    vec2 pixel = vec2(column * 18.0 + 1.5 + fract(localUv.x) * 15.0,
                      row * 18.0 + 1.5 + (1.0 - fract(localUv.y)) * 15.0);
    return vec2(pixel.x / atlasSize.x, 1.0 - pixel.y / atlasSize.y);
}
void main() {
    vec4 texel = (uUseAtlas == 1) ? texture(uBlockAtlas, AtlasUv(vTexCoord, vAtlasTile)) : vec4(1.0);
    vec3 litColor = texel.rgb * vColor;
    float fog = smoothstep(uFogNear, uFogFar, vFogDistance);
    outColor = vec4(mix(litColor, uFogColor, fog), texel.a);
}
)";

Vec3 Add(const Vec3& a, const Vec3& b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }

} // namespace

Engine& GetEngine() {
    static Engine engine;
    return engine;
}

std::string Engine::LastInitError() const { return lastInitError_; }

void Engine::SetLastInitError(std::string message) { lastInitError_ = std::move(message); }

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

CameraFrustum Engine::ExtractFrustum(const Mat4& mvp) {
    const auto row=[&](int index) {
        return std::array<float,4>{
            mvp.v[static_cast<std::size_t>(index)],
            mvp.v[static_cast<std::size_t>(4+index)],
            mvp.v[static_cast<std::size_t>(8+index)],
            mvp.v[static_cast<std::size_t>(12+index)]
        };
    };
    const auto r0=row(0), r1=row(1), r2=row(2), r3=row(3);
    const auto makePlane=[](const std::array<float,4>& a,
                            const std::array<float,4>& b,float sign) {
        FrustumPlane plane{a[0]+sign*b[0],a[1]+sign*b[1],
                           a[2]+sign*b[2],a[3]+sign*b[3]};
        const float length=std::sqrt(std::max(
            plane.x*plane.x+plane.y*plane.y+plane.z*plane.z,0.000001f));
        plane.x/=length; plane.y/=length; plane.z/=length; plane.d/=length;
        return plane;
    };
    CameraFrustum frustum;
    frustum.planes={{
        makePlane(r3,r0, 1.0f),makePlane(r3,r0,-1.0f),
        makePlane(r3,r1, 1.0f),makePlane(r3,r1,-1.0f),
        makePlane(r3,r2, 1.0f),makePlane(r3,r2,-1.0f)
    }};
    frustum.valid=true;
    return frustum;
}

GLuint Engine::CompileShader(GLenum type, const char* source) {
    const char* stage = type == GL_VERTEX_SHADER ? "vertex" : "fragment";
    const GLuint shader = glCreateShader(type);
    if (shader == 0) {
        const GLenum error = glGetError();
        char code[24]{};
        std::snprintf(code, sizeof(code), "0x%04X", static_cast<unsigned int>(error));
        gWorldShaderDiagnostic = std::string("glCreateShader(") + stage +
                ") devolvió 0; GL error " + code;
        ENGINE_LOGE("%s", gWorldShaderDiagnostic.c_str());
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        GLint length = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &length);
        std::string log(static_cast<std::size_t>(std::max(length, 1)), '\0');
        GLsizei written = 0;
        glGetShaderInfoLog(shader, static_cast<GLsizei>(log.size()), &written, log.data());
        if (written > 0) log.resize(static_cast<std::size_t>(written));
        else log.clear();
        gWorldShaderDiagnostic = std::string("Falló la compilación del shader ") + stage +
                (log.empty() ? ": el controlador no entregó un registro GLSL." : ": " + log);
        ENGINE_LOGE("%s", gWorldShaderDiagnostic.c_str());
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool Engine::CreateWorldProgram() {
    gWorldShaderDiagnostic.clear();
    const auto destroyCurrentProgram = [this]() {
        if (worldProgram_ != 0) glDeleteProgram(worldProgram_);
        worldProgram_ = 0;
    };
    const auto buildProgram = [this, &destroyCurrentProgram](const char* vertexSource,
                                                              const char* fragmentSource,
                                                              std::string& diagnostic) {
        diagnostic.clear();
        const GLuint vertex = CompileShader(GL_VERTEX_SHADER, vertexSource);
        if (vertex == 0) {
            diagnostic = gWorldShaderDiagnostic;
            return false;
        }
        const GLuint fragment = CompileShader(GL_FRAGMENT_SHADER, fragmentSource);
        if (fragment == 0) {
            diagnostic = gWorldShaderDiagnostic;
            glDeleteShader(vertex);
            return false;
        }
        worldProgram_ = glCreateProgram();
        if (worldProgram_ == 0) {
            const GLenum error = glGetError();
            char code[24]{};
            std::snprintf(code, sizeof(code), "0x%04X", static_cast<unsigned int>(error));
            diagnostic = std::string("glCreateProgram devolvió 0; GL error ") + code;
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            ENGINE_LOGE("%s", diagnostic.c_str());
            return false;
        }
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
            GLsizei written = 0;
            glGetProgramInfoLog(worldProgram_, static_cast<GLsizei>(log.size()), &written, log.data());
            if (written > 0) log.resize(static_cast<std::size_t>(written));
            else log.clear();
            diagnostic = log.empty() ? "El programa no enlazó y el controlador no entregó un registro."
                                     : "Falló el enlace GLSL: " + log;
            ENGINE_LOGE("%s", diagnostic.c_str());
            destroyCurrentProgram();
            return false;
        }
        return true;
    };
    const auto fetchLocations = [this]() {
        mvpLocation_ = glGetUniformLocation(worldProgram_, "uMvp");
        lightLocation_ = glGetUniformLocation(worldProgram_, "uLightDirection");
        daylightLocation_ = glGetUniformLocation(worldProgram_, "uDaylight");
        atlasLocation_ = glGetUniformLocation(worldProgram_, "uBlockAtlas");
        useAtlasLocation_ = glGetUniformLocation(worldProgram_, "uUseAtlas");
        timeLocation_ = glGetUniformLocation(worldProgram_, "uTime");
        qualityLocation_ = glGetUniformLocation(worldProgram_, "uQuality");
        waterWavesLocation_ = glGetUniformLocation(worldProgram_, "uWaterWaves");
        cameraLocation_ = glGetUniformLocation(worldProgram_, "uCameraPosition");
        fogColorLocation_ = glGetUniformLocation(worldProgram_, "uFogColor");
        fogNearLocation_ = glGetUniformLocation(worldProgram_, "uFogNear");
        fogFarLocation_ = glGetUniformLocation(worldProgram_, "uFogFar");
    };

    std::string fullDiagnostic;
    if (buildProgram(kWorldVertexShader, kWorldFragmentShader, fullDiagnostic)) {
        fetchLocations();
        if (mvpLocation_ >= 0 && lightLocation_ >= 0 && daylightLocation_ >= 0 &&
            atlasLocation_ >= 0 && useAtlasLocation_ >= 0 && timeLocation_ >= 0 &&
            qualityLocation_ >= 0 && waterWavesLocation_ >= 0 && cameraLocation_ >= 0 &&
            fogColorLocation_ >= 0 && fogNearLocation_ >= 0 && fogFarLocation_ >= 0) {
            glUseProgram(worldProgram_);
            glUniform1i(atlasLocation_, 0);
            glUniform1i(useAtlasLocation_, 1);
            glUseProgram(0);
            ENGINE_LOGI("Full world shader compiled and linked");
            return true;
        }
        fullDiagnostic = "El shader principal enlazó, pero falta al menos un uniform obligatorio.";
        ENGINE_LOGE("%s", fullDiagnostic.c_str());
        destroyCurrentProgram();
    } else {
        ENGINE_LOGW("Primary world shader rejected: %s", fullDiagnostic.c_str());
    }

    // Some mobile driver versions are fragile with larger shader variants. Keep a
    // deliberately small ES 3.0 path so the world remains playable without fog,
    // animated water or per-fragment lighting if the full program is rejected.
    constexpr char fallbackVertex[] = R"(#version 300 es
precision highp float;
layout(location=0) in vec3 aPosition;
layout(location=2) in vec3 aColor;
layout(location=3) in vec2 aTexCoord;
layout(location=4) in float aAtlasTile;
uniform mat4 uMvp;
out mediump vec3 vColor;
out mediump vec2 vTexCoord;
flat out highp int vAtlasTile;
void main() {
    gl_Position = uMvp * vec4(aPosition, 1.0);
    vColor = aColor;
    vTexCoord = aTexCoord;
    vAtlasTile = int(aAtlasTile + 0.5);
}
)";
    constexpr char fallbackFragment[] = R"(#version 300 es
precision mediump float;
in mediump vec3 vColor;
in mediump vec2 vTexCoord;
flat in highp int vAtlasTile;
uniform sampler2D uBlockAtlas;
uniform int uUseAtlas;
out vec4 outColor;
vec2 AtlasUv(vec2 localUv, int tile) {
    vec2 atlasSize = vec2(textureSize(uBlockAtlas, 0));
    float column = float(tile - (tile / 32) * 32);
    float row = float(tile / 32);
    vec2 pixel = vec2(column * 18.0 + 1.5 + fract(localUv.x) * 15.0,
                      row * 18.0 + 1.5 + (1.0 - fract(localUv.y)) * 15.0);
    return vec2(pixel.x / atlasSize.x, 1.0 - pixel.y / atlasSize.y);
}
void main() {
    vec4 texel = (uUseAtlas == 1) ? texture(uBlockAtlas, AtlasUv(vTexCoord, vAtlasTile)) : vec4(1.0);
    outColor = vec4(texel.rgb * vColor, texel.a);
}
)";
    std::string fallbackDiagnostic;
    if (!buildProgram(fallbackVertex, fallbackFragment, fallbackDiagnostic)) {
        destroyCurrentProgram();
        gWorldShaderDiagnostic = "Shader principal: " + fullDiagnostic +
                "\nShader alternativo: " + fallbackDiagnostic;
        if (gWorldShaderDiagnostic.size() > 760u) gWorldShaderDiagnostic.resize(760u);
        return false;
    }
    fetchLocations();
    if (mvpLocation_ < 0 || atlasLocation_ < 0 || useAtlasLocation_ < 0) {
        fallbackDiagnostic = "El shader alternativo enlazó, pero faltan uniforms obligatorios.";
        gWorldShaderDiagnostic = "Shader principal: " + fullDiagnostic +
                "\nShader alternativo: " + fallbackDiagnostic;
        ENGINE_LOGE("%s", gWorldShaderDiagnostic.c_str());
        destroyCurrentProgram();
        return false;
    }
    glUseProgram(worldProgram_);
    glUniform1i(atlasLocation_, 0);
    glUniform1i(useAtlasLocation_, 1);
    glUseProgram(0);
    ENGINE_LOGW("Using conservative ES 3.0 fallback world shader");
    return true;
}

bool Engine::UploadBlockAtlas(const std::uint8_t* pixels, std::size_t bytes, int width, int height) {
    if (pixels == nullptr || width <= 0 || height <= 0) return false;
    GLint maximumTextureSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maximumTextureSize);
    if (maximumTextureSize <= 0 || width > maximumTextureSize || height > maximumTextureSize) {
        ENGINE_LOGE("Atlas dimensions %dx%d exceed GL_MAX_TEXTURE_SIZE=%d",
                    width, height, maximumTextureSize);
        return false;
    }
    const std::size_t safeWidth = static_cast<std::size_t>(width);
    const std::size_t safeHeight = static_cast<std::size_t>(height);
    if (safeWidth > std::numeric_limits<std::size_t>::max() / safeHeight / 4u) {
        ENGINE_LOGE("Atlas byte count would overflow size_t");
        return false;
    }
    const std::size_t expected = safeWidth * safeHeight * 4u;
    if (bytes != expected) {
        ENGINE_LOGE("Atlas byte count mismatch: got %zu expected %zu", bytes, expected);
        return false;
    }
    glGenTextures(1, &blockAtlasTexture_);
    if (blockAtlasTexture_ == 0) return false;
    GLint previousUnpackAlignment = 4;
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &previousUnpackAlignment);
    glBindTexture(GL_TEXTURE_2D, blockAtlasTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glPixelStorei(GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
    glBindTexture(GL_TEXTURE_2D, 0);
    const GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        glDeleteTextures(1, &blockAtlasTexture_);
        blockAtlasTexture_ = 0;
        ENGINE_LOGE("Block atlas upload failed: GL error 0x%04X",
                    static_cast<unsigned int>(error));
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
    lastInitError_.clear();
    Shutdown();
    world_ = VoxelWorld(0x4D464445u);
    player_ = Player();
    player_.SetAppearance(avatarAppearance_);
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
    entityMeshAccumulator_ = 0.0f;
    itemMeshAccumulator_ = 0.0f;
    exitRequested_.store(false, std::memory_order_release);
    soundEvents_.store(0u, std::memory_order_release);

    multiplayerMode_ = pendingMultiplayerMode_;
    multiplayerSlot_ = pendingMultiplayerSlot_;
    graphicsQuality_ = pendingGraphicsQuality_;
    renderDistanceChunks_ = pendingRenderDistanceChunks_;
    waterWaves_ = pendingWaterWaves_;
    fieldOfView_ = pendingFieldOfView_;
    contactShadows_ = pendingContactShadows_;
    player_.SetContactShadows(contactShadows_);
    entities_.SetContactShadows(contactShadows_);
    animationQuality_ = pendingAnimationQuality_;
    animationsEnabled_ = pendingAnimationsEnabled_;
    player_.SetAnimationOptions(static_cast<std::uint8_t>(animationQuality_), animationsEnabled_);
    entities_.SetAnimationOptions(static_cast<std::uint8_t>(animationQuality_), animationsEnabled_);
    lookSensitivityX_ = pendingLookSensitivityX_;
    lookSensitivityY_ = pendingLookSensitivityY_;
    invertLookY_ = pendingInvertLookY_;
    leftHandedControls_ = pendingLeftHandedControls_;
    joystickDeadzone_ = pendingJoystickDeadzone_;
    showHudStats_ = pendingShowHudStats_;
    ENGINE_LOGI("Starting Minefade Native Engine C++20 / OpenGL ES 3");
    const auto* glVersion = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const auto* glRenderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const auto* glVendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const auto* glslVersion = reinterpret_cast<const char*>(glGetString(GL_SHADING_LANGUAGE_VERSION));
    GLint maxTextureSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    ENGINE_LOGI("OpenGL version=%s GLSL=%s vendor=%s renderer=%s maxTexture=%d",
                glVersion != nullptr ? glVersion : "unknown",
                glslVersion != nullptr ? glslVersion : "unknown",
                glVendor != nullptr ? glVendor : "unknown",
                glRenderer != nullptr ? glRenderer : "unknown", maxTextureSize);

    const auto failInitialisation = [this](const std::string& reason) {
        GLenum glError = glGetError();
        lastInitError_ = reason;
        if (glError != GL_NO_ERROR) {
            char code[24]{};
            std::snprintf(code, sizeof(code), "0x%04X", static_cast<unsigned int>(glError));
            lastInitError_ += std::string(" | OpenGL error ") + code;
        }
        ENGINE_LOGE("World initialisation failed: %s; releasing partial GL and UI state",
                    lastInitError_.c_str());
        Shutdown();
        return false;
    };

    if (minefade_core_abi_version() != MINEFADE_CORE_ABI_VERSION) {
        return failInitialisation("La versión ABI del núcleo Rust no coincide con la esperada por el motor C++.");
    }

    // Clear any stale driver error before attributing a failure to a startup stage.
    while (glGetError() != GL_NO_ERROR) { }
    if (!CreateWorldProgram()) {
        std::string reason = "No se pudo compilar o enlazar el shader principal del mundo.";
        if (!gWorldShaderDiagnostic.empty()) {
            reason += "\nDetalle GLSL: " + gWorldShaderDiagnostic;
        }
        return failInitialisation(reason);
    }
    if (!UploadBlockAtlas(atlasPixels, atlasBytes, atlasWidth, atlasHeight)) {
        return failInitialisation("No se pudo cargar la textura atlas de bloques. Comprueba sus dimensiones, memoria gráfica y GL_MAX_TEXTURE_SIZE.");
    }
    glGenVertexArrays(1, &worldVao_);
    glGenBuffers(1, &worldVbo_);
    glGenVertexArrays(1, &entityVao_);
    glGenBuffers(1, &entityVbo_);
    glGenVertexArrays(1, &transparentVao_);
    glGenBuffers(1, &transparentVbo_);
    glGenVertexArrays(1, &itemVao_);
    glGenBuffers(1, &itemVbo_);
    if (worldVao_ == 0 || worldVbo_ == 0 || entityVao_ == 0 || entityVbo_ == 0 ||
        transparentVao_ == 0 || transparentVbo_ == 0 || itemVao_ == 0 || itemVbo_ == 0)
        return failInitialisation("El controlador no pudo reservar los VAO/VBO del mundo, entidades, transparencia u objetos. Posible falta de memoria gráfica.");
    if (const GLenum error = glGetError(); error != GL_NO_ERROR) {
        char code[24]{};
        std::snprintf(code, sizeof(code), "0x%04X", static_cast<unsigned int>(error));
        return failInitialisation(std::string("Error al reservar buffers OpenGL: ") + code);
    }

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
            // Keep a copy of a damaged/unsupported save before replacing it with
            // a fresh playable world. This avoids silently destroying old progress.
            std::error_code backupError;
            const bool saveExists = std::filesystem::exists(storagePath_, backupError) && !backupError;
            if (saveExists) {
                const std::string backupPath = storagePath_ + ".recovery-backup";
                std::filesystem::copy_file(storagePath_, backupPath,
                    std::filesystem::copy_options::none, backupError);
                if (!backupError) {
                    ENGINE_LOGE("Unreadable world save preserved at %s", backupPath.c_str());
                } else {
                    ENGINE_LOGE("Could not preserve unreadable world save: %s", backupError.message().c_str());
                }
            }
            world_.GenerateAround(8, 8, 0);
            player_.position = {8.0f, static_cast<float>(world_.GetSurfaceHeight(8, 8)) + 1.05f, 8.0f};
        }
    } else {
        world_.GenerateAround(8, 8, 0);
        player_.position = {8.0f, static_cast<float>(world_.GetSurfaceHeight(8, 8)) + 1.05f, 8.0f};
    }
    if (!player_.ResolveSafeSpawn(world_)) {
        ENGINE_LOGE("Saved spawn is obstructed and no nearby safe position was found");
        world_.GenerateAround(8, 8, 0);
        player_.position = {8.0f, static_cast<float>(world_.GetSurfaceHeight(8, 8)) + 1.001f, 8.0f};
        if (!player_.ResolveSafeSpawn(world_)) {
            ENGINE_LOGE("Fresh spawn recovery failed; generated terrain has no safe player location");
            return failInitialisation("No se pudo encontrar una posición segura para aparecer incluso después de regenerar el terreno inicial.");
        }
    }
    if (!multiplayerMode_) {
        // Stream the nearby ring after the first frame. Synchronously generating
        // nine 16x16x96 chunks and building their meshes here can stall EGL startup
        // on lower-end Android GPUs and trigger the world's loading timeout.
        world_.RequestGenerateAround(static_cast<int>(std::floor(player_.position.x)),
                                     static_cast<int>(std::floor(player_.position.z)),
                                     renderDistanceChunks_);
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
    UploadItemMesh();
    if (const GLenum error = glGetError(); error != GL_NO_ERROR) {
        char code[24]{};
        std::snprintf(code, sizeof(code), "0x%04X", static_cast<unsigned int>(error));
        return failInitialisation(std::string("Falló la construcción o transferencia de las mallas del mundo: ") + code);
    }

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);
    glFrontFace(GL_CCW);
    glDisable(GL_BLEND);
    glViewport(0, 0, width_, height_);

    // Keep the generated atlas available to RmlUi so quickbar/inventory icons
    // are cropped from the exact same block textures used by the 3D renderer.
    ui_.SetBlockAtlas(atlasPixels,atlasBytes,atlasWidth,atlasHeight);
    ui_.SetStatusVisible(showHudStats_);
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
    if (itemVbo_ != 0) glDeleteBuffers(1, &itemVbo_);
    if (itemVao_ != 0) glDeleteVertexArrays(1, &itemVao_);
    if (worldProgram_ != 0) glDeleteProgram(worldProgram_);
    if (blockAtlasTexture_ != 0) glDeleteTextures(1, &blockAtlasTexture_);
    worldVbo_ = worldVao_ = entityVbo_ = entityVao_ = transparentVbo_ = transparentVao_ = 0;
    itemVbo_ = itemVao_ = 0;
    worldProgram_ = blockAtlasTexture_ = 0;
    mvpLocation_ = lightLocation_ = daylightLocation_ = atlasLocation_ = useAtlasLocation_ = -1;
    timeLocation_ = qualityLocation_ = waterWavesLocation_ = -1;
    cameraLocation_ = fogColorLocation_ = fogNearLocation_ = fogFarLocation_ = -1;
    worldVertexCount_ = transparentVertexCount_ = entityVertexCount_ = itemVertexCount_ = 0;
    worldChunkRanges_.clear();
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
                     static_cast<int>(std::floor(player_.position.z)),
                     renderDistanceChunks_, &transparentWorldMesh_, &worldChunkRanges_);

    // Keep each chunk's transparent triangles far-to-near without changing the
    // chunk ranges. The renderer sorts the chunk ranges again as the camera moves.
    for (const ChunkMeshRange& range : worldChunkRanges_) {
        if (range.transparentCount < 6u) continue;
        using Triangle = std::array<WorldVertex,3>;
        std::vector<std::pair<float,Triangle>> sorted;
        sorted.reserve(range.transparentCount/3u);
        const Vec3 eye=ViewPosition();
        const std::size_t first=range.transparentFirst;
        const std::size_t last=first+range.transparentCount;
        for (std::size_t i=first;i+2u<last;i+=3u) {
            Triangle triangle{transparentWorldMesh_[i],transparentWorldMesh_[i+1u],
                              transparentWorldMesh_[i+2u]};
            const float cx=(triangle[0].x+triangle[1].x+triangle[2].x)/3.0f-eye.x;
            const float cy=(triangle[0].y+triangle[1].y+triangle[2].y)/3.0f-eye.y;
            const float cz=(triangle[0].z+triangle[1].z+triangle[2].z)/3.0f-eye.z;
            sorted.emplace_back(cx*cx+cy*cy+cz*cz,triangle);
        }
        std::sort(sorted.begin(),sorted.end(),[](const auto& a,const auto& b){
            return a.first>b.first;
        });
        std::size_t cursor=first;
        for (const auto& entry:sorted)
            for (const WorldVertex& vertex:entry.second) transparentWorldMesh_[cursor++]=vertex;
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
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, atlasTile)));
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
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, atlasTile)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    transparentVertexCount_ = static_cast<GLsizei>(transparentWorldMesh_.size());
}

void Engine::RebuildEntityMesh() {
    entityMesh_.clear();
    itemMesh_.clear();
    const Vec3 eye=ViewPosition();
    const Vec3 target=ViewTarget();
    const Mat4 projection=Perspective(fieldOfView_*(kPi/180.0f),
        static_cast<float>(width_)/static_cast<float>(height_),0.08f,120.0f);
    const Mat4 view=LookAt(eye,target,{0.0f,1.0f,0.0f});
    const CameraFrustum frustum=ExtractFrustum(Multiply(projection,view));
    entities_.AppendMesh(entityMesh_,&frustum);
    if (thirdPerson_) {
        player_.AppendMesh(entityMesh_);
    } else {
        // First-person held item: render the selected block with its own atlas
        // texture and place it in the lower-right camera space, not as a generic cube.
        const std::uint16_t selected=inventory_.SelectedItemId();
        const BlockDefinition* definition=GetBlockDefinition(static_cast<BlockId>(selected));
        if(definition!=nullptr&&definition->shape!=BlockShape::None&&selected!=static_cast<std::uint16_t>(BlockId::Air)) {
            const Vec3 eye=player_.CameraPosition();
            const Vec3 forward=player_.Forward();
            const Vec3 right{-forward.z,0.0f,forward.x};
            const Vec3 held{
                eye.x+forward.x*0.66f+right.x*0.30f,
                eye.y+forward.y*0.66f-0.28f,
                eye.z+forward.z*0.66f+right.z*0.30f
            };
            const float movementAmount=std::clamp(
                std::abs(player_.movementX)+std::abs(player_.movementY),0.0f,1.0f);
            const float handBob=std::sin(player_.animationTime*1.6f)*0.012f
                +std::cos(player_.walkPhase*2.0f)*0.022f*movementAmount;
            const float handYaw=player_.yaw+std::sin(player_.walkPhase)*0.055f*movementAmount;
            AppendAnimatedTexturedBlockCube(itemMesh_,static_cast<BlockId>(selected),
                held,{0.105f,0.105f,0.105f},handYaw,handBob);
            const std::array<float,3> sleeve{0.28f,0.43f,0.38f};
            AppendCube(entityMesh_,{held.x-right.x*0.19f,held.y-0.12f,held.z-right.z*0.19f},
                       {0.085f,0.16f,0.085f},sleeve);
        }
    }
    // Remote players use the same articulated voxel model as the local avatar.
    for (std::size_t i = 0; i + 3u < remotePlayers_.size(); i += 4u) {
        Player remote;
        remote.position = {remotePlayers_[i], remotePlayers_[i + 1u], remotePlayers_[i + 2u]};
        remote.yaw = remotePlayers_[i + 3u];
        if (!frustum.IntersectsAabb(
                remote.position.x-0.62f,remote.position.y-0.08f,remote.position.z-0.62f,
                remote.position.x+0.62f,remote.position.y+1.95f,remote.position.z+0.62f)) continue;
        // Network snapshots currently carry position/yaw only. Render a
        // natural idle pose until movement deltas are available in the protocol,
        // rather than making every connected player look permanently in motion.
        remote.movementX = 0.0f;
        remote.movementY = 0.0f;
        remote.walkPhase = 0.0f;
        remote.animationTime = static_cast<float>(ElapsedSeconds());
        remote.SetAnimationOptions(static_cast<std::uint8_t>(animationQuality_), animationsEnabled_);
        remote.AppendMesh(entityMesh_);
    }
    // Block items use their own atlas-backed draw pass; entity models remain
    // vertex-coloured and therefore do not sample an arbitrary texture pixel.
    droppedItems_.AppendMesh(itemMesh_);
}

void Engine::UploadItemMesh() {
    if(itemVao_==0||itemVbo_==0)return;
    glBindVertexArray(itemVao_);
    glBindBuffer(GL_ARRAY_BUFFER,itemVbo_);
    glBufferData(GL_ARRAY_BUFFER,
        static_cast<GLsizeiptr>(itemMesh_.size()*sizeof(WorldVertex)),
        itemMesh_.empty()?nullptr:itemMesh_.data(),GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex,x)));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex,nx)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2,3,GL_FLOAT,GL_FALSE,sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex,r)));
    glEnableVertexAttribArray(3);
    glVertexAttribPointer(3,2,GL_FLOAT,GL_FALSE,sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex,u)));
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4,1,GL_FLOAT,GL_FALSE,sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex,atlasTile)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER,0);
    itemVertexCount_=static_cast<GLsizei>(itemMesh_.size());
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
    glEnableVertexAttribArray(4);
    glVertexAttribPointer(4, 1, GL_FLOAT, GL_FALSE, sizeof(WorldVertex),
                          reinterpret_cast<const void*>(offsetof(WorldVertex, atlasTile)));
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
        // Simulation remains frame-stepped, while animated vertex buffers are
        // uploaded at bounded rates to avoid rebuilding all creature/item geometry
        // on every 60–120 Hz screen refresh.
        entityMeshAccumulator_ += safeDelta;
        if (entityMeshAccumulator_ >= (1.0f / 30.0f)) {
            entityMeshAccumulator_ = std::fmod(entityMeshAccumulator_, 1.0f / 30.0f);
            RebuildEntityMesh();
            UploadEntityMesh();
        }
        itemMeshAccumulator_ += safeDelta;
        if (itemMeshAccumulator_ >= 0.05f) {
            itemMeshAccumulator_ = std::fmod(itemMeshAccumulator_, 0.05f);
            UploadItemMesh();
        }
        const int playerChunkX = static_cast<int>(std::floor(player_.position.x / 16.0f));
        const int playerChunkZ = static_cast<int>(std::floor(player_.position.z / 16.0f));
        if (playerChunkX != lastChunkX_ || playerChunkZ != lastChunkZ_) {
            world_.RequestGenerateAround(static_cast<int>(std::floor(player_.position.x)),
                                         static_cast<int>(std::floor(player_.position.z)),
                                         renderDistanceChunks_);
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
        const Mat4 projection = Perspective(fieldOfView_ * (kPi / 180.0f),
                                            static_cast<float>(width_) / static_cast<float>(height_),
                                            0.08f, 120.0f);
        const Mat4 view = LookAt(eye, target, {0.0f, 1.0f, 0.0f});
        const Mat4 mvp = Multiply(projection, view);
        const CameraFrustum frustum = ExtractFrustum(mvp);
        const auto chunkVisible = [&](const ChunkMeshRange& range) {
            const float minX = static_cast<float>(range.chunkX * VoxelWorld::kChunkSize);
            const float minZ = static_cast<float>(range.chunkZ * VoxelWorld::kChunkSize);
            return frustum.IntersectsAabb(minX, 0.0f, minZ,
                minX + static_cast<float>(VoxelWorld::kChunkSize),
                static_cast<float>(VoxelWorld::kWorldHeight),
                minZ + static_cast<float>(VoxelWorld::kChunkSize));
        };
        const auto sun = environment_.SunDirection();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glUseProgram(worldProgram_);
        glUniformMatrix4fv(mvpLocation_, 1, GL_FALSE, mvp.v.data());
        glUniform3f(lightLocation_, sun[0], sun[1], sun[2]);
        glUniform1f(daylightLocation_, environment_.Daylight());
        glUniform1f(timeLocation_, static_cast<float>(ElapsedSeconds()));
        glUniform1f(qualityLocation_, static_cast<float>(graphicsQuality_));
        glUniform1i(waterWavesLocation_, waterWaves_ ? 1 : 0);
        glUniform3f(cameraLocation_, eye.x, eye.y, eye.z);
        glUniform3f(fogColorLocation_, sky[0], sky[1], sky[2]);
        const float fogStart = 12.0f + static_cast<float>(renderDistanceChunks_) * 14.0f;
        const float fogEnd = std::min(120.0f, fogStart + 34.0f + static_cast<float>(graphicsQuality_) * 10.0f);
        glUniform1f(fogNearLocation_, fogStart);
        glUniform1f(fogFarLocation_, fogEnd);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, blockAtlasTexture_);
        glBindVertexArray(worldVao_);
        if (worldChunkRanges_.empty()) {
            glDrawArrays(GL_TRIANGLES, 0, worldVertexCount_);
        } else {
            std::size_t batchFirst=0u, batchCount=0u;
            const auto flushOpaqueBatch=[&]() {
                if (batchCount>0u) {
                    glDrawArrays(GL_TRIANGLES,static_cast<GLint>(batchFirst),
                                 static_cast<GLsizei>(batchCount));
                    batchCount=0u;
                }
            };
            for (const ChunkMeshRange& range : worldChunkRanges_) {
                if (range.opaqueCount==0u) continue;
                if (!chunkVisible(range)) {
                    flushOpaqueBatch();
                    continue;
                }
                if (batchCount==0u) {
                    batchFirst=range.opaqueFirst;
                    batchCount=range.opaqueCount;
                } else if (batchFirst+batchCount==range.opaqueFirst) {
                    batchCount+=range.opaqueCount;
                } else {
                    flushOpaqueBatch();
                    batchFirst=range.opaqueFirst;
                    batchCount=range.opaqueCount;
                }
            }
            flushOpaqueBatch();
        }
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);

        if (entityVertexCount_ > 0) {
            glUniform1i(useAtlasLocation_, 0);
            glBindVertexArray(entityVao_);
            glDrawArrays(GL_TRIANGLES, 0, entityVertexCount_);
            glBindVertexArray(0);
        }
        if(itemVertexCount_>0) {
            glUniform1i(useAtlasLocation_,1);
            glBindTexture(GL_TEXTURE_2D,blockAtlasTexture_);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(GL_FALSE);
            glBindVertexArray(itemVao_);
            glDrawArrays(GL_TRIANGLES,0,itemVertexCount_);
            glBindVertexArray(0);
            glDepthMask(GL_TRUE);
            glDisable(GL_BLEND);
        }
        glUniform1i(useAtlasLocation_,1);
        if (transparentVertexCount_ > 0) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDepthMask(GL_FALSE);
            glBindVertexArray(transparentVao_);
            std::vector<const ChunkMeshRange*> transparentVisible;
            transparentVisible.reserve(worldChunkRanges_.size());
            for (const ChunkMeshRange& range : worldChunkRanges_)
                if (range.transparentCount>0u && chunkVisible(range))
                    transparentVisible.push_back(&range);
            std::sort(transparentVisible.begin(),transparentVisible.end(),
                [&](const ChunkMeshRange* a,const ChunkMeshRange* b) {
                    const float ax=(static_cast<float>(a->chunkX)+0.5f)*VoxelWorld::kChunkSize-eye.x;
                    const float az=(static_cast<float>(a->chunkZ)+0.5f)*VoxelWorld::kChunkSize-eye.z;
                    const float bx=(static_cast<float>(b->chunkX)+0.5f)*VoxelWorld::kChunkSize-eye.x;
                    const float bz=(static_cast<float>(b->chunkZ)+0.5f)*VoxelWorld::kChunkSize-eye.z;
                    return ax*ax+az*az > bx*bx+bz*bz;
                });
            for (const ChunkMeshRange* range : transparentVisible)
                glDrawArrays(GL_TRIANGLES,static_cast<GLint>(range->transparentFirst),
                             static_cast<GLsizei>(range->transparentCount));
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
        state.joystick = uiUnhandled && (leftHandedControls_ ? x > static_cast<float>(width_) * 0.58f
                : x < static_cast<float>(width_) * 0.42f);
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
            const float magnitude = std::sqrt(dx * dx + dy * dy);
            state.moved = true;
            if (magnitude <= joystickDeadzone_) {
                player_.SetMovement(0.0f, 0.0f);
            } else {
                // Remap the range outside the dead zone to [0, 1] so small
                // accidental finger drift does not move the player.
                const float intensity = std::clamp(
                    (magnitude - joystickDeadzone_) / (1.0f - joystickDeadzone_), 0.0f, 1.0f);
                const float factor = intensity / std::max(magnitude, 0.0001f);
                player_.SetMovement(dx * factor, dy * factor);
            }
        } else {
            const float deltaX = x - state.lastX;
            const float deltaY = y - state.lastY;
            if (std::abs(x - state.startX) + std::abs(y - state.startY) > 7.0f) {
                state.moved = true;
                state.mining = false;
            }
            if (state.moved) {
                const float verticalDirection = invertLookY_ ? -1.0f : 1.0f;
                player_.Rotate(deltaX * lookSensitivityX_,
                               deltaY * lookSensitivityY_ * verticalDirection);
            }
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
    markup.reserve(2400);
    for(std::size_t i=0;i<inventory_.counts.size();++i) {
        const std::uint32_t encoded=inventory_.counts[i];
        const std::uint16_t itemId=static_cast<std::uint16_t>(encoded/1000u);
        const std::uint32_t quantity=encoded%1000u;
        const BlockDefinition* definition=GetBlockDefinition(static_cast<BlockId>(itemId));
        const char* name=definition!=nullptr?definition->nameEs:"Vacío";
        std::string icon;
        if(quantity>0u&&itemId>0u) {
            icon="<img class='inventory-icon' src='minefade://block/"+
                std::to_string(itemId)+"'/>";
        } else {
            icon="<span class='inventory-icon empty-icon'>—</span>";
        }
        markup+="<div class='inventory-row'>"+icon+"<span>";
        markup+=std::to_string(i+1u)+". "+name;
        if(quantity>0u)markup+=" × "+std::to_string(quantity);
        markup+="</span></div>";

        std::string slotMarkup="·";
        if(quantity>0u&&itemId>0u) {
            slotMarkup="<img src='minefade://block/"+std::to_string(itemId)+"'/>";
            if(quantity>1u)slotMarkup+="<span class='slot-count'>"+
                std::to_string(quantity)+"</span>";
        }
        ui_.SetQuickbarSlot(static_cast<int>(i),slotMarkup,i==inventory_.selectedSlot);
    }
    ui_.SetInventoryContents(markup);
}

void Engine::SaveWorld() {
    if (multiplayerMode_) return; // LAN sessions are transient until full host-world persistence is added.
    if (storagePath_.empty()) {
        ENGINE_LOGE("Cannot save: Android private storage path has not been supplied");
        return;
    }
    // Persistence errors must never be reported as a graphics/world startup failure.
    // A user should still be able to enter and play while the storage problem is visible
    // in Logcat; the original world file remains untouched if the atomic save fails.
    try {
        if (!world_.Save(storagePath_, player_.position, inventory_.counts))
            ENGINE_LOGE("Failed to save world to %s", storagePath_.c_str());
        else
            ENGINE_LOGI("World saved to private app storage");
    } catch (const std::exception& error) {
        ENGINE_LOGE("World save raised an exception: %s", error.what());
    } catch (...) {
        ENGINE_LOGE("World save raised an unknown exception");
    }
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

void Engine::SetGraphicsOptions(int quality,int renderDistanceChunks,bool waterWaves,
                                 float fieldOfView,bool contactShadows) {
    pendingGraphicsQuality_=std::clamp(quality,0,2);
    pendingRenderDistanceChunks_=std::clamp(renderDistanceChunks,1,3);
    pendingWaterWaves_=waterWaves;
    pendingFieldOfView_=std::clamp(fieldOfView,60.0f,100.0f);
    pendingContactShadows_=contactShadows;
    graphicsQuality_=pendingGraphicsQuality_;
    renderDistanceChunks_=pendingRenderDistanceChunks_;
    waterWaves_=pendingWaterWaves_;
    fieldOfView_=pendingFieldOfView_;
    contactShadows_=pendingContactShadows_;
    if(ready_) {
        player_.SetContactShadows(contactShadows_);
        entities_.SetContactShadows(contactShadows_);
        world_.RequestGenerateAround(static_cast<int>(std::floor(player_.position.x)),
                                     static_cast<int>(std::floor(player_.position.z)),
                                     renderDistanceChunks_);
        RebuildWorldMesh();
        UploadWorldMesh();
        UploadTransparentWorldMesh();
        RebuildEntityMesh();
        UploadEntityMesh();
    }
}

void Engine::SetInputOptions(float sensitivityX, float sensitivityY, float joystickDeadzone, bool invertLookY, bool leftHanded) {
    pendingLookSensitivityX_ = std::clamp(sensitivityX, 0.35f, 1.50f);
    pendingLookSensitivityY_ = std::clamp(sensitivityY, 0.35f, 1.50f);
    pendingJoystickDeadzone_ = std::clamp(joystickDeadzone, 0.05f, 0.30f);
    pendingInvertLookY_ = invertLookY;
    pendingLeftHandedControls_ = leftHanded;
    lookSensitivityX_ = pendingLookSensitivityX_;
    lookSensitivityY_ = pendingLookSensitivityY_;
    invertLookY_ = pendingInvertLookY_;
    leftHandedControls_ = pendingLeftHandedControls_;
    ui_.SetLeftHanded(leftHandedControls_);
}

void Engine::SetHudOptions(bool showStats) {
    pendingShowHudStats_ = showStats;
    showHudStats_ = showStats;
    ui_.SetStatusVisible(showHudStats_);
}

void Engine::SetAnimationOptions(int quality, bool enabled) {
    pendingAnimationQuality_ = std::clamp(quality, 0, 2);
    pendingAnimationsEnabled_ = enabled;
    animationQuality_ = pendingAnimationQuality_;
    animationsEnabled_ = pendingAnimationsEnabled_;
    player_.SetAnimationOptions(static_cast<std::uint8_t>(animationQuality_), animationsEnabled_);
    entities_.SetAnimationOptions(static_cast<std::uint8_t>(animationQuality_), animationsEnabled_);
    if (ready_) {
        RebuildEntityMesh();
        UploadEntityMesh();
    }
}

void Engine::SetAvatarAppearance(const int* values,std::size_t count) {
    if(values==nullptr||count<avatarAppearance_.size())return;
    for(std::size_t i=0;i<avatarAppearance_.size();++i)
        avatarAppearance_[i]=std::clamp(values[i],0,5);
    player_.SetAppearance(avatarAppearance_);
    if(ready_) {
        RebuildEntityMesh();
        UploadEntityMesh();
    }
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
