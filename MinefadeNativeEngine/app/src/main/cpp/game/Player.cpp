#include "Player.h"

#include <algorithm>
#include <cmath>

namespace minefade {
namespace {
constexpr float kPi = 3.14159265358979323846f;
int FloorToInt(float value) { return static_cast<int>(std::floor(value)); }

Vec3 CrossPlayer(const Vec3& a, const Vec3& b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

Vec3 PlayerLocalPoint(const Vec3& anchor, float yaw, float x, float y, float z) {
    // x-right, y-up, z-back is a right-handed basis. Keeping this winding is
    // essential because the world renderer back-face culls OpenGL triangles.
    const Vec3 right{-std::sin(yaw), 0.0f, std::cos(yaw)};
    const Vec3 backward{-std::cos(yaw), 0.0f, -std::sin(yaw)};
    return {
        anchor.x + right.x * x + backward.x * z,
        anchor.y + y,
        anchor.z + right.z * x + backward.z * z
    };
}

void AppendPlayerBox(std::vector<WorldVertex>& output, const Vec3& anchor, float yaw,
                     const Vec3& localCenter, const Vec3& half,
                     const std::array<float, 3>& color) {
    static constexpr std::array<std::array<std::array<float, 3>, 4>, 6> faces{{
        {{{{1,0,0}},{{1,1,0}},{{1,1,1}},{{1,0,1}}}},
        {{{{0,0,1}},{{0,1,1}},{{0,1,0}},{{0,0,0}}}},
        {{{{0,1,1}},{{1,1,1}},{{1,1,0}},{{0,1,0}}}},
        {{{{0,0,0}},{{1,0,0}},{{1,0,1}},{{0,0,1}}}},
        {{{{1,0,1}},{{1,1,1}},{{0,1,1}},{{0,0,1}}}},
        {{{{0,0,0}},{{0,1,0}},{{1,1,0}},{{1,0,0}}}}
    }};
    static constexpr std::array<float, 6> shade{{0.84f, 0.84f, 1.0f, 0.56f, 0.91f, 0.91f}};
    static constexpr std::array<int, 6> order{{0, 1, 2, 0, 2, 3}};
    for (std::size_t face = 0; face < faces.size(); ++face) {
        std::array<Vec3, 4> points{};
        for (std::size_t i = 0; i < points.size(); ++i) {
            const auto& p = faces[face][i];
            const float lx = localCenter.x + (p[0] * 2.0f - 1.0f) * half.x;
            const float ly = localCenter.y + (p[1] * 2.0f - 1.0f) * half.y;
            const float lz = localCenter.z + (p[2] * 2.0f - 1.0f) * half.z;
            points[i] = PlayerLocalPoint(anchor, yaw, lx, ly, lz);
        }
        const Vec3 edgeA{points[1].x - points[0].x, points[1].y - points[0].y, points[1].z - points[0].z};
        const Vec3 edgeB{points[2].x - points[0].x, points[2].y - points[0].y, points[2].z - points[0].z};
        Vec3 normal = CrossPlayer(edgeA, edgeB);
        const float length = std::sqrt(std::max(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z, 0.000001f));
        normal = {normal.x / length, normal.y / length, normal.z / length};
        for (int index : order) {
            const Vec3& p = points[static_cast<std::size_t>(index)];
            const float light = shade[face];
            output.push_back({p.x, p.y, p.z, normal.x, normal.y, normal.z,
                              color[0] * light, color[1] * light, color[2] * light, 0.5f, 0.5f});
        }
    }
}
}

Vec3 Player::Forward() const {
    const float cp = std::cos(pitch);
    return {std::cos(yaw) * cp, std::sin(pitch), std::sin(yaw) * cp};
}

Vec3 Player::CameraPosition() const { return {position.x, position.y + 1.58f, position.z}; }

void Player::SetMovement(float x, float y) {
    const float length = std::sqrt(x * x + y * y);
    if (length > 1.0f) { x /= length; y /= length; }
    movementX = std::clamp(x, -1.0f, 1.0f);
    movementY = std::clamp(y, -1.0f, 1.0f);
}

void Player::Rotate(float deltaX, float deltaY) {
    yaw += deltaX * 0.0042f;
    pitch = std::clamp(pitch - deltaY * 0.0042f, -1.35f, 1.35f);
    if (yaw > kPi) yaw -= 2.0f * kPi;
    if (yaw < -kPi) yaw += 2.0f * kPi;
}

void Player::Jump() {
    if (!grounded) return;
    verticalVelocity = 7.2f;
    grounded = false;
}

bool Player::ResolveSafeSpawn(const VoxelWorld& world) {
    constexpr float bodyRadius = 0.29f;
    constexpr float bodyHeight = 1.80f;
    const auto clearAt = [&world](float x, float y, float z) {
        const int minX = FloorToInt(x - bodyRadius);
        const int maxX = FloorToInt(x + bodyRadius);
        const int minZ = FloorToInt(z - bodyRadius);
        const int maxZ = FloorToInt(z + bodyRadius);
        const int minY = FloorToInt(y + 0.06f);
        const int maxY = FloorToInt(y + bodyHeight - 0.06f);
        for (int by = minY; by <= maxY; ++by)
            for (int bz = minZ; bz <= maxZ; ++bz)
                for (int bx = minX; bx <= maxX; ++bx)
                    if (world.IsSolid(bx, by, bz)) return false;
        return true;
    };

    if (std::isfinite(position.x) && std::isfinite(position.y) && std::isfinite(position.z) &&
        position.y >= 1.0f && position.y <= static_cast<float>(VoxelWorld::kWorldHeight - 2) &&
        clearAt(position.x, position.y, position.z)) {
        return true;
    }

    const int originX = std::isfinite(position.x) ? FloorToInt(position.x) : 8;
    const int originZ = std::isfinite(position.z) ? FloorToInt(position.z) : 8;
    // Search outward from the saved position first, so recovery is minimally disruptive.
    for (int radius = 0; radius <= 12; ++radius) {
        for (int dz = -radius; dz <= radius; ++dz) {
            for (int dx = -radius; dx <= radius; ++dx) {
                if (std::max(std::abs(dx), std::abs(dz)) != radius) continue;
                const int x = originX + dx;
                const int z = originZ + dz;
                const int surface = world.GetSurfaceHeight(x, z);
                const float candidateX = static_cast<float>(x) + 0.5f;
                const float candidateY = static_cast<float>(surface) + 1.001f;
                const float candidateZ = static_cast<float>(z) + 0.5f;
                if (surface <= 0 || candidateY >= static_cast<float>(VoxelWorld::kWorldHeight - 2) ||
                    !clearAt(candidateX, candidateY, candidateZ)) {
                    continue;
                }
                position = {candidateX, candidateY, candidateZ};
                verticalVelocity = 0.0f;
                movementX = movementY = 0.0f;
                grounded = false;
                return true;
            }
        }
    }
    return false;
}

void Player::AppendMesh(std::vector<WorldVertex>& output) const {
    const Vec3 anchor = position;
    const float stride = (std::abs(movementX) + std::abs(movementY) > 0.05f)
        ? std::sin(walkPhase) * 0.16f : 0.0f;

    // Original Minefade adventurer silhouette: boots, trousers, tunic, arms, hands,
    // face and hair. Every part is transformed by the player's yaw, not world axes.
    const std::array<float, 3> skin{{0.78f, 0.57f, 0.42f}};
    const std::array<float, 3> hair{{0.25f, 0.18f, 0.14f}};
    const std::array<float, 3> tunic{{0.18f, 0.43f, 0.62f}};
    const std::array<float, 3> trim{{0.88f, 0.66f, 0.28f}};
    const std::array<float, 3> trousers{{0.19f, 0.25f, 0.43f}};
    const std::array<float, 3> boots{{0.25f, 0.16f, 0.11f}};

    AppendPlayerBox(output, anchor, yaw, {0.0f, 1.08f, 0.0f}, {0.32f, 0.40f, 0.19f}, tunic);
    AppendPlayerBox(output, anchor, yaw, {0.0f, 1.37f, 0.0f}, {0.33f, 0.10f, 0.195f}, trim);

    const float armSwing = stride * 0.78f;
    AppendPlayerBox(output, anchor, yaw, {-0.43f, 1.08f, -armSwing}, {0.145f, 0.34f, 0.145f}, tunic);
    AppendPlayerBox(output, anchor, yaw, { 0.43f, 1.08f,  armSwing}, {0.145f, 0.34f, 0.145f}, tunic);
    AppendPlayerBox(output, anchor, yaw, {-0.43f, 0.68f, -armSwing}, {0.135f, 0.12f, 0.14f}, skin);
    AppendPlayerBox(output, anchor, yaw, { 0.43f, 0.68f,  armSwing}, {0.135f, 0.12f, 0.14f}, skin);

    AppendPlayerBox(output, anchor, yaw, {-0.17f, 0.43f,  stride}, {0.16f, 0.37f, 0.16f}, trousers);
    AppendPlayerBox(output, anchor, yaw, { 0.17f, 0.43f, -stride}, {0.16f, 0.37f, 0.16f}, trousers);
    AppendPlayerBox(output, anchor, yaw, {-0.17f, 0.105f, stride + 0.025f}, {0.165f, 0.105f, 0.205f}, boots);
    AppendPlayerBox(output, anchor, yaw, { 0.17f, 0.105f,-stride + 0.025f}, {0.165f, 0.105f, 0.205f}, boots);

    AppendPlayerBox(output, anchor, yaw, {0.0f, 1.63f, 0.015f}, {0.25f, 0.25f, 0.245f}, skin);
    AppendPlayerBox(output, anchor, yaw, {0.0f, 1.83f, -0.015f}, {0.255f, 0.075f, 0.255f}, hair);
    AppendPlayerBox(output, anchor, yaw, {0.0f, 1.63f, -0.225f}, {0.255f, 0.10f, 0.045f}, hair);

    // Facial features, neck trim and a waist belt keep the avatar readable at
    // third-person camera distance instead of presenting as a stack of plain cubes.
    const std::array<float, 3> eye{{0.11f, 0.14f, 0.17f}};
    const std::array<float, 3> belt{{0.11f, 0.16f, 0.21f}};
    AppendPlayerBox(output, anchor, yaw, {-0.105f, 1.66f, 0.245f}, {0.035f, 0.045f, 0.018f}, eye);
    AppendPlayerBox(output, anchor, yaw, { 0.105f, 1.66f, 0.245f}, {0.035f, 0.045f, 0.018f}, eye);
    AppendPlayerBox(output, anchor, yaw, {0.0f, 1.57f, 0.252f}, {0.045f, 0.035f, 0.018f}, trim);
    AppendPlayerBox(output, anchor, yaw, {0.0f, 0.77f, 0.0f}, {0.325f, 0.055f, 0.198f}, belt);
    AppendPlayerBox(output, anchor, yaw, {-0.22f, 1.32f, 0.0f}, {0.13f, 0.08f, 0.205f}, trim);
    AppendPlayerBox(output, anchor, yaw, { 0.22f, 1.32f, 0.0f}, {0.13f, 0.08f, 0.205f}, trim);
}

void Player::Update(const VoxelWorld& world, float deltaSeconds) {
    const float dt = std::clamp(deltaSeconds, 0.0f, 0.05f);
    const float bodyRadius = 0.29f;
    const float bodyHeight = 1.80f;
    const auto collidesAt = [&world, bodyRadius, bodyHeight](float x, float y, float z) {
        const int minX = FloorToInt(x - bodyRadius);
        const int maxX = FloorToInt(x + bodyRadius);
        const int minZ = FloorToInt(z - bodyRadius);
        const int maxZ = FloorToInt(z + bodyRadius);
        const int minY = FloorToInt(y + 0.06f);
        const int maxY = FloorToInt(y + bodyHeight - 0.06f);
        for (int blockY = minY; blockY <= maxY; ++blockY)
            for (int blockZ = minZ; blockZ <= maxZ; ++blockZ)
                for (int blockX = minX; blockX <= maxX; ++blockX)
                    if (world.IsSolid(blockX, blockY, blockZ)) return true;
        return false;
    };

    const BlockId bodyBlock = world.GetBlock(FloorToInt(position.x),
                                               FloorToInt(position.y + 0.65f),
                                               FloorToInt(position.z));
    const bool inFluid = HasBlockFlag(bodyBlock, BlockFluid);
    const float speed = inFluid ? 2.35f : 5.0f;
    const Vec3 forward{std::cos(yaw), 0.0f, std::sin(yaw)};
    const Vec3 right{-forward.z, 0.0f, forward.x};
    const float moveX = (forward.x * movementY + right.x * movementX) * speed * dt;
    const float moveZ = (forward.z * movementY + right.z * movementX) * speed * dt;
    if (std::abs(moveX) + std::abs(moveZ) > 0.0001f) {
        walkPhase += dt * (inFluid ? 5.0f : 8.5f);
        if (walkPhase > 2.0f * kPi) walkPhase -= 2.0f * kPi;
    } else {
        walkPhase = std::fmod(walkPhase, 2.0f * kPi);
    }

    // Substeps keep the player from tunnelling through a thin wall at low frame rates.
    const int horizontalSteps = std::max(1, static_cast<int>(std::ceil(
        std::max(std::abs(moveX), std::abs(moveZ)) / 0.12f)));
    const float stepX = moveX / static_cast<float>(horizontalSteps);
    const float stepZ = moveZ / static_cast<float>(horizontalSteps);
    for (int step = 0; step < horizontalSteps; ++step) {
        const float nextX = position.x + stepX;
        if (!collidesAt(nextX, position.y, position.z)) position.x = nextX;
        const float nextZ = position.z + stepZ;
        if (!collidesAt(position.x, position.y, nextZ)) position.z = nextZ;
    }

    const float gravity = inFluid ? 5.0f : 22.0f;
    verticalVelocity = std::max(verticalVelocity - gravity * dt, inFluid ? -2.0f : -32.0f);
    const float verticalMove = verticalVelocity * dt;
    const int verticalSteps = std::max(1, static_cast<int>(std::ceil(std::abs(verticalMove) / 0.10f)));
    const float verticalStep = verticalMove / static_cast<float>(verticalSteps);
    grounded = false;
    for (int step = 0; step < verticalSteps; ++step) {
        const float nextY = position.y + verticalStep;
        if (!collidesAt(position.x, nextY, position.z)) {
            position.y = nextY;
            continue;
        }
        if (verticalStep < 0.0f) {
            const int footBlockY = FloorToInt(nextY + 0.06f);
            position.y = static_cast<float>(footBlockY + 1);
            verticalVelocity = 0.0f;
            grounded = true;
        } else {
            const int headBlockY = FloorToInt(nextY + bodyHeight - 0.06f);
            position.y = static_cast<float>(headBlockY) - bodyHeight + 0.06f;
            verticalVelocity = 0.0f;
        }
        break;
    }

    if (position.y <= 0.0f) {
        position.y = 1.0f;
        verticalVelocity = 0.0f;
        grounded = true;
    }
    position.y = std::clamp(position.y, 1.0f, static_cast<float>(VoxelWorld::kWorldHeight - 2));
}

bool Inventory::CanAdd(std::uint16_t itemId, std::uint16_t amount) const {
    const BlockDefinition* definition = GetBlockDefinition(static_cast<BlockId>(itemId));
    if (definition == nullptr || itemId == 0 || amount == 0) return false;
    const std::uint32_t maximum = definition->stackLimit == 0 ? 64u : definition->stackLimit;
    std::uint32_t remaining = amount;
    for (const std::uint32_t encoded : counts) {
        if (encoded == 0 || encoded / 1000u != itemId) continue;
        const std::uint32_t quantity = encoded % 1000u;
        if (quantity < maximum) remaining -= std::min(maximum - quantity, remaining);
        if (remaining == 0) return true;
    }
    for (const std::uint32_t encoded : counts) {
        if (encoded != 0) continue;
        remaining -= std::min(maximum, remaining);
        if (remaining == 0) return true;
    }
    return false;
}

bool Inventory::Add(std::uint16_t itemId, std::uint16_t amount) {
    // Make insertion atomic: the caller can preserve the world block when the
    // inventory is full, instead of silently losing the overflow quantity.
    if (!CanAdd(itemId, amount)) return false;
    const BlockDefinition* definition = GetBlockDefinition(static_cast<BlockId>(itemId));
    if (definition == nullptr) return false;
    const std::uint32_t maximum = definition->stackLimit == 0 ? 64u : definition->stackLimit;
    std::uint32_t remaining = amount;
    for (std::uint32_t& encoded : counts) {
        if (encoded == 0 || encoded / 1000u != itemId) continue;
        const std::uint32_t quantity = encoded % 1000u;
        if (quantity >= maximum) continue;
        const std::uint32_t moved = std::min(maximum - quantity, remaining);
        encoded = static_cast<std::uint32_t>(itemId) * 1000u + quantity + moved;
        remaining -= moved;
        if (remaining == 0) return true;
    }
    for (std::uint32_t& encoded : counts) {
        if (encoded != 0) continue;
        const std::uint32_t moved = std::min(maximum, remaining);
        encoded = static_cast<std::uint32_t>(itemId) * 1000u + moved;
        remaining -= moved;
        if (remaining == 0) return true;
    }
    return false; // CanAdd guarantees this branch is unreachable.
}

std::uint16_t Inventory::SelectedItemId() const {
    if (selectedSlot >= counts.size() || counts[selectedSlot] == 0) return 0;
    return static_cast<std::uint16_t>(counts[selectedSlot] / 1000u);
}

bool Inventory::ConsumeSelected(std::uint16_t amount) {
    if (selectedSlot >= counts.size() || counts[selectedSlot] == 0 || amount == 0) return false;
    const std::uint32_t itemId = counts[selectedSlot] / 1000u;
    std::uint32_t quantity = counts[selectedSlot] % 1000u;
    if (quantity < amount) return false;
    quantity -= amount;
    counts[selectedSlot] = quantity == 0 ? 0u : itemId * 1000u + quantity;
    return true;
}

void DroppedItemSystem::Spawn(const Vec3& position, std::uint16_t itemId, std::uint16_t amount) {
    if (itemId == 0u || amount == 0u || GetBlockDefinition(static_cast<BlockId>(itemId)) == nullptr) return;
    const float angle = static_cast<float>(++spawnSequence_) * 2.39996323f;
    DroppedItem item;
    item.position = position;
    item.velocity = {std::cos(angle) * 0.85f, 2.25f, std::sin(angle) * 0.85f};
    item.itemId = itemId;
    item.amount = amount;
    item.age = 0.0f;
    drops_.push_back(item);
}

std::size_t DroppedItemSystem::Update(const VoxelWorld& world, const Player& player, Inventory& inventory,
                                      float deltaSeconds, std::uint16_t* lastPickedItemId) {
    const float dt = std::clamp(deltaSeconds, 0.0f, 0.05f);
    std::size_t pickedUp = 0u;
    for (auto it = drops_.begin(); it != drops_.end();) {
        DroppedItem& item = *it;
        item.age += dt;
        if (item.age >= 300.0f || item.position.y < -2.0f) {
            it = drops_.erase(it);
            continue;
        }

        // Small, deterministic item physics: gravity, block collision and a soft bounce.
        item.velocity.y = std::max(item.velocity.y - 17.5f * dt, -20.0f);
        const float nextX = item.position.x + item.velocity.x * dt;
        if (world.IsSolid(FloorToInt(nextX), FloorToInt(item.position.y - 0.10f),
                          FloorToInt(item.position.z))) {
            item.velocity.x *= -0.22f;
        } else {
            item.position.x = nextX;
        }
        const float nextZ = item.position.z + item.velocity.z * dt;
        if (world.IsSolid(FloorToInt(item.position.x), FloorToInt(item.position.y - 0.10f),
                          FloorToInt(nextZ))) {
            item.velocity.z *= -0.22f;
        } else {
            item.position.z = nextZ;
        }

        const float nextY = item.position.y + item.velocity.y * dt;
        const int feetBlockY = FloorToInt(nextY - 0.12f);
        if (item.velocity.y <= 0.0f &&
            world.IsSolid(FloorToInt(item.position.x), feetBlockY, FloorToInt(item.position.z))) {
            item.position.y = static_cast<float>(feetBlockY + 1) + 0.12f;
            item.velocity.y *= -0.20f;
            if (std::abs(item.velocity.y) < 0.55f) item.velocity.y = 0.0f;
            item.velocity.x *= 0.78f;
            item.velocity.z *= 0.78f;
        } else {
            const int headBlockY = FloorToInt(nextY + 0.12f);
            if (item.velocity.y > 0.0f &&
                world.IsSolid(FloorToInt(item.position.x), headBlockY, FloorToInt(item.position.z))) {
                item.velocity.y = 0.0f;
            } else {
                item.position.y = nextY;
            }
        }

        if (item.age >= 0.55f) {
            const float dx = item.position.x - player.position.x;
            const float dy = item.position.y - (player.position.y + 0.85f);
            const float dz = item.position.z - player.position.z;
            if (dx * dx + dy * dy + dz * dz <= 1.65f * 1.65f &&
                inventory.Add(item.itemId, item.amount)) {
                pickedUp += item.amount;
                if (lastPickedItemId != nullptr) *lastPickedItemId = item.itemId;
                it = drops_.erase(it);
                continue;
            }
        }
        ++it;
    }
    return pickedUp;
}

void DroppedItemSystem::AppendMesh(std::vector<WorldVertex>& output) const {
    for (const DroppedItem& item : drops_) {
        const BlockId block=static_cast<BlockId>(item.itemId);
        AppendTexturedBlockCube(output,block,item.position,{0.13f,0.13f,0.13f});
    }
}

void EntitySystem::SpawnInitial(const VoxelWorld& world) {
    if (initialized_) return;
    constexpr std::array<Vec3, 3> positions{{{5.0f, 0.0f, 5.0f}, {11.0f, 0.0f, 4.0f}, {13.0f, 0.0f, 12.0f}}};
    for (std::size_t i = 0; i < entities_.size(); ++i) {
        entities_[i].position = positions[i];
        entities_[i].position.y = static_cast<float>(world.GetSurfaceHeight(
            FloorToInt(positions[i].x), FloorToInt(positions[i].z))) + 1.0f;
        entities_[i].phase = static_cast<float>(i) * 2.0f;
        entities_[i].speed = 0.18f + static_cast<float>(i) * 0.07f;
        entities_[i].kind = static_cast<int>(i % 3u);
    }
    initialized_ = true;
}

void EntitySystem::Update(const VoxelWorld& world, float deltaSeconds) {
    const float dt = std::clamp(deltaSeconds, 0.0f, 0.05f);
    for (Entity& entity : entities_) {
        entity.phase += dt * entity.speed;
        const float targetX = entity.position.x + std::sin(entity.phase) * dt * 0.5f;
        const float targetZ = entity.position.z + std::cos(entity.phase * 0.7f) * dt * 0.5f;
        if (world.IsSolid(FloorToInt(targetX), FloorToInt(entity.position.y - 0.05f), FloorToInt(targetZ))) {
            entity.position.x = targetX;
            entity.position.z = targetZ;
        }
        entity.position.y = static_cast<float>(world.GetSurfaceHeight(
            FloorToInt(entity.position.x), FloorToInt(entity.position.z))) + 1.0f;
    }
}

void EntitySystem::AppendMesh(std::vector<WorldVertex>& output) const {
    for (const Entity& entity : entities_) {
        const float gait = std::sin(entity.phase * 8.0f) * 0.12f;
        const float legA = gait;
        const float legB = -gait;
        if (entity.kind == 0) {
            // Minefade meadow creature: wool body, head and four animated legs.
            const std::array<float, 3> wool{0.91f, 0.88f, 0.78f};
            const std::array<float, 3> face{0.63f, 0.47f, 0.37f};
            const std::array<float, 3> hoof{0.28f, 0.24f, 0.21f};
            AppendCube(output, {entity.position.x, entity.position.y + 0.58f, entity.position.z},
                       {0.48f, 0.38f, 0.64f}, wool);
            AppendCube(output, {entity.position.x, entity.position.y + 0.73f, entity.position.z + 0.64f},
                       {0.24f, 0.25f, 0.24f}, face);
            AppendCube(output, {entity.position.x - 0.27f, entity.position.y + 0.22f + legA, entity.position.z - 0.34f},
                       {0.10f, 0.24f, 0.11f}, hoof);
            AppendCube(output, {entity.position.x + 0.27f, entity.position.y + 0.22f + legB, entity.position.z - 0.34f},
                       {0.10f, 0.24f, 0.11f}, hoof);
            AppendCube(output, {entity.position.x - 0.27f, entity.position.y + 0.22f + legB, entity.position.z + 0.36f},
                       {0.10f, 0.24f, 0.11f}, hoof);
            AppendCube(output, {entity.position.x + 0.27f, entity.position.y + 0.22f + legA, entity.position.z + 0.36f},
                       {0.10f, 0.24f, 0.11f}, hoof);
            const std::array<float, 3> eye{0.12f, 0.10f, 0.08f};
            AppendCube(output, {entity.position.x - 0.13f, entity.position.y + 0.79f, entity.position.z + 0.855f},
                       {0.035f, 0.045f, 0.028f}, eye);
            AppendCube(output, {entity.position.x + 0.13f, entity.position.y + 0.79f, entity.position.z + 0.855f},
                       {0.035f, 0.045f, 0.028f}, eye);
            AppendCube(output, {entity.position.x, entity.position.y + 0.62f, entity.position.z + 0.87f},
                       {0.12f, 0.08f, 0.045f}, face);
            AppendCube(output, {entity.position.x, entity.position.y + 0.70f, entity.position.z - 0.71f},
                       {0.055f, 0.07f, 0.15f}, wool);
        } else if (entity.kind == 1) {
            // Short-bodied bovine with contrasting patches and a larger head.
            const std::array<float, 3> hide{0.83f, 0.80f, 0.70f};
            const std::array<float, 3> patch{0.23f, 0.20f, 0.19f};
            const std::array<float, 3> muzzle{0.66f, 0.47f, 0.39f};
            AppendCube(output, {entity.position.x, entity.position.y + 0.66f, entity.position.z},
                       {0.54f, 0.43f, 0.72f}, hide);
            AppendCube(output, {entity.position.x - 0.25f, entity.position.y + 0.79f, entity.position.z - 0.10f},
                       {0.17f, 0.22f, 0.18f}, patch);
            AppendCube(output, {entity.position.x + 0.20f, entity.position.y + 0.54f, entity.position.z + 0.18f},
                       {0.16f, 0.18f, 0.19f}, patch);
            AppendCube(output, {entity.position.x, entity.position.y + 0.80f, entity.position.z + 0.72f},
                       {0.29f, 0.29f, 0.27f}, hide);
            AppendCube(output, {entity.position.x, entity.position.y + 0.62f, entity.position.z + 0.97f},
                       {0.20f, 0.13f, 0.12f}, muzzle);
            AppendCube(output, {entity.position.x - 0.34f, entity.position.y + 0.22f + legA, entity.position.z - 0.42f},
                       {0.11f, 0.25f, 0.11f}, patch);
            AppendCube(output, {entity.position.x + 0.34f, entity.position.y + 0.22f + legB, entity.position.z - 0.42f},
                       {0.11f, 0.25f, 0.11f}, patch);
            AppendCube(output, {entity.position.x - 0.34f, entity.position.y + 0.22f + legB, entity.position.z + 0.42f},
                       {0.11f, 0.25f, 0.11f}, patch);
            AppendCube(output, {entity.position.x + 0.34f, entity.position.y + 0.22f + legA, entity.position.z + 0.42f},
                       {0.11f, 0.25f, 0.11f}, patch);
            const std::array<float, 3> eye{0.10f, 0.08f, 0.07f};
            const std::array<float, 3> horn{0.88f, 0.82f, 0.67f};
            AppendCube(output, {entity.position.x - 0.17f, entity.position.y + 0.88f, entity.position.z + 0.94f},
                       {0.04f, 0.05f, 0.028f}, eye);
            AppendCube(output, {entity.position.x + 0.17f, entity.position.y + 0.88f, entity.position.z + 0.94f},
                       {0.04f, 0.05f, 0.028f}, eye);
            AppendCube(output, {entity.position.x - 0.22f, entity.position.y + 1.13f, entity.position.z + 0.68f},
                       {0.065f, 0.15f, 0.065f}, horn);
            AppendCube(output, {entity.position.x + 0.22f, entity.position.y + 1.13f, entity.position.z + 0.68f},
                       {0.065f, 0.15f, 0.065f}, horn);
            AppendCube(output, {entity.position.x, entity.position.y + 0.70f, entity.position.z - 0.73f},
                       {0.045f, 0.06f, 0.20f}, hide);
        } else {
            // Small bird with compact body, head, beak and animated legs.
            const std::array<float, 3> feather{0.89f, 0.85f, 0.75f};
            const std::array<float, 3> beak{0.91f, 0.60f, 0.18f};
            const std::array<float, 3> foot{0.80f, 0.49f, 0.15f};
            AppendCube(output, {entity.position.x, entity.position.y + 0.39f, entity.position.z},
                       {0.34f, 0.30f, 0.42f}, feather);
            AppendCube(output, {entity.position.x, entity.position.y + 0.69f, entity.position.z + 0.36f},
                       {0.20f, 0.20f, 0.20f}, feather);
            AppendCube(output, {entity.position.x, entity.position.y + 0.61f, entity.position.z + 0.59f},
                       {0.11f, 0.07f, 0.10f}, beak);
            AppendCube(output, {entity.position.x - 0.13f, entity.position.y + 0.12f + legA, entity.position.z},
                       {0.045f, 0.13f, 0.045f}, foot);
            AppendCube(output, {entity.position.x + 0.13f, entity.position.y + 0.12f + legB, entity.position.z},
                       {0.045f, 0.13f, 0.045f}, foot);
            const std::array<float, 3> eye{0.12f, 0.09f, 0.05f};
            const std::array<float, 3> wing{0.70f, 0.66f, 0.57f};
            AppendCube(output, {entity.position.x - 0.12f, entity.position.y + 0.73f, entity.position.z + 0.52f},
                       {0.035f, 0.04f, 0.025f}, eye);
            AppendCube(output, {entity.position.x + 0.12f, entity.position.y + 0.73f, entity.position.z + 0.52f},
                       {0.035f, 0.04f, 0.025f}, eye);
            AppendCube(output, {entity.position.x - 0.27f, entity.position.y + 0.40f, entity.position.z - 0.03f},
                       {0.13f, 0.11f, 0.31f}, wing);
            AppendCube(output, {entity.position.x + 0.27f, entity.position.y + 0.40f, entity.position.z - 0.03f},
                       {0.13f, 0.11f, 0.31f}, wing);
            AppendCube(output, {entity.position.x, entity.position.y + 0.36f, entity.position.z - 0.47f},
                       {0.12f, 0.09f, 0.24f}, feather);
        }
    }
}

void EnvironmentSystem::Update(float deltaSeconds) {
    timeOfDay_ += std::clamp(deltaSeconds, 0.0f, 0.1f) / 720.0f;
    if (timeOfDay_ >= 1.0f) timeOfDay_ -= 1.0f;
    const float sun = std::sin(timeOfDay_ * 2.0f * kPi - kPi * 0.5f);
    daylight_ = std::clamp(0.17f + (sun + 1.0f) * 0.415f, 0.17f, 1.0f);
}

std::array<float, 3> EnvironmentSystem::SkyColor() const {
    const float night = 1.0f - daylight_;
    return {0.42f - night * 0.30f, 0.68f - night * 0.48f, 0.88f - night * 0.30f};
}

std::array<float, 3> EnvironmentSystem::SunDirection() const {
    const float angle = timeOfDay_ * 2.0f * kPi;
    return {-std::cos(angle), -std::max(0.12f, std::sin(angle)), -0.35f};
}

} // namespace minefade
