#pragma once

#include "../world/VoxelWorld.h"

#include <array>
#include <cstdint>

namespace minefade {

class Player {
public:
    Vec3 position{8.0f, 13.0f, 8.0f}; // feet position
    float yaw{-1.5707963f};
    float pitch{-0.12f};
    float verticalVelocity{0.0f};
    float movementX{0.0f};
    float movementY{0.0f};
    bool grounded{false};

    Vec3 Forward() const;
    Vec3 CameraPosition() const;
    void SetMovement(float x, float y);
    void Rotate(float deltaX, float deltaY);
    void Jump();
    void Update(const VoxelWorld& world, float deltaSeconds);
};

class Inventory {
public:
    static constexpr std::size_t kSlotCount = 9;
    std::array<std::uint32_t, kSlotCount> counts{2016u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u};
    std::uint16_t selectedSlot{0};

    bool CanAdd(std::uint16_t itemId, std::uint16_t amount = 1) const;
    bool Add(std::uint16_t itemId, std::uint16_t amount = 1);
    bool ConsumeSelected(std::uint16_t amount = 1);
    std::uint16_t SelectedItemId() const;
};

struct Entity {
    Vec3 position;
    float phase{0.0f};
    float speed{0.5f};
    int kind{0};
};

class EntitySystem {
public:
    void SpawnInitial(const VoxelWorld& world);
    void Update(const VoxelWorld& world, float deltaSeconds);
    void AppendMesh(std::vector<WorldVertex>& output) const;
    std::size_t Count() const { return entities_.size(); }
private:
    std::array<Entity, 3> entities_{};
    bool initialized_{false};
};

class EnvironmentSystem {
public:
    void Update(float deltaSeconds);
    std::array<float, 3> SkyColor() const;
    std::array<float, 3> SunDirection() const;
    float Daylight() const { return daylight_; }
    float TimeOfDay() const { return timeOfDay_; }
private:
    float timeOfDay_{0.25f};
    float daylight_{1.0f};
};

} // namespace minefade
