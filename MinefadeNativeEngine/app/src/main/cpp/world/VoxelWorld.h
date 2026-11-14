#pragma once

#include "../blocks/BlockRegistry.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <future>
#include <set>
#include <map>
#include <string>
#include <vector>

namespace minefade {

struct Vec3 {
    float x{0.0f};
    float y{0.0f};
    float z{0.0f};
};

// Interleaved vertex format for the OpenGL ES world renderer.
struct WorldVertex {
    float x, y, z;
    float nx, ny, nz;
    float r, g, b;
    float u, v;
    // Atlas tile index; UVs are local and may repeat across greedy-merged faces.
    float atlasTile{0.0f};
};

struct FrustumPlane { float x{0.0f}, y{0.0f}, z{0.0f}, d{0.0f}; };

struct CameraFrustum {
    std::array<FrustumPlane, 6> planes{};
    bool valid{false};

    bool IntersectsAabb(float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ) const noexcept {
        if (!valid) return true;
        for (const FrustumPlane& plane : planes) {
            const float px = plane.x >= 0.0f ? maxX : minX;
            const float py = plane.y >= 0.0f ? maxY : minY;
            const float pz = plane.z >= 0.0f ? maxZ : minZ;
            if (plane.x * px + plane.y * py + plane.z * pz + plane.d < 0.0f) return false;
        }
        return true;
    }
};

struct ChunkMeshRange {
    int chunkX{0};
    int chunkZ{0};
    std::size_t opaqueFirst{0};
    std::size_t opaqueCount{0};
    std::size_t transparentFirst{0};
    std::size_t transparentCount{0};
    int lodLevel{0};
};

class VoxelWorld {
public:
    static constexpr int kChunkSize = 16;
    static constexpr int kWorldHeight = 96;
    static constexpr int kSeaLevel = 34;
    static constexpr std::size_t kChunkVolume =
        static_cast<std::size_t>(kChunkSize * kWorldHeight * kChunkSize);

    explicit VoxelWorld(std::uint32_t seed = 0x4D464445u);

    void GenerateAround(int worldX, int worldZ, int radiusInChunks = 1);
    void RequestGenerateAround(int worldX, int worldZ, int radiusInChunks = 1);
    bool PumpChunkGeneration();
    void WaitForChunkGeneration();
    std::size_t PendingChunkCount() const;
    BlockId GetBlock(int x, int y, int z) const;
    bool SetBlock(int x, int y, int z, BlockId block);
    bool IsSolid(int x, int y, int z) const;
    bool IsOpaque(int x, int y, int z) const;
    int GetSurfaceHeight(int x, int z) const;
    bool GetRayTargetBlock(const Vec3& origin, const Vec3& direction, float maxDistance,
                           Vec3* targetBlock = nullptr, BlockId* targetType = nullptr) const;
    bool BreakRay(const Vec3& origin, const Vec3& direction, float maxDistance,
                  Vec3* brokenBlock = nullptr, BlockId* brokenType = nullptr);
    bool PlaceRay(const Vec3& origin, const Vec3& direction, float maxDistance, BlockId block,
                  Vec3* placedBlock = nullptr);

    void BuildMesh(std::vector<WorldVertex>& output, int centerWorldX = 0,
                   int centerWorldZ = 0, int radiusInChunks = -1,
                   std::vector<WorldVertex>* transparentOutput = nullptr,
                   std::vector<ChunkMeshRange>* chunkRanges = nullptr) const;
    bool Save(const std::string& filePath, const Vec3& playerPosition,
              const std::array<std::uint32_t, 9>& inventory) const;
    bool Load(const std::string& filePath, Vec3& playerPosition,
              std::array<std::uint32_t, 9>& inventory);

    std::uint32_t Seed() const { return seed_; }
    std::size_t ChunkCount() const { return chunks_.size(); }
    std::size_t CountNonAirBlocks() const;

private:
    struct Chunk {
        int x{0};
        int z{0};
        std::array<BlockId, kChunkVolume> blocks{};
    };

    enum class Biome : std::uint8_t { Plains, Forest, Desert, Snow, Ocean, Mountains, Swamp, Savanna, River };

    static int FloorDiv(int value, int divisor);
    static int PositiveMod(int value, int divisor);
    static std::size_t Index(int x, int y, int z);
    float Noise2D(float x, float z) const;
    float FractalNoise2D(float x, float z, int octaves, float persistence, float lacunarity) const;
    Biome GetBiome(int x, int z) const;
    int TerrainHeight(int x, int z) const;
    Chunk MakeChunk(int chunkX, int chunkZ) const;
    void StartNextChunkJob();
    const Chunk* FindChunk(int chunkX, int chunkZ) const;
    Chunk* FindChunk(int chunkX, int chunkZ);
    bool IsChunkFullySealed(const Chunk& chunk) const;

    std::uint32_t seed_;
    std::map<std::pair<int, int>, Chunk> chunks_;
    std::deque<std::pair<int, int>> generationQueue_;
    std::set<std::pair<int, int>> queuedChunks_;
    struct ChunkJob {
        std::pair<int, int> key;
        std::future<Chunk> future;
    };
    static constexpr std::size_t kChunkWorkerCount = 2;
    static constexpr std::size_t kMeshWorkerCount = 2;
    std::vector<ChunkJob> pendingChunkJobs_;
    bool asyncGenerationDisabled_{false};
};

void AppendCube(std::vector<WorldVertex>& output, const Vec3& center, const Vec3& half,
                const std::array<float, 3>& color);
void AppendTexturedBlockCube(std::vector<WorldVertex>& output, BlockId block,
                             const Vec3& center, const Vec3& half);
void AppendAnimatedTexturedBlockCube(std::vector<WorldVertex>& output, BlockId block,
                                     const Vec3& center, const Vec3& half,
                                     float yawRadians, float verticalOffset);

} // namespace minefade
