#ifndef MINEFADE_CORE_H
#define MINEFADE_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MINEFADE_CORE_ABI_VERSION 1u

typedef struct MfPlayerState {
    float x, y, z;
    float yaw, pitch;
    float vertical_velocity;
    float movement_x, movement_y;
    float walk_phase;
    float animation_time;
    uint8_t grounded;
    uint8_t reserved[3];
} MfPlayerState;

typedef struct MfPlayerPose {
    float left_arm_pitch;
    float right_arm_pitch;
    float left_leg_pitch;
    float right_leg_pitch;
    float torso_bob;
    float torso_pitch;
    float head_yaw;
    float head_pitch;
    float eyelid;
    float jump_amount;
} MfPlayerPose;

typedef struct MfEntityState {
    float x, y, z;
    float phase;
    float speed;
    int32_t kind;
} MfEntityState;

typedef struct MfEntityPose {
    float front_left_leg_pitch;
    float front_right_leg_pitch;
    float rear_left_leg_pitch;
    float rear_right_leg_pitch;
    float body_bob;
    float head_pitch;
    float head_yaw;
    float tail_yaw;
    float left_wing_roll;
    float right_wing_roll;
    float eyelid;
} MfEntityPose;

typedef struct MfEnvironmentState {
    float time_of_day;
    float daylight;
} MfEnvironmentState;

typedef struct MfDroppedItemState {
    float x, y, z;
    float velocity_x, velocity_y, velocity_z;
    float age;
} MfDroppedItemState;

typedef uint8_t (*MfIsSolidCallback)(int32_t x, int32_t y, int32_t z, void* user_data);
typedef int32_t (*MfSurfaceHeightCallback)(int32_t x, int32_t z, void* user_data);

uint32_t minefade_core_abi_version(void);

#define MINEFADE_INVENTORY_SLOT_COUNT 9u

uint8_t minefade_inventory_can_add(
    const uint32_t* counts, uint32_t slot_count,
    uint16_t item_id, uint16_t amount, uint16_t stack_limit);
uint8_t minefade_inventory_add(
    uint32_t* counts, uint32_t slot_count,
    uint16_t item_id, uint16_t amount, uint16_t stack_limit);
void minefade_dropped_item_update(
    MfDroppedItemState* state,
    float delta_seconds,
    MfIsSolidCallback is_solid,
    void* user_data);

uint16_t minefade_inventory_selected_item_id(
    const uint32_t* counts, uint32_t slot_count, uint32_t selected_slot);
uint8_t minefade_inventory_consume_selected(
    uint32_t* counts, uint32_t slot_count, uint32_t selected_slot, uint16_t amount);

void minefade_player_set_movement(MfPlayerState* state, float x, float y);
void minefade_player_rotate(MfPlayerState* state, float delta_x, float delta_y);
void minefade_player_jump(MfPlayerState* state);
void minefade_player_update(
    MfPlayerState* state,
    float delta_seconds,
    uint8_t in_fluid,
    MfIsSolidCallback is_solid,
    void* user_data);
void minefade_player_compute_pose(
    const MfPlayerState* state,
    uint8_t animation_quality,
    uint8_t animations_enabled,
    MfPlayerPose* output);

void minefade_entity_update(
    MfEntityState* state,
    float delta_seconds,
    MfIsSolidCallback is_solid,
    MfSurfaceHeightCallback surface_height,
    void* user_data);
void minefade_entity_compute_pose(
    int32_t kind,
    float phase,
    float animation_time,
    uint8_t animation_quality,
    uint8_t animations_enabled,
    MfEntityPose* output);

void minefade_environment_update(MfEnvironmentState* state, float delta_seconds);
void minefade_environment_sky_color(const MfEnvironmentState* state, float output_rgb[3]);
void minefade_environment_sun_direction(const MfEnvironmentState* state, float output_xyz[3]);

#ifdef __cplusplus
}
#endif

#endif /* MINEFADE_CORE_H */
