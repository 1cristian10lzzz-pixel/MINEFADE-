#include "../app/src/main/cpp/world/VoxelWorld.h"
#include "../app/src/main/cpp/game/Player.h"

#include <array>
#include <cassert>
#include <cmath>
#include <filesystem>
#include <iostream>

using namespace minefade;

int main() {
    VoxelWorld world(123456u);
    VoxelWorld deterministic(123456u);
    world.GenerateAround(8, 8, 1);
    deterministic.GenerateAround(8, 8, 1);
    assert(world.GetSurfaceHeight(8, 8) == deterministic.GetSurfaceHeight(8, 8));
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
    assert(world.PlaceRay({8.5f, static_cast<float>(height) + 7.5f, 8.5f},
                          {0.0f, -1.0f, 0.0f}, 30.0f, BlockId::Dirt));
    assert(world.GetBlock(8, height + 1, 8) == BlockId::Dirt);

    std::vector<WorldVertex> mesh;
    world.BuildMesh(mesh);
    assert(!mesh.empty());
    assert(mesh.size() % 3u == 0u);
    std::vector<WorldVertex> transparentMesh;
    world.SetBlock(8, height + 8, 8, BlockId::Water);
    world.BuildMesh(mesh, 8, 8, 1, &transparentMesh);
    assert(!transparentMesh.empty());
    world.SetBlock(8, height + 8, 8, BlockId::Air);
    world.BuildMesh(mesh);

    const std::string path = (std::filesystem::temp_directory_path() / "minefade-native-world-test.mfworld").string();
    const Vec3 savedPlayer{8.25f, static_cast<float>(height) + 1.1f, 8.5f};
    const std::array<std::uint32_t, 9> inventory{
        static_cast<std::uint32_t>(BlockId::SeaLantern) * 1000u + 16u,
        static_cast<std::uint32_t>(BlockId::Dirt) * 1000u + 4u, 0u,0u,0u,0u,0u,0u,0u};
    assert(world.Save(path, savedPlayer, inventory));

    VoxelWorld loaded(1u);
    Vec3 loadedPlayer{};
    std::array<std::uint32_t, 9> loadedInventory{};
    assert(loaded.Load(path, loadedPlayer, loadedInventory));
    assert(loaded.ChunkCount() == 9u);
    assert(loaded.GetBlock(8, height + 1, 8) == BlockId::Dirt);
    assert(std::abs(loadedPlayer.x - savedPlayer.x) < 0.001f);
    assert(loadedInventory == inventory);
    std::filesystem::remove(path);

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
    assert(GetBlockDefinitionCount() == 306u);
    assert(FindBlockDefinition("seaLantern") != nullptr);
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
    float minX = playerMesh.front().x, maxX = playerMesh.front().x;
    float minZ = playerMesh.front().z, maxZ = playerMesh.front().z;
    for (const WorldVertex& vertex : playerMesh) {
        minX = std::min(minX, vertex.x);
        maxX = std::max(maxX, vertex.x);
        minZ = std::min(minZ, vertex.z);
        maxZ = std::max(maxZ, vertex.z);
    }
    assert((maxZ - minZ) > (maxX - minX) * 1.35f);
    renderedPlayer.yaw = 1.5707963f;
    playerMesh.clear();
    renderedPlayer.AppendMesh(playerMesh);
    minX = maxX = playerMesh.front().x;
    minZ = maxZ = playerMesh.front().z;
    for (const WorldVertex& vertex : playerMesh) {
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
    entities.Update(world, 0.016f);
    std::vector<WorldVertex> entityMesh;
    entities.AppendMesh(entityMesh);
    assert(!entityMesh.empty());

    EnvironmentSystem environment;
    const float initialDaylight = environment.Daylight();
    environment.Update(10.0f);
    assert(environment.Daylight() >= 0.17f && environment.Daylight() <= 1.0f);
    assert(environment.TimeOfDay() != 0.25f);
    assert(initialDaylight > 0.0f);

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
    std::array<std::uint32_t, 9> streamedInventory{};
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
