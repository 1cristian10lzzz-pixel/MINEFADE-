#include "Player.h"
#include "minefade_core.h"

static_assert(minefade::Inventory::kSlotCount == MINEFADE_INVENTORY_SLOT_COUNT,
              "C++ inventory slot count differs from the Rust ABI");
static_assert(sizeof(MfDroppedItemState) == 28u, "Rust/C dropped-item ABI mismatch");

static_assert(sizeof(MfPlayerState) == 44u, "Rust/C player state ABI mismatch");
static_assert(sizeof(MfPlayerPose) == 40u, "Rust/C player pose ABI mismatch");
static_assert(sizeof(MfEntityState) == 24u, "Rust/C entity state ABI mismatch");
static_assert(sizeof(MfEntityPose) == 44u, "Rust/C entity pose ABI mismatch");
static_assert(sizeof(MfEnvironmentState) == 8u, "Rust/C environment ABI mismatch");

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

void AppendContactShadow(std::vector<WorldVertex>& output,const Vec3& position,
                          float radiusX,float radiusZ,float opacity) {
    constexpr int segments=18;
    const float y=position.y+0.018f;
    const std::array<float,3> inner{0.10f*opacity,0.12f*opacity,0.15f*opacity};
    const std::array<float,3> outer{0.19f*opacity,0.22f*opacity,0.25f*opacity};
    for(int segment=0;segment<segments;++segment) {
        const float a0=2.0f*kPi*static_cast<float>(segment)/static_cast<float>(segments);
        const float a1=2.0f*kPi*static_cast<float>(segment+1)/static_cast<float>(segments);
        const Vec3 center{position.x,y,position.z};
        const Vec3 p0{position.x+std::cos(a0)*radiusX,y,position.z+std::sin(a0)*radiusZ};
        const Vec3 p1{position.x+std::cos(a1)*radiusX,y,position.z+std::sin(a1)*radiusZ};
        output.push_back({center.x,center.y,center.z,0,1,0,inner[0],inner[1],inner[2],0.5f,0.5f});
        output.push_back({p1.x,p1.y,p1.z,0,1,0,outer[0],outer[1],outer[2],0.5f,0.5f});
        output.push_back({p0.x,p0.y,p0.z,0,1,0,outer[0],outer[1],outer[2],0.5f,0.5f});
    }
}

