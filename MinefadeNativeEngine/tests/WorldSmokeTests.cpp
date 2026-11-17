#include "../app/src/main/cpp/world/VoxelWorld.h"
#include "../app/src/main/cpp/game/Player.h"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <cassert>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>

using namespace minefade;

int main() {
    VoxelWorld world(123456u);
    // Exercise the exact default game seed and the same one-chunk spawn path
    // used by Engine::Initialise, not only arbitrary test seeds.
    VoxelWorld firstLaunchWorld(0x4D464445u);
    firstLaunchWorld.GenerateAround(8, 8, 0);
    Player firstLaunchPlayer;
    firstLaunchPlayer.position = {8.0f,
        static_cast<float>(firstLaunchWorld.GetSurfaceHeight(8, 8)) + 1.05f, 8.0f};
    assert(firstLaunchPlayer.ResolveSafeSpawn(firstLaunchWorld));
    assert(std::isfinite(firstLaunchPlayer.position.x));
    assert(std::isfinite(firstLaunchPlayer.position.y));
    assert(std::isfinite(firstLaunchPlayer.position.z));
    assert(firstLaunchPlayer.position.y > 0.0f);
    assert(!firstLaunchWorld.IsSolid(
        static_cast<int>(std::floor(firstLaunchPlayer.position.x)),
        static_cast<int>(std::floor(firstLaunchPlayer.position.y + 0.75f)),
        static_cast<int>(std::floor(firstLaunchPlayer.position.z))));

    VoxelWorld deterministic(123456u);
    world.GenerateAround(8, 8, 1);
    deterministic.GenerateAround(8, 8, 1);
    assert(world.GetSurfaceHeight(8, 8) == deterministic.GetSurfaceHeight(8, 8));
    std::size_t deepslateCount = 0u;
    for (int x = -16; x < 32; ++x) for (int z = -16; z < 32; ++z) {
        for (int y = 0; y < 17; ++y) {
            const BlockId id = world.GetBlock(x, y, z);
            if (id == BlockId::Deepslate || id == BlockId::DeepslateCoalOre ||
                id == BlockId::DeepslateIronOre || id == BlockId::DeepslateCopperOre ||
                id == BlockId::DeepslateGoldOre || id == BlockId::DeepslateRedstoneOre ||
                id == BlockId::DeepslateEmeraldOre || id == BlockId::DeepslateLapisOre ||
                id == BlockId::DeepslateDiamondOre) ++deepslateCount;
        }
    }
    assert(deepslateCount > 0u);
    assert(world.ChunkCount() == 9u);
    const int height = world.GetSurfaceHeight(8, 8);
    assert(height >= 3 && height < VoxelWorld::kWorldHeight);
    assert(world.IsSolid(8, height, 8));
    assert(!world.IsSolid(8, height + 1, 8));

    assert(world.SetBlock(8, height + 5, 8, BlockId::Stone));
    Vec3 broken{};
    BlockId brokenType = BlockId::Air;
    assert(world.BreakRay({8.5f, static_cast<float>(height) + 7.5f, 8.5f},
                          {0.0f, -1.0f, 0.0f}, 5.0f, &broken, &brokenType));
    assert(brokenType == BlockId::Stone);
    assert(world.GetBlock(8, height + 5, 8) == BlockId::Air);
    assert(world.SetBlock(8, height + 5, 8, BlockId::Bedrock));
    assert(!world.BreakRay({8.5f, static_cast<float>(height) + 7.5f, 8.5f},
                           {0.0f, -1.0f, 0.0f}, 5.0f));
    assert(world.GetBlock(8, height + 5, 8) == BlockId::Bedrock);
    assert(world.SetBlock(8, height + 5, 8, BlockId::Portal));
    assert(!world.BreakRay({8.5f, static_cast<float>(height) + 7.5f, 8.5f},
                           {0.0f, -1.0f, 0.0f}, 5.0f));
    assert(world.GetBlock(8, height + 5, 8) == BlockId::Portal);
    assert(world.SetBlock(8, height + 5, 8, BlockId::Air));
    // Biome decoration and rivers may legitimately occupy this cell in the new terrain.
    assert(world.SetBlock(8, height + 1, 8, BlockId::Air));
    assert(world.PlaceRay({8.5f, static_cast<float>(height) + 7.5f, 8.5f},
                          {0.0f, -1.0f, 0.0f}, 30.0f, BlockId::Dirt));
    assert(world.GetBlock(8, height + 1, 8) == BlockId::Dirt);

    std::vector<WorldVertex> mesh;
    world.BuildMesh(mesh);
    assert(!mesh.empty());
    assert(mesh.size() % 3u == 0u);
    // A 4x4x4 opaque stone volume inside one chunk must collapse to six
    // greedy rectangles (36 vertices), not 384 individual block faces.
    VoxelWorld greedyWorld(0x47524545u);
    greedyWorld.GenerateAround(8, 8, 0);
    std::vector<WorldVertex> greedyBefore;
    greedyWorld.BuildMesh(greedyBefore, 8, 8, 0);
    for (int y = 80; y < 84; ++y)
        for (int z = 4; z < 8; ++z)
            for (int x = 4; x < 8; ++x)
                assert(greedyWorld.SetBlock(x, y, z, BlockId::Stone));
    std::vector<WorldVertex> greedyAfter;
    greedyWorld.BuildMesh(greedyAfter, 8, 8, 0);
    assert(greedyAfter.size() == greedyBefore.size() + 36u);
    std::vector<WorldVertex> greedyTransparent;
    std::vector<ChunkMeshRange> greedyRanges;
    std::vector<WorldVertex> greedyOpaque;
    greedyWorld.BuildMesh(greedyOpaque, 8, 8, 0, &greedyTransparent, &greedyRanges);
    assert(greedyRanges.size() == 1u);
    assert(greedyRanges[0].chunkX == 0 && greedyRanges[0].chunkZ == 0);
    assert(greedyRanges[0].opaqueFirst == 0u);
    assert(greedyRanges[0].opaqueCount == greedyOpaque.size());
    assert(greedyRanges[0].lodLevel == 0);
    assert(greedyOpaque.size() >= 36u);
    assert(greedyRanges[0].revision > 0u);

    // A second mesh collection without world edits must reuse every cached
    // chunk revision, including the visible outer border (the old renderer
    // rebuilt that ring unconditionally and stalled exploration).
    const std::uint64_t initialGreedyRevision = greedyRanges[0].revision;
    std::vector<WorldVertex> cachedOpaque;
    std::vector<WorldVertex> cachedTransparent;
    std::vector<ChunkMeshRange> cachedRanges;
    greedyWorld.BuildMesh(cachedOpaque, 8, 8, 0, &cachedTransparent, &cachedRanges);
    assert(cachedRanges.size() == 1u);
    assert(cachedRanges[0].revision == initialGreedyRevision);
    assert(cachedOpaque.size() == greedyOpaque.size());
    assert(cachedTransparent.size() == greedyTransparent.size());

    // Frame-budgeted startup: the first pass emits the nearest chunk only,
    // then fills the visible square over subsequent passes without rebuilding
    // previously completed chunks.
    VoxelWorld budgetWorld(0x42554447u);
    budgetWorld.GenerateAround(8, 8, 1);
    std::vector<WorldVertex> budgetOpaque;
    std::vector<WorldVertex> budgetTransparent;
    std::vector<ChunkMeshRange> budgetRanges;
    assert(budgetWorld.HasPendingMeshBuild(8, 8, 1));
    budgetWorld.BuildMesh(budgetOpaque, 8, 8, 1,
                          &budgetTransparent, &budgetRanges, 1u);
    assert(budgetRanges.size() == 1u);
    const auto firstBudgetRevision = budgetRanges.front().revision;
    assert(budgetWorld.HasPendingMeshBuild(8, 8, 1));
    for (int frame = 0; frame < 10 &&
         budgetWorld.HasPendingMeshBuild(8, 8, 1); ++frame) {
        budgetWorld.BuildMesh(budgetOpaque, 8, 8, 1,
                              &budgetTransparent, &budgetRanges, 1u);
        assert(budgetRanges.size() == static_cast<std::size_t>(frame + 2));
        assert(budgetRanges.front().revision == firstBudgetRevision);
    }
    assert(budgetRanges.size() == 9u);
    assert(!budgetWorld.HasPendingMeshBuild(8, 8, 1));

    VoxelWorld revisionWorld(0x52455631u);
    revisionWorld.GenerateAround(8, 8, 1);
    std::vector<WorldVertex> revisionOpaque;
    std::vector<WorldVertex> revisionTransparent;
    std::vector<ChunkMeshRange> revisionRanges;
    revisionWorld.BuildMesh(revisionOpaque, 8, 8, 1, &revisionTransparent, &revisionRanges);
    assert(revisionRanges.size() == 9u);
    std::array<std::uint64_t, 9> beforeRevisions{};
    for (const ChunkMeshRange& range : revisionRanges) {
        const auto index = static_cast<std::size_t>(
            (range.chunkX + 1) * 3 + (range.chunkZ + 1));
        beforeRevisions[index] = range.revision;
    }
    revisionWorld.BuildMesh(revisionOpaque, 8, 8, 1, &revisionTransparent, &revisionRanges);
    for (std::size_t i = 0; i < revisionRanges.size(); ++i)
        assert(revisionRanges[i].revision == beforeRevisions[i]);

    // Editing one interior chunk must increment only that chunk's revision;
    // unrelated chunks retain their GPU buffers.
    const int revisionGround = revisionWorld.GetSurfaceHeight(8, 8);
    assert(revisionWorld.SetBlock(8, VoxelWorld::kWorldHeight - 1, 8, BlockId::Stone));
    revisionWorld.BuildMesh(revisionOpaque, 8, 8, 1, &revisionTransparent, &revisionRanges);
    bool centreRevisionAdvanced = false;
    for (const ChunkMeshRange& range : revisionRanges) {
        if (range.chunkX == 0 && range.chunkZ == 0) {
            assert(range.revision > beforeRevisions[4]);
            centreRevisionAdvanced = true;
        } else {
            const auto index = static_cast<std::size_t>(
                (range.chunkX + 1) * 3 + (range.chunkZ + 1));
            assert(range.revision == beforeRevisions[index]);
        }
    }
    assert(centreRevisionAdvanced);

    // Every emitted terrain triangle must contain finite attributes, a unit
    // normal, non-degenerate winding consistent with that normal, and a valid
    // catalog tile. This catches broken face order and atlas references.
    const auto validateWorldMesh = [](const std::vector<WorldVertex>& vertices) {
        assert(vertices.size() % 3u == 0u);
        for (const WorldVertex& vertex : vertices) {
            assert(std::isfinite(vertex.x) && std::isfinite(vertex.y) && std::isfinite(vertex.z));
            assert(std::isfinite(vertex.nx) && std::isfinite(vertex.ny) && std::isfinite(vertex.nz));
            assert(std::isfinite(vertex.u) && std::isfinite(vertex.v));
            const float normalLength = std::sqrt(vertex.nx * vertex.nx +
                vertex.ny * vertex.ny + vertex.nz * vertex.nz);
            assert(normalLength > 0.98f && normalLength < 1.02f);
            assert(vertex.atlasTile >= 0.0f);
            assert(vertex.atlasTile < static_cast<float>(GetBlockDefinitionCount() * 3u));
        }
        for (std::size_t i = 0; i + 2u < vertices.size(); i += 3u) {
            const WorldVertex& a = vertices[i];
            const WorldVertex& b = vertices[i + 1u];
            const WorldVertex& c = vertices[i + 2u];
            const float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
            const float acx = c.x - a.x, acy = c.y - a.y, acz = c.z - a.z;
            const float nx = aby * acz - abz * acy;
            const float ny = abz * acx - abx * acz;
            const float nz = abx * acy - aby * acx;
            const float area = std::sqrt(nx * nx + ny * ny + nz * nz);
            assert(area > 0.00001f);
            assert(nx * a.nx + ny * a.ny + nz * a.nz > 0.00001f);
            assert(nx * b.nx + ny * b.ny + nz * b.nz > 0.00001f);
            assert(nx * c.nx + ny * c.ny + nz * c.nz > 0.00001f);
        }
    };
    validateWorldMesh(revisionOpaque);
    validateWorldMesh(revisionTransparent);

    // The nearest two rings retain full block detail; only the outer
    // Far chunks must use compact surface-heightfield LOD2 while the
    // nearest two rings retain full voxel geometry.
    VoxelWorld lodWorld(0x4c4f4431u);
    lodWorld.GenerateAround(8, 8, 4);
    std::size_t generatedRuinBlocks = 0u;
    for (int chunkZ = -4; chunkZ <= 4; ++chunkZ) {
        for (int chunkX = -4; chunkX <= 4; ++chunkX) {
            for (int y = 0; y < VoxelWorld::kWorldHeight; ++y) {
                for (int z = 0; z < VoxelWorld::kChunkSize; ++z) {
                    for (int x = 0; x < VoxelWorld::kChunkSize; ++x) {
                        const BlockId id = lodWorld.GetBlock(
                            chunkX * VoxelWorld::kChunkSize + x, y,
                            chunkZ * VoxelWorld::kChunkSize + z);
                        if (id == BlockId::Stonebrick) ++generatedRuinBlocks;
                    }
                }
            }
        }
    }
    assert(generatedRuinBlocks > 0u);
    VoxelWorld sameRuinSeed(0x4c4f4431u);
    sameRuinSeed.GenerateAround(8, 8, 4);
    std::size_t repeatedRuinBlocks = 0u;
    for (int chunkZ = -4; chunkZ <= 4; ++chunkZ)
        for (int chunkX = -4; chunkX <= 4; ++chunkX)
            for (int y = 0; y < VoxelWorld::kWorldHeight; ++y)
                for (int z = 0; z < VoxelWorld::kChunkSize; ++z)
                    for (int x = 0; x < VoxelWorld::kChunkSize; ++x)
                        if (sameRuinSeed.GetBlock(chunkX * VoxelWorld::kChunkSize + x, y,
                                chunkZ * VoxelWorld::kChunkSize + z) == BlockId::Stonebrick)
                            ++repeatedRuinBlocks;
    assert(repeatedRuinBlocks == generatedRuinBlocks);

    std::vector<WorldVertex> lodMesh;
    std::vector<ChunkMeshRange> lodRanges;
    lodWorld.BuildMesh(lodMesh, 8, 8, 4, nullptr, &lodRanges);
    bool foundLod2Chunk = false;
    bool foundLod1Chunk = false;
    std::size_t largestFarMesh = 0;
    for (const ChunkMeshRange& range : lodRanges) {
        const int distance = std::max(std::abs(range.chunkX), std::abs(range.chunkZ));
        if (distance <= 2) {
            assert(range.lodLevel == 0);
        }
        if (distance == 3) {
            assert(range.lodLevel == 1);
            foundLod1Chunk = true;
        }
        if (distance >= 4) {
            assert(range.lodLevel == 2);
            foundLod2Chunk = true;
            largestFarMesh = std::max(largestFarMesh, range.opaqueCount);
        }
    }
    assert(foundLod1Chunk && foundLod2Chunk);
    assert(largestFarMesh > 0u);
    assert(largestFarMesh <= 7680u); // 256 top quads + at most four exposed sides per column.

    // Frustum AABB tests use a simple [-4,+4] cube around the camera.
    CameraFrustum testFrustum;
    testFrustum.valid = true;
    testFrustum.planes = {{
        { 1.0f, 0.0f, 0.0f, 4.0f}, {-1.0f, 0.0f, 0.0f, 4.0f},
        { 0.0f, 1.0f, 0.0f, 4.0f}, { 0.0f,-1.0f, 0.0f, 4.0f},
        { 0.0f, 0.0f, 1.0f, 4.0f}, { 0.0f, 0.0f,-1.0f, 4.0f}
    }};
    assert(testFrustum.IntersectsAabb(-1.0f,-1.0f,-1.0f,1.0f,1.0f,1.0f));
    assert(!testFrustum.IntersectsAabb(8.0f,0.0f,0.0f,10.0f,1.0f,1.0f));
    assert(!testFrustum.IntersectsAabb(-1.0f,-1.0f,8.0f,1.0f,1.0f,10.0f));

    // A generated but excluded chunk must not hide the last visible ring's
    // boundary surfaces, as if its neighbour were unloaded.
    VoxelWorld edgeWorld(0x45444745u);
    edgeWorld.GenerateAround(8, 8, 1);
    for (int y = 80; y < 84; ++y)
        for (int z = 4; z < 8; ++z)
            for (int x = 15; x < 16; ++x)
                assert(edgeWorld.SetBlock(x, y, z, BlockId::Stone));
    std::vector<WorldVertex> edgeMesh;
    edgeWorld.BuildMesh(edgeMesh, 8, 8, 0);
    assert(edgeMesh.size() >= 36u);

    std::vector<WorldVertex> transparentMesh;
    world.SetBlock(8, height + 8, 8, BlockId::Water);
    world.BuildMesh(mesh, 8, 8, 1, &transparentMesh);
    assert(!transparentMesh.empty());
    world.SetBlock(8, height + 8, 8, BlockId::Air);
    world.BuildMesh(mesh);

    const std::string path = (std::filesystem::temp_directory_path() / "minefade-native-world-test.mfworld").string();
    const Vec3 savedPlayer{8.25f, static_cast<float>(height) + 1.1f, 8.5f};
    const std::array<std::uint32_t, 36> inventory{
        static_cast<std::uint32_t>(BlockId::SeaLantern) * 1000u + 16u,
        static_cast<std::uint32_t>(BlockId::Dirt) * 1000u + 4u, 0u,0u,0u,0u,0u,0u,0u};
    assert(world.Save(path, savedPlayer, inventory));

    VoxelWorld loaded(1u);
    Vec3 loadedPlayer{};
    std::array<std::uint32_t, 36> loadedInventory{};
    assert(loaded.Load(path, loadedPlayer, loadedInventory));
    assert(loaded.ChunkCount() == 9u);
    assert(loaded.GetBlock(8, height + 1, 8) == BlockId::Dirt);
    assert(std::abs(loadedPlayer.x - savedPlayer.x) < 0.001f);
    assert(loadedInventory == inventory);
    std::filesystem::remove(path);

    // A truncated/invalid world must fail atomically: it must not partially
    // overwrite the currently loaded world, player position or inventory.
    const std::string damagedPath = path + ".damaged";
    {
        std::ofstream damaged(damagedPath, std::ios::binary | std::ios::trunc);
        damaged.write("MF", 2);
    }
    const Vec3 beforeFailedLoad = loadedPlayer;
    const auto inventoryBeforeFailedLoad = loadedInventory;
    const auto chunksBeforeFailedLoad = loaded.ChunkCount();
    assert(!loaded.Load(damagedPath, loadedPlayer, loadedInventory));
    assert(loaded.ChunkCount() == chunksBeforeFailedLoad);
    assert(std::abs(loadedPlayer.x - beforeFailedLoad.x) < 0.001f);
    assert(std::abs(loadedPlayer.y - beforeFailedLoad.y) < 0.001f);
    assert(std::abs(loadedPlayer.z - beforeFailedLoad.z) < 0.001f);
    assert(loadedInventory == inventoryBeforeFailedLoad);
    std::filesystem::remove(damagedPath);

    Inventory slots;
    assert(slots.SelectedItemId() == static_cast<std::uint16_t>(BlockId::Grass));
    assert(slots.Add(static_cast<std::uint16_t>(BlockId::Grass), 2));
    assert(slots.counts[0] == 2018u);
    assert(slots.ConsumeSelected());
    assert(slots.counts[0] == 2017u);
    assert(slots.Add(static_cast<std::uint16_t>(BlockId::Stone), 3));
    assert(slots.SelectedItemId() == static_cast<std::uint16_t>(BlockId::Grass));
    assert(slots.Add(static_cast<std::uint16_t>(BlockId::SeaLantern), 5));
    assert(slots.counts[2] == static_cast<std::uint32_t>(BlockId::SeaLantern) * 1000u + 5u);
    slots.selectedSlot = 2;
    assert(slots.ConsumeSelected());
    assert(slots.counts[2] == static_cast<std::uint32_t>(BlockId::SeaLantern) * 1000u + 4u);
    Inventory spill;
    spill.counts[0] = static_cast<std::uint32_t>(BlockId::Stone) * 1000u + 64u;
    spill.counts[1] = static_cast<std::uint32_t>(BlockId::Stone) * 1000u + 62u;
    assert(spill.Add(static_cast<std::uint16_t>(BlockId::Stone), 5));
    assert(spill.counts[0] == static_cast<std::uint32_t>(BlockId::Stone) * 1000u + 64u);
    assert(spill.counts[1] == static_cast<std::uint32_t>(BlockId::Stone) * 1000u + 64u);
    assert(spill.counts[2] == static_cast<std::uint32_t>(BlockId::Stone) * 1000u + 3u);
    Inventory full;
    full.counts.fill(static_cast<std::uint32_t>(BlockId::Stone) * 1000u + 64u);
    const auto fullBefore = full.counts;
    assert(!full.CanAdd(static_cast<std::uint16_t>(BlockId::Stone), 1));
    assert(!full.Add(static_cast<std::uint16_t>(BlockId::Stone), 1));
    assert(full.counts == fullBefore);

    VoxelWorld dropWorld(773u);
    dropWorld.GenerateAround(8, 8, 0);
    const int dropGround = dropWorld.GetSurfaceHeight(8, 8);
    Player dropPlayer;
    dropPlayer.position = {8.5f, static_cast<float>(dropGround) + 1.0f, 8.5f};
    Inventory dropInventory;
    DroppedItemSystem droppedItems;
    droppedItems.Spawn({8.8f, static_cast<float>(dropGround) + 2.0f, 8.5f},
                       static_cast<std::uint16_t>(BlockId::Stone), 1);
    assert(droppedItems.Count() == 1u);
    std::vector<WorldVertex> droppedItemMesh;
    droppedItems.AppendMesh(droppedItemMesh);
    assert(!droppedItemMesh.empty());
    std::uint16_t pickedItemId = 0u;
    std::size_t pickedCount = 0u;
    for (int frame = 0; frame < 40 && droppedItems.Count() > 0u; ++frame)
        pickedCount += droppedItems.Update(dropWorld, dropPlayer, dropInventory, 0.05f, &pickedItemId);
    assert(pickedCount == 1u);
    assert(pickedItemId == static_cast<std::uint16_t>(BlockId::Stone));
    assert(droppedItems.Count() == 0u);
    bool stoneDropWasPickedUp = false;
    for (const std::uint32_t encoded : dropInventory.counts) {
        if (encoded / 1000u == static_cast<std::uint32_t>(BlockId::Stone) && encoded % 1000u == 1u)
            stoneDropWasPickedUp = true;
    }
    assert(stoneDropWasPickedUp);

    assert(GetBlockDefinitionCount() == static_cast<std::size_t>(BlockId::Count));
    assert(GetBlockDefinitionCount() == 414u);
    assert(FindBlockDefinition("minefade_stick") != nullptr);
    assert(FindBlockDefinition("minefade_wooden_pickaxe") != nullptr);
    assert(FindBlockDefinition("minefade_wooden_axe") != nullptr);
    assert(FindBlockDefinition("minefade_wooden_shovel") != nullptr);
    assert(FindBlockDefinition("minefade_wooden_sword") != nullptr);
    assert(FindBlockDefinition("seaLantern") != nullptr);
    assert(FindBlockDefinition("copper_ore") != nullptr);
    assert(FindBlockDefinition("deepslate_diamond_ore") != nullptr);
    assert(FindBlockDefinition("deepslate_redstone_ore") != nullptr);
    assert(FindBlockDefinition("ancient_debris") != nullptr);
    assert(FindBlockDefinition("cherry_planks") != nullptr);
    assert(FindBlockDefinition("mangrove_propagule") != nullptr);
    assert(FindBlockDefinition("mud_bricks") != nullptr);
    assert(FindBlockDefinition("sculk_catalyst") != nullptr);
    assert(FindBlockDefinition("reinforced_deepslate") != nullptr);
    assert(FindBlockDefinition("pearlescent_froglight") != nullptr);
    assert(GetBlockDefinition(BlockId::CopperOre)->shape == BlockShape::Cube);
    assert(GetBlockDefinition(BlockId::DeepslateDiamondOre)->shape == BlockShape::Cube);
    assert(GetBlockDefinition(BlockId::AmethystCluster)->shape == BlockShape::Thin);
    assert(GetBlockDefinition(BlockId::BuddingAmethyst)->hardness < 0.0f);
    assert(FindBlockDefinition("copper_ore") != nullptr);
    assert(FindBlockDefinition("deepslate_diamond_ore") != nullptr);
    assert(FindBlockDefinition("deepslate_redstone_ore") != nullptr);
    assert(FindBlockDefinition("ancient_debris") != nullptr);
    assert(GetBlockDefinition(BlockId::CopperOre)->shape == BlockShape::Cube);
    assert(GetBlockDefinition(BlockId::DeepslateDiamondOre)->shape == BlockShape::Cube);
    assert(GetBlockDefinition(BlockId::AmethystCluster)->shape == BlockShape::Thin);
    assert(GetBlockDefinition(BlockId::BuddingAmethyst)->hardness < 0.0f);
    const BlockDefinition* flowingWaterDefinition = GetBlockDefinition(BlockId::FlowingWater);
    assert(flowingWaterDefinition != nullptr);
    assert(flowingWaterDefinition->dropId == static_cast<std::uint16_t>(BlockId::Water));
    assert(HasBlockFlag(BlockId::Water, BlockFluid));
    assert(HasBlockFlag(BlockId::Water, BlockTransparent));
    assert(!HasBlockFlag(BlockId::Water, BlockCollidable));
    assert(GetBlockDefinition(BlockId::BrownMushroomBlock)->shape == BlockShape::Cube);
    assert(GetBlockDefinition(BlockId::NetherWartBlock)->shape == BlockShape::Cube);
    assert(GetBlockDefinition(BlockId::EndGateway)->shape == BlockShape::Thin);
    assert(!HasBlockFlag(BlockId::EndGateway, BlockPlaceable));
    assert(HasBlockFlag(BlockId::SeaLantern, BlockEmissive));
    assert(!HasBlockFlag(BlockId::Sapling, BlockEmissive));
    assert(HasBlockFlag(BlockId::Barrier, BlockCollidable));
    assert(!HasBlockFlag(BlockId::Barrier, BlockPlaceable));
    const BlockDefinition* grassDefinition = GetBlockDefinition(BlockId::Grass);
    assert(grassDefinition != nullptr && grassDefinition->nameEs != nullptr);
    for (std::size_t i = 0; i < GetBlockDefinitionCount(); ++i) {
        const auto* definition = GetBlockDefinition(static_cast<BlockId>(i));
        assert(definition != nullptr && definition->key != nullptr && definition->nameEs != nullptr);
        assert(definition->nameEs[0] != '\0');
        assert(definition->stackLimit > 0u);
        assert(definition->dropId < GetBlockDefinitionCount());
        float u0=0,v0=0,u1=0,v1=0;
        for (const std::uint16_t tile : {definition->tileTop, definition->tileSide, definition->tileBottom}) {
            GetAtlasUv(tile,u0,v0,u1,v1);
            assert(u0 >= 0.0f && v0 >= 0.0f && u1 <= 1.0f && v1 <= 1.0f && u1 > u0 && v1 > v0);
        }
    }

    Player player;
    player.grounded = true;
    player.Jump();
    assert(player.verticalVelocity > 0.0f);
    assert(!player.grounded);

    Player renderedPlayer;
    renderedPlayer.position = {4.0f, 10.0f, 7.0f};
    renderedPlayer.yaw = 0.0f;
    const std::array<int,6> avatarChoices{{5,4,1,3,4,2}};
    renderedPlayer.SetAppearance(avatarChoices);
    std::vector<WorldVertex> playerMesh;
    renderedPlayer.AppendMesh(playerMesh);
    assert(playerMesh.size() >= 400u);
    assert(playerMesh.size() % 3u == 0u);
    for (std::size_t i = 0; i + 2u < playerMesh.size(); i += 3u) {
        const WorldVertex& a = playerMesh[i];
        const WorldVertex& b = playerMesh[i + 1u];
        const WorldVertex& d = playerMesh[i + 2u];
        const float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        const float adx = d.x - a.x, ady = d.y - a.y, adz = d.z - a.z;
        const float nx = aby * adz - abz * ady;
        const float ny = abz * adx - abx * adz;
        const float nz = abx * ady - aby * adx;
        assert(nx * a.nx + ny * a.ny + nz * a.nz > 0.0f);
    }
    // The first 18 triangles are the intentionally broad contact shadow;
    // measure the articulated character body separately from its ground projection.
    constexpr std::size_t shadowVertexCount = 18u * 3u;
    assert(playerMesh.size() > shadowVertexCount);
    float minX = playerMesh[shadowVertexCount].x, maxX = playerMesh[shadowVertexCount].x;
    float minZ = playerMesh[shadowVertexCount].z, maxZ = playerMesh[shadowVertexCount].z;
    for (std::size_t i = shadowVertexCount; i < playerMesh.size(); ++i) {
        const WorldVertex& vertex = playerMesh[i];
        minX = std::min(minX, vertex.x);
        maxX = std::max(maxX, vertex.x);
        minZ = std::min(minZ, vertex.z);
        maxZ = std::max(maxZ, vertex.z);
    }
    assert((maxZ - minZ) > (maxX - minX) * 1.35f);
    renderedPlayer.yaw = 1.5707963f;
    playerMesh.clear();
    renderedPlayer.AppendMesh(playerMesh);
    minX = maxX = playerMesh[shadowVertexCount].x;
    minZ = maxZ = playerMesh[shadowVertexCount].z;
    for (std::size_t i = shadowVertexCount; i < playerMesh.size(); ++i) {
        const WorldVertex& vertex = playerMesh[i];
        minX = std::min(minX, vertex.x);
        maxX = std::max(maxX, vertex.x);
        minZ = std::min(minZ, vertex.z);
        maxZ = std::max(maxZ, vertex.z);
    }
    assert((maxX - minX) > (maxZ - minZ) * 1.35f);

    VoxelWorld collisionWorld(44u);
    collisionWorld.GenerateAround(8, 8, 1);
    const int collisionGround = collisionWorld.GetSurfaceHeight(8, 8);
    collisionWorld.SetBlock(9, collisionGround + 1, 8, BlockId::Stone);
    collisionWorld.SetBlock(9, collisionGround + 2, 8, BlockId::Stone);
    Player collisionPlayer;
    collisionPlayer.position = {8.5f, static_cast<float>(collisionGround) + 1.0f, 8.5f};
    collisionPlayer.yaw = 0.0f;
    collisionPlayer.SetMovement(0.0f, 1.0f);
    collisionPlayer.Update(collisionWorld, 0.05f);
    assert(collisionPlayer.position.x < 8.72f);

    collisionWorld.SetBlock(8, collisionGround + 1, 8, BlockId::Stone);
    collisionWorld.SetBlock(8, collisionGround + 2, 8, BlockId::Stone);
    Player recoveredPlayer;
    recoveredPlayer.position = {8.5f, static_cast<float>(collisionGround) + 1.0f, 8.5f};
    assert(recoveredPlayer.ResolveSafeSpawn(collisionWorld));
    assert(!collisionWorld.IsSolid(static_cast<int>(std::floor(recoveredPlayer.position.x)),
                                   static_cast<int>(std::floor(recoveredPlayer.position.y + 0.65f)),
                                   static_cast<int>(std::floor(recoveredPlayer.position.z))));
    assert(recoveredPlayer.position.y > 0.0f);
    assert(recoveredPlayer.position.y < static_cast<float>(VoxelWorld::kWorldHeight - 2));

    EntitySystem entities;
    entities.SpawnInitial(world);
    assert(entities.Count() == 3u);
    Player testPlayer;
    testPlayer.position = {8.0f, 14.0f, 8.0f};
    entities.Update(world, testPlayer, 0.016f);
    std::vector<WorldVertex> entityMesh;
    entities.AppendMesh(entityMesh);
    assert(!entityMesh.empty());
    assert(entities.SpawnEntity({8.0f, 0.0f, 8.0f}, 3, world)); // pig
    assert(entities.SpawnEntity({8.0f, 0.0f, 8.0f}, 4, world)); // zombie
    assert(entities.SpawnEntity({8.0f, 0.0f, 8.0f}, 5, world)); // skeleton
    assert(entities.SpawnEntity({8.0f, 0.0f, 8.0f}, 6, world)); // rabbit
    assert(entities.Count() == 7u);
    entityMesh.clear();
    entities.AppendMesh(entityMesh);
    assert(entityMesh.size() > 0u);
    assert(!entities.SpawnEntity({8.0f,0.0f,8.0f},7,world)); // unknown entity kind rejected

    EnvironmentSystem environment;
    const float initialDaylight = environment.Daylight();
    environment.Update(10.0f);
    assert(environment.Daylight() >= 0.17f && environment.Daylight() <= 1.0f);
    assert(environment.TimeOfDay() != 0.25f);
    assert(initialDaylight > 0.0f);
    environment.SetTimeOfDay(0.75f);
    assert(std::abs(environment.TimeOfDay() - 0.75f) < 0.001f);
    assert(environment.Daylight() < 0.5f);

    const std::size_t initialMeshSize = mesh.size();
    world.GenerateAround(40, 40, 1);
    assert(world.ChunkCount() == 17u);
    assert(world.GetBlock(8, height + 1, 8) == BlockId::Dirt);
    world.BuildMesh(mesh, 40, 40, 1);
    const std::size_t visibleMeshSize = mesh.size();
    world.BuildMesh(mesh);
    assert(mesh.size() > visibleMeshSize);
    assert(visibleMeshSize > 0u && initialMeshSize > 0u);
    assert(world.Save(path, savedPlayer, inventory));
    VoxelWorld streamedLoaded;
    Vec3 streamedPlayer{};
    std::array<std::uint32_t, 36> streamedInventory{};
    assert(streamedLoaded.Load(path, streamedPlayer, streamedInventory));
    assert(streamedLoaded.ChunkCount() == 17u);
    assert(streamedLoaded.GetBlock(8, height + 1, 8) == BlockId::Dirt);
    std::filesystem::remove(path);

    VoxelWorld asyncWorld(987u);
    asyncWorld.GenerateAround(0, 0, 0);
    asyncWorld.RequestGenerateAround(32, 32, 1);
    assert(asyncWorld.PendingChunkCount() > 0u);
    asyncWorld.WaitForChunkGeneration();
    assert(asyncWorld.PendingChunkCount() == 0u);
    assert(asyncWorld.ChunkCount() == 10u);
    std::vector<WorldVertex> asyncMesh;
    asyncWorld.BuildMesh(asyncMesh, 32, 32, 1);
    assert(!asyncMesh.empty());
    std::cout << "Minefade world smoke tests passed.\n";
    return 0;
}