void AppendPlayerBoxAnimated(std::vector<WorldVertex>& output, const Vec3& anchor, float yaw,
                             const Vec3& localCenter, const Vec3& half,
                             const std::array<float, 3>& color,
                             const Vec3& pivot, const Vec3& rotation) {
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
    const float cx = std::cos(rotation.x), sx = std::sin(rotation.x);
    const float cy = std::cos(rotation.y), sy = std::sin(rotation.y);
    const float cz = std::cos(rotation.z), sz = std::sin(rotation.z);
    const auto transform = [&](float x, float y, float z) {
        x -= pivot.x; y -= pivot.y; z -= pivot.z;
        const float y1 = y * cx - z * sx;
        const float z1 = y * sx + z * cx;
        const float x2 = x * cy + z1 * sy;
        const float z2 = -x * sy + z1 * cy;
        const float x3 = x2 * cz - y1 * sz;
        const float y3 = x2 * sz + y1 * cz;
        return PlayerLocalPoint(anchor, yaw, pivot.x + x3, pivot.y + y3, pivot.z + z2);
    };
    for (std::size_t face = 0; face < faces.size(); ++face) {
        std::array<Vec3, 4> points{};
        for (std::size_t i = 0; i < points.size(); ++i) {
            const auto& p = faces[face][i];
            const float lx = localCenter.x + (p[0] * 2.0f - 1.0f) * half.x;
            const float ly = localCenter.y + (p[1] * 2.0f - 1.0f) * half.y;
            const float lz = localCenter.z + (p[2] * 2.0f - 1.0f) * half.z;
            points[i] = transform(lx, ly, lz);
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

void AppendPlayerBox(std::vector<WorldVertex>& output, const Vec3& anchor, float yaw,
                     const Vec3& localCenter, const Vec3& half,
                     const std::array<float, 3>& color) {
    AppendPlayerBoxAnimated(output, anchor, yaw, localCenter, half, color,
                            localCenter, {0.0f, 0.0f, 0.0f});
}

void AppendEntityBoxAnimated(std::vector<WorldVertex>& output, const Vec3& anchor,
                             const Vec3& centerOffset, const Vec3& half,
                             const std::array<float, 3>& color,
                             const Vec3& pivotOffset, const Vec3& rotation) {
    // A -90 degree basis maps the player-local right/back axes to world X/Z.
    AppendPlayerBoxAnimated(output, anchor, -kPi * 0.5f, centerOffset, half,
                            color, pivotOffset, rotation);
}

std::uint8_t WorldSolidForRust(std::int32_t x, std::int32_t y, std::int32_t z, void* userData) {
    const auto* world = static_cast<const VoxelWorld*>(userData);
    return world != nullptr && world->IsSolid(x, y, z) ? 1u : 0u;
}

std::int32_t WorldSurfaceForRust(std::int32_t x, std::int32_t z, void* userData) {
    const auto* world = static_cast<const VoxelWorld*>(userData);
    return world != nullptr ? world->GetSurfaceHeight(x, z) : 0;
}

MfPlayerState ToRustPlayerState(const Player& player) {
    MfPlayerState state{};
    state.x = player.position.x; state.y = player.position.y; state.z = player.position.z;
    state.yaw = player.yaw; state.pitch = player.pitch;
    state.vertical_velocity = player.verticalVelocity;
    state.movement_x = player.movementX; state.movement_y = player.movementY;
    state.walk_phase = player.walkPhase; state.animation_time = player.animationTime;
    state.grounded = player.grounded ? 1u : 0u;
    return state;
}

void ApplyRustPlayerState(Player& player, const MfPlayerState& state) {
    player.position = {state.x, state.y, state.z};
    player.yaw = state.yaw; player.pitch = state.pitch;
    player.verticalVelocity = state.vertical_velocity;
    player.movementX = state.movement_x; player.movementY = state.movement_y;
    player.walkPhase = state.walk_phase; player.animationTime = state.animation_time;
    player.grounded = state.grounded != 0;
}
}

Vec3 Player::Forward() const {
    const float cp = std::cos(pitch);
    return {std::cos(yaw) * cp, std::sin(pitch), std::sin(yaw) * cp};
}

Vec3 Player::CameraPosition() const { return {position.x, position.y + 1.58f, position.z}; }

void Player::SetMovement(float x, float y) {
    MfPlayerState state = ToRustPlayerState(*this);
    minefade_player_set_movement(&state, x, y);
    ApplyRustPlayerState(*this, state);
}

void Player::Rotate(float deltaX, float deltaY) {
    MfPlayerState state = ToRustPlayerState(*this);
    minefade_player_rotate(&state, deltaX, deltaY);
    ApplyRustPlayerState(*this, state);
}

void Player::Jump() {
    MfPlayerState state = ToRustPlayerState(*this);
    minefade_player_jump(&state);
    ApplyRustPlayerState(*this, state);
}

void Player::SetAnimationOptions(std::uint8_t quality, bool enabled) {
    animationQuality = std::min<std::uint8_t>(quality, 2u);
    animationsEnabled = enabled;
}

void Player::SetAppearance(const std::array<int, 6>& values) {
    for (std::size_t i = 0; i < appearance.size(); ++i)
        appearance[i] = std::clamp(values[i], 0, 5);
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
    if(contactShadows)
        AppendContactShadow(output,{anchor.x,anchor.y-0.98f,anchor.z},0.48f,0.34f,1.0f);
    MfPlayerPose pose{};
    const MfPlayerState state = ToRustPlayerState(*this);
    minefade_player_compute_pose(&state, animationQuality, animationsEnabled ? 1u : 0u, &pose);
    const float stride = pose.left_leg_pitch * 0.45f;
    // Original Minefade adventurer silhouette: boots, trousers, tunic, arms, hands,
    // face and hair. Every part is transformed by the player's yaw, not world axes.
    static constexpr std::array<std::array<float,3>,6> skinPalette{{
        {{0.91f,0.75f,0.61f}},{{0.75f,0.51f,0.37f}},{{0.58f,0.36f,0.25f}},
        {{0.95f,0.80f,0.68f}},{{0.68f,0.43f,0.31f}},{{0.43f,0.28f,0.21f}}
    }};
    static constexpr std::array<std::array<float,3>,6> hairPalette{{
        {{0.48f,0.29f,0.17f}},{{0.19f,0.16f,0.18f}},{{0.72f,0.50f,0.24f}},
        {{0.25f,0.39f,0.50f}},{{0.42f,0.30f,0.23f}},{{0.78f,0.70f,0.55f}}
    }};
    static constexpr std::array<std::array<float,3>,6> outfitPalette{{
        {{0.18f,0.55f,0.39f}},{{0.25f,0.41f,0.76f}},{{0.66f,0.35f,0.54f}},
        {{0.70f,0.47f,0.19f}},{{0.34f,0.42f,0.50f}},{{0.27f,0.48f,0.36f}}
    }};
    static constexpr std::array<std::array<float,3>,6> armourPalette{{
        {{0.0f,0.0f,0.0f}},{{0.48f,0.34f,0.24f}},{{0.61f,0.69f,0.74f}},
        {{0.26f,0.52f,0.66f}},{{0.61f,0.46f,0.78f}},{{0.82f,0.88f,0.91f}}
    }};
    const auto& skin=skinPalette[static_cast<std::size_t>(appearance[0])];
    const auto& hair=hairPalette[static_cast<std::size_t>(appearance[1])];
    const auto& tunic=outfitPalette[static_cast<std::size_t>(appearance[3])];
    const auto& armour=armourPalette[static_cast<std::size_t>(appearance[4])];
    const int hairstyle=appearance[1];
    const int accessory=appearance[5];
    const std::array<float, 3> trim{{0.88f, 0.66f, 0.28f}};
    const std::array<float, 3> trousers{{0.19f, 0.25f, 0.43f}};
    const std::array<float, 3> boots{{0.25f, 0.16f, 0.11f}};

    AppendPlayerBoxAnimated(output, anchor, yaw,
        {0.0f, 1.08f + pose.torso_bob, 0.0f}, {0.32f, 0.40f, 0.19f}, tunic,
        {0.0f, 1.08f, 0.0f}, {pose.torso_pitch, 0.0f, 0.0f});
    AppendPlayerBoxAnimated(output, anchor, yaw,
        {0.0f, 1.37f + pose.torso_bob, 0.0f}, {0.33f, 0.10f, 0.195f}, trim,
        {0.0f, 1.37f, 0.0f}, {pose.torso_pitch * 0.6f, 0.0f, 0.0f});

    const float torsoOffset = pose.torso_bob;
    AppendPlayerBoxAnimated(output,anchor,yaw,{-0.43f,1.08f+torsoOffset,0.0f},{0.145f,0.34f,0.145f},tunic,
        {-0.43f,1.38f,0.0f},{pose.left_arm_pitch,0.0f,0.0f});
    AppendPlayerBoxAnimated(output,anchor,yaw,{0.43f,1.08f+torsoOffset,0.0f},{0.145f,0.34f,0.145f},tunic,
        {0.43f,1.38f,0.0f},{pose.right_arm_pitch,0.0f,0.0f});
    AppendPlayerBox(output,anchor,yaw,{-0.43f,0.68f+torsoOffset,0.0f},{0.135f,0.12f,0.14f},skin);
    AppendPlayerBox(output,anchor,yaw,{0.43f,0.68f+torsoOffset,0.0f},{0.135f,0.12f,0.14f},skin);

    AppendPlayerBoxAnimated(output,anchor,yaw,{-0.17f,0.43f+stride,0.0f},{0.16f,0.37f,0.16f},trousers,
        {-0.17f,0.80f,0.0f},{pose.left_leg_pitch,0.0f,0.0f});
    AppendPlayerBoxAnimated(output,anchor,yaw,{0.17f,0.43f-stride,0.0f},{0.16f,0.37f,0.16f},trousers,
        {0.17f,0.80f,0.0f},{pose.right_leg_pitch,0.0f,0.0f});
    AppendPlayerBoxAnimated(output,anchor,yaw,{-0.17f,0.105f+stride,0.025f},{0.165f,0.105f,0.205f},boots,
        {-0.17f,0.80f,0.0f},{pose.left_leg_pitch,0.0f,0.0f});
    AppendPlayerBoxAnimated(output,anchor,yaw,{0.17f,0.105f-stride,0.025f},{0.165f,0.105f,0.205f},boots,
        {0.17f,0.80f,0.0f},{pose.right_leg_pitch,0.0f,0.0f});

    AppendPlayerBoxAnimated(output,anchor,yaw,{0.0f,1.63f+pose.torso_bob,0.015f},{0.25f,0.25f,0.245f},skin,
        {0.0f,1.63f,0.015f},{pose.head_pitch,pose.head_yaw,0.0f});
    AppendPlayerBoxAnimated(output,anchor,yaw,{0.0f,1.83f+pose.torso_bob,-0.015f},{0.255f,0.075f,0.255f},hair,
        {0.0f,1.63f,0.015f},{pose.head_pitch,pose.head_yaw,0.0f});
    AppendPlayerBoxAnimated(output,anchor,yaw,{0.0f,1.63f+pose.torso_bob,-0.225f},{0.255f,0.10f,0.045f},hair,
        {0.0f,1.63f,0.015f},{pose.head_pitch,pose.head_yaw,0.0f});
    // Hairstyle slots alter the actual native silhouette, not just the menu swatch.
    if(hairstyle==1||hairstyle==2) {
        AppendPlayerBox(output,anchor,yaw,{-0.19f,1.72f,0.19f},{0.10f,0.18f,0.12f},hair);
    } else if(hairstyle==3||hairstyle==4) {
        AppendPlayerBox(output,anchor,yaw,{0.23f,1.43f,-0.02f},{0.085f,0.34f,0.10f},hair);
    } else if(hairstyle==5) {
        AppendPlayerBox(output,anchor,yaw,{0.0f,1.99f,-0.015f},{0.11f,0.16f,0.12f},hair);
    }

    // Skin/eye palettes, armour overlays and accessories are applied on the in-world avatar.
    static constexpr std::array<std::array<float,3>,6> eyePalette{{
        {{0.48f,0.34f,0.20f}},{{0.30f,0.62f,0.82f}},{{0.29f,0.67f,0.43f}},
        {{0.52f,0.60f,0.68f}},{{0.13f,0.15f,0.22f}},{{0.82f,0.62f,0.25f}}
    }};
    const auto& eye=eyePalette[static_cast<std::size_t>(appearance[2])];
    const std::array<float,3> belt{{0.11f,0.16f,0.21f}};
    const float eyeOpen = std::max(0.08f, 1.0f - pose.eyelid * 0.92f);
    AppendPlayerBoxAnimated(output,anchor,yaw,{-0.105f,1.66f+pose.torso_bob,0.245f},
        {0.035f,0.045f*eyeOpen,0.018f},eye,{0.0f,1.63f,0.015f},{pose.head_pitch,pose.head_yaw,0.0f});
    AppendPlayerBoxAnimated(output,anchor,yaw,{0.105f,1.66f+pose.torso_bob,0.245f},
        {0.035f,0.045f*eyeOpen,0.018f},eye,{0.0f,1.63f,0.015f},{pose.head_pitch,pose.head_yaw,0.0f});
    AppendPlayerBox(output, anchor, yaw, {0.0f,1.57f,0.252f},{0.045f,0.035f,0.018f},trim);
    AppendPlayerBox(output, anchor, yaw, {0.0f,0.77f,0.0f},{0.325f,0.055f,0.198f},belt);
    AppendPlayerBox(output, anchor, yaw, {-0.22f,1.32f,0.0f},{0.13f,0.08f,0.205f},trim);
    AppendPlayerBox(output, anchor, yaw, {0.22f,1.32f,0.0f},{0.13f,0.08f,0.205f},trim);

    if(appearance[4]>0) {
        AppendPlayerBox(output,anchor,yaw,{0.0f,1.08f,0.205f},{0.30f,0.25f,0.045f},armour);
        AppendPlayerBox(output,anchor,yaw,{-0.43f,1.34f,0.0f},{0.17f,0.12f,0.18f},armour);
        AppendPlayerBox(output,anchor,yaw,{0.43f,1.34f,0.0f},{0.17f,0.12f,0.18f},armour);
    }
    if(accessory==1) {
        AppendPlayerBox(output,anchor,yaw,{-0.34f,1.42f,0.0f},{0.20f,0.12f,0.22f},trim);
    } else if(accessory==2) {
        AppendPlayerBox(output,anchor,yaw,{0.0f,1.00f,-0.235f},{0.30f,0.62f,0.035f},hair);
    } else if(accessory==3) {
        AppendPlayerBox(output,anchor,yaw,{0.0f,1.26f,0.25f},{0.075f,0.085f,0.025f},trim);
    } else if(accessory==4) {
        AppendPlayerBox(output,anchor,yaw,{0.0f,2.03f,-0.015f},{0.27f,0.035f,0.26f},trim);
    } else if(accessory==5) {
        AppendPlayerBox(output,anchor,yaw,{0.0f,1.08f,-0.245f},{0.22f,0.30f,0.09f},armour);
    }
}

void Player::Update(const VoxelWorld& world, float deltaSeconds) {
    const BlockId bodyBlock = world.GetBlock(FloorToInt(position.x),
                                               FloorToInt(position.y + 0.65f),
                                               FloorToInt(position.z));
    const std::uint8_t inFluid = HasBlockFlag(bodyBlock, BlockFluid) ? 1u : 0u;
    MfPlayerState state = ToRustPlayerState(*this);
    minefade_player_update(&state, deltaSeconds, inFluid, WorldSolidForRust,
                           const_cast<VoxelWorld*>(&world));
    ApplyRustPlayerState(*this, state);
}

bool Inventory::CanAdd(std::uint16_t itemId, std::uint16_t amount) const {
    const BlockDefinition* definition = GetBlockDefinition(static_cast<BlockId>(itemId));
    if (definition == nullptr) return false;
    return minefade_inventory_can_add(
        counts.data(), static_cast<std::uint32_t>(counts.size()),
        itemId, amount, definition->stackLimit) != 0;
}

bool Inventory::Add(std::uint16_t itemId, std::uint16_t amount) {
    const BlockDefinition* definition = GetBlockDefinition(static_cast<BlockId>(itemId));
    if (definition == nullptr) return false;
    // Rust preflights the whole insertion before touching the nine encoded slots.
    return minefade_inventory_add(
        counts.data(), static_cast<std::uint32_t>(counts.size()),
        itemId, amount, definition->stackLimit) != 0;
}

std::uint16_t Inventory::SelectedItemId() const {
    return minefade_inventory_selected_item_id(
        counts.data(), static_cast<std::uint32_t>(counts.size()),
        static_cast<std::uint32_t>(selectedSlot));
}

bool Inventory::ConsumeSelected(std::uint16_t amount) {
    return minefade_inventory_consume_selected(
        counts.data(), static_cast<std::uint32_t>(counts.size()),
        static_cast<std::uint32_t>(selectedSlot), amount) != 0;
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
        MfDroppedItemState state{
            item.position.x, item.position.y, item.position.z,
            item.velocity.x, item.velocity.y, item.velocity.z, item.age
        };
        minefade_dropped_item_update(&state, dt, WorldSolidForRust,
                                     const_cast<VoxelWorld*>(&world));
        item.position = {state.x, state.y, state.z};
        item.velocity = {state.velocity_x, state.velocity_y, state.velocity_z};
        item.age = state.age;
        if (item.age >= 300.0f || item.position.y < -2.0f) {
            it = drops_.erase(it);
            continue;
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
        const float spin=item.age*1.45f;
        const float bob=std::sin(item.age*3.4f)*0.055f;
        AppendAnimatedTexturedBlockCube(output,block,item.position,
            {0.13f,0.13f,0.13f},spin,bob);
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
    animationTime_ = std::fmod(animationTime_ + dt, 4096.0f);
    for (Entity& entity : entities_) {
        MfEntityState state{entity.position.x, entity.position.y, entity.position.z,
                            entity.phase, entity.speed, entity.kind};
        minefade_entity_update(&state, dt, WorldSolidForRust, WorldSurfaceForRust,
                               const_cast<VoxelWorld*>(&world));
        entity.position = {state.x, state.y, state.z};
        entity.phase = state.phase;
    }
}

void EntitySystem::AppendMesh(std::vector<WorldVertex>& output) const {
    for (const Entity& entity : entities_) {
        MfEntityPose pose{};
        minefade_entity_compute_pose(entity.kind, entity.phase, animationTime_,
                                     animationQuality_, animationsEnabled_ ? 1u : 0u, &pose);
        if (contactShadows_)
            AppendContactShadow(output,
                {entity.position.x, entity.position.y - 0.98f, entity.position.z},
                0.55f, 0.42f, 0.78f);

        const float bob = pose.body_bob;
        const float eyeOpen = std::max(0.08f, 1.0f - pose.eyelid * 0.94f);
        if (entity.kind == 0) {
            const std::array<float, 3> wool{0.91f, 0.88f, 0.78f};
            const std::array<float, 3> face{0.63f, 0.47f, 0.37f};
            const std::array<float, 3> hoof{0.28f, 0.24f, 0.21f};
            AppendEntityBoxAnimated(output, entity.position, {0.0f,0.58f+bob,0.0f},
                {0.48f,0.38f,0.64f}, wool, {0.0f,0.58f,0.0f}, {0.0f,0.0f,0.0f});
            AppendEntityBoxAnimated(output, entity.position, {0.0f,0.73f+bob,0.64f},
                {0.24f,0.25f,0.24f}, face, {0.0f,0.74f,0.54f}, {pose.head_pitch,pose.head_yaw,0.0f});
            const std::array<Vec3,4> legs{{
                {-0.27f,0.22f,-0.34f},{0.27f,0.22f,-0.34f},
                {-0.27f,0.22f,0.36f},{0.27f,0.22f,0.36f}}};
            const std::array<float,4> swings{{pose.front_left_leg_pitch,pose.front_right_leg_pitch,
                                               pose.rear_left_leg_pitch,pose.rear_right_leg_pitch}};
            for (std::size_t i=0;i<legs.size();++i) {
                const Vec3& c=legs[i];
                AppendEntityBoxAnimated(output,entity.position,
                    {c.x,c.y+bob,c.z},{0.10f,0.24f,0.11f},hoof,
                    {c.x,0.43f,c.z},{swings[i],0.0f,0.0f});
            }
            const std::array<float,3> eye{0.12f,0.10f,0.08f};
            AppendEntityBoxAnimated(output,entity.position,{-0.13f,0.79f+bob,0.855f},
                {0.035f,0.045f*eyeOpen,0.028f},eye,{0.0f,0.74f,0.54f},{pose.head_pitch,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.13f,0.79f+bob,0.855f},
                {0.035f,0.045f,0.028f},eye,{0.0f,0.74f,0.54f},{pose.head_pitch,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.62f+bob,0.87f},
                {0.12f,0.08f,0.045f},face,{0.0f,0.74f,0.54f},{pose.head_pitch,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.70f+bob,-0.71f},
                {0.055f,0.07f,0.15f},wool,{0.0f,0.70f,-0.56f},{0.0f,pose.tail_yaw,0.0f});
        } else if (entity.kind == 1) {
            const std::array<float,3> hide{0.83f,0.80f,0.70f};
            const std::array<float,3> patch{0.23f,0.20f,0.19f};
            const std::array<float,3> muzzle{0.66f,0.47f,0.39f};
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.66f+bob,0.0f},
                {0.54f,0.43f,0.72f},hide,{0.0f,0.66f,0.0f},{0.0f,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{-0.25f,0.79f+bob,-0.10f},
                {0.17f,0.22f,0.18f},patch,{0.0f,0.66f,0.0f},{0.0f,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.20f,0.54f+bob,0.18f},
                {0.16f,0.18f,0.19f},patch,{0.0f,0.66f,0.0f},{0.0f,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.80f+bob,0.72f},
                {0.29f,0.29f,0.27f},hide,{0.0f,0.82f,0.64f},{pose.head_pitch,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.62f+bob,0.97f},
                {0.20f,0.13f,0.12f},muzzle,{0.0f,0.82f,0.64f},{pose.head_pitch,0.0f,0.0f});
            const std::array<Vec3,4> legs{{
                {-0.34f,0.22f,-0.42f},{0.34f,0.22f,-0.42f},
                {-0.34f,0.22f,0.42f},{0.34f,0.22f,0.42f}}};
            const std::array<float,4> swings{{pose.front_left_leg_pitch,pose.front_right_leg_pitch,
                                               pose.rear_left_leg_pitch,pose.rear_right_leg_pitch}};
            for (std::size_t i=0;i<legs.size();++i) {
                const Vec3& c=legs[i];
                AppendEntityBoxAnimated(output,entity.position,
                    {c.x,c.y+bob,c.z},{0.11f,0.25f,0.11f},patch,
                    {c.x,0.47f,c.z},{swings[i],0.0f,0.0f});
            }
            const std::array<float,3> eye{0.10f,0.08f,0.07f};
            const std::array<float,3> horn{0.88f,0.82f,0.67f};
            for (float side : {-1.0f,1.0f}) {
                AppendEntityBoxAnimated(output,entity.position,{side*0.17f,0.88f+bob,0.94f},
                    {0.04f,0.05f*eyeOpen,0.028f},eye,{0.0f,0.82f,0.64f},{pose.head_pitch,0.0f,0.0f});
                AppendEntityBoxAnimated(output,entity.position,{side*0.22f,1.13f+bob,0.68f},
                    {0.065f,0.15f,0.065f},horn,{0.0f,0.82f,0.64f},{pose.head_pitch,0.0f,0.0f});
            }
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.70f+bob,-0.73f},
                {0.045f,0.06f,0.20f},hide,{0.0f,0.70f,-0.55f},{0.0f,pose.tail_yaw,0.0f});
        } else {
            const std::array<float,3> feather{0.89f,0.85f,0.75f};
            const std::array<float,3> beak{0.91f,0.60f,0.18f};
            const std::array<float,3> foot{0.80f,0.49f,0.15f};
            const std::array<float,3> wing{0.70f,0.66f,0.57f};
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.39f+bob,0.0f},
                {0.34f,0.30f,0.42f},feather,{0.0f,0.39f,0.0f},{0.0f,0.0f,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.69f+bob,0.36f},
                {0.20f,0.20f,0.20f},feather,{0.0f,0.69f,0.36f},{pose.head_pitch,pose.head_yaw,0.0f});
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.61f+bob,0.59f},
                {0.11f,0.07f,0.10f},beak,{0.0f,0.69f,0.36f},{pose.head_pitch,0.0f,0.0f});
            for (float side : {-1.0f,1.0f}) {
                const float legPitch = side < 0.0f ? pose.front_left_leg_pitch : pose.front_right_leg_pitch;
                AppendEntityBoxAnimated(output,entity.position,{side*0.13f,0.12f+bob,0.0f},
                    {0.045f,0.13f,0.045f},foot,{side*0.13f,0.25f,0.0f},{legPitch,0.0f,0.0f});
            }
            AppendEntityBoxAnimated(output,entity.position,{-0.27f,0.40f+bob,-0.03f},
                {0.13f,0.11f,0.31f},wing,{-0.14f,0.40f,-0.03f},{0.0f,0.0f,pose.left_wing_roll});
            AppendEntityBoxAnimated(output,entity.position,{0.27f,0.40f+bob,-0.03f},
                {0.13f,0.11f,0.31f},wing,{0.14f,0.40f,-0.03f},{0.0f,0.0f,pose.right_wing_roll});
            AppendEntityBoxAnimated(output,entity.position,{0.0f,0.36f+bob,-0.47f},
                {0.12f,0.09f,0.24f},feather,{0.0f,0.36f,-0.25f},{0.0f,pose.tail_yaw,0.0f});
            const std::array<float,3> eye{0.12f,0.09f,0.05f};
            for (float side : {-1.0f,1.0f}) {
                AppendEntityBoxAnimated(output,entity.position,{side*0.12f,0.73f+bob,0.52f},
                    {0.035f,0.04f*eyeOpen,0.025f},eye,{0.0f,0.69f,0.36f},{pose.head_pitch,pose.head_yaw,0.0f});
            }
        }
    }
}

void EnvironmentSystem::Update(float deltaSeconds) {
    MfEnvironmentState state{timeOfDay_, daylight_};
    minefade_environment_update(&state, deltaSeconds);
    timeOfDay_ = state.time_of_day;
    daylight_ = state.daylight;
}

std::array<float, 3> EnvironmentSystem::SkyColor() const {
    const MfEnvironmentState state{timeOfDay_, daylight_};
    std::array<float,3> colour{};
    minefade_environment_sky_color(&state, colour.data());
    return colour;
}

std::array<float, 3> EnvironmentSystem::SunDirection() const {
    const MfEnvironmentState state{timeOfDay_, daylight_};
    std::array<float,3> direction{};
    minefade_environment_sun_direction(&state, direction.data());
    return direction;
}

} // namespace minefade
