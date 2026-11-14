//! Minefade's portable game-simulation and animation core.
//!
//! The Android renderer and RmlUi integration remain in C++ because they bind
//! directly to GLES/RmlUi. Player motion, environment clocks and pose evaluation
//! live here and are exposed through a stable C ABI for the existing engine.

use core::ffi::c_void;
use core::f32::consts::PI;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MfPlayerState {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub yaw: f32,
    pub pitch: f32,
    pub vertical_velocity: f32,
    pub movement_x: f32,
    pub movement_y: f32,
    pub walk_phase: f32,
    pub animation_time: f32,
    pub grounded: u8,
    pub reserved: [u8; 3],
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct MfPlayerPose {
    pub left_arm_pitch: f32,
    pub right_arm_pitch: f32,
    pub left_leg_pitch: f32,
    pub right_leg_pitch: f32,
    pub torso_bob: f32,
    pub torso_pitch: f32,
    pub head_yaw: f32,
    pub head_pitch: f32,
    pub eyelid: f32,
    pub jump_amount: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MfEntityState {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub phase: f32,
    pub speed: f32,
    pub kind: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MfEntityPose {
    pub front_left_leg_pitch: f32,
    pub front_right_leg_pitch: f32,
    pub rear_left_leg_pitch: f32,
    pub rear_right_leg_pitch: f32,
    pub body_bob: f32,
    pub head_pitch: f32,
    pub head_yaw: f32,
    pub tail_yaw: f32,
    pub left_wing_roll: f32,
    pub right_wing_roll: f32,
    pub eyelid: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MfEnvironmentState {
    pub time_of_day: f32,
    pub daylight: f32,
}

pub type MfIsSolidCallback =
    Option<unsafe extern "C" fn(i32, i32, i32, *mut c_void) -> u8>;
pub type MfSurfaceHeightCallback =
    Option<unsafe extern "C" fn(i32, i32, *mut c_void) -> i32>;

const BODY_RADIUS: f32 = 0.29;
const BODY_HEIGHT: f32 = 1.80;

#[inline]
fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() { value } else { fallback }
}

#[inline]
fn floor_i32(value: f32) -> i32 {
    value.floor() as i32
}

#[inline]
fn normalize_movement(x: f32, y: f32) -> (f32, f32) {
    let mut x = finite_or(x, 0.0).clamp(-1.0, 1.0);
    let mut y = finite_or(y, 0.0).clamp(-1.0, 1.0);
    let length = (x * x + y * y).sqrt();
    if length > 1.0 {
        x /= length;
        y /= length;
    }
    (x, y)
}

#[inline]
unsafe fn cell_is_solid(
    callback: MfIsSolidCallback,
    user_data: *mut c_void,
    x: i32,
    y: i32,
    z: i32,
) -> bool {
    match callback {
        Some(check) => unsafe { check(x, y, z, user_data) != 0 },
        None => false,
    }
}

#[inline]
unsafe fn collides_at(
    callback: MfIsSolidCallback,
    user_data: *mut c_void,
    x: f32,
    y: f32,
    z: f32,
) -> bool {
    if !x.is_finite() || !y.is_finite() || !z.is_finite() {
        return true;
    }

    let min_x = floor_i32(x - BODY_RADIUS);
    let max_x = floor_i32(x + BODY_RADIUS);
    let min_z = floor_i32(z - BODY_RADIUS);
    let max_z = floor_i32(z + BODY_RADIUS);
    let min_y = floor_i32(y + 0.06);
    let max_y = floor_i32(y + BODY_HEIGHT - 0.06);

    for block_y in min_y..=max_y {
        for block_z in min_z..=max_z {
            for block_x in min_x..=max_x {
                if unsafe { cell_is_solid(callback, user_data, block_x, block_y, block_z) } {
                    return true;
                }
            }
        }
    }
    false
}

#[inline]
fn quality_scale(quality: u8) -> f32 {
    match quality {
        0 => 0.58,
        1 => 0.82,
        _ => 1.0,
    }
}

#[no_mangle]
pub extern "C" fn minefade_core_abi_version() -> u32 {
    1
}

/// Check that an encoded inventory can accept an item without mutating it.
/// Each non-empty slot stores item_id * 1000 + quantity.
#[no_mangle]
pub unsafe extern "C" fn minefade_inventory_can_add(
    counts: *const u32,
    slot_count: u32,
    item_id: u16,
    amount: u16,
    stack_limit: u16,
) -> u8 {
    if counts.is_null() || item_id == 0 || amount == 0 || slot_count == 0 {
        return 0;
    }
    let Some(counts) = (unsafe { core::slice::from_raw_parts(counts, slot_count as usize) }).into() else {
        return 0;
    };
    let maximum = if stack_limit == 0 { 64u32 } else { (stack_limit as u32).min(999) };
    if maximum == 0 { return 0; }
    let mut remaining = amount as u32;
    for encoded in counts.iter().copied() {
        if encoded == 0 || encoded / 1000 != item_id as u32 { continue; }
        let quantity = encoded % 1000;
        if quantity < maximum {
            remaining = remaining.saturating_sub(maximum.saturating_sub(quantity).min(remaining));
        }
        if remaining == 0 { return 1; }
    }
    for encoded in counts.iter().copied() {
        if encoded == 0 {
            remaining = remaining.saturating_sub(maximum.min(remaining));
        }
        if remaining == 0 { return 1; }
    }
    0
}

#[no_mangle]
pub unsafe extern "C" fn minefade_inventory_add(
    counts: *mut u32,
    slot_count: u32,
    item_id: u16,
    amount: u16,
    stack_limit: u16,
) -> u8 {
    if unsafe { minefade_inventory_can_add(counts, slot_count, item_id, amount, stack_limit) } == 0 {
        return 0;
    }
    let Some(counts) = (unsafe { counts.as_mut() }) else { return 0 };
    let slots = unsafe { core::slice::from_raw_parts_mut(counts, slot_count as usize) };
    let maximum = if stack_limit == 0 { 64u32 } else { (stack_limit as u32).min(999) };
    let item = item_id as u32;
    let mut remaining = amount as u32;
    for encoded in slots.iter_mut() {
        if *encoded == 0 || *encoded / 1000 != item { continue; }
        let quantity = *encoded % 1000;
        if quantity >= maximum { continue; }
        let moved = maximum.saturating_sub(quantity).min(remaining);
        *encoded = item * 1000 + quantity + moved;
        remaining -= moved;
        if remaining == 0 { return 1; }
    }
    for encoded in slots.iter_mut() {
        if *encoded != 0 { continue; }
        let moved = maximum.min(remaining);
        *encoded = item * 1000 + moved;
        remaining -= moved;
        if remaining == 0 { return 1; }
    }
    0
}

#[no_mangle]
pub unsafe extern "C" fn minefade_inventory_selected_item_id(
    counts: *const u32,
    slot_count: u32,
    selected_slot: u32,
) -> u16 {
    if counts.is_null() || selected_slot >= slot_count { return 0; }
    let slots = unsafe { core::slice::from_raw_parts(counts, slot_count as usize) };
    let encoded = slots[selected_slot as usize];
    if encoded == 0 { 0 } else { (encoded / 1000).min(u16::MAX as u32) as u16 }
}

#[no_mangle]
pub unsafe extern "C" fn minefade_inventory_consume_selected(
    counts: *mut u32,
    slot_count: u32,
    selected_slot: u32,
    amount: u16,
) -> u8 {
    if counts.is_null() || selected_slot >= slot_count || amount == 0 { return 0; }
    let slots = unsafe { core::slice::from_raw_parts_mut(counts, slot_count as usize) };
    let encoded = slots[selected_slot as usize];
    if encoded == 0 { return 0; }
    let item = encoded / 1000;
    let quantity = encoded % 1000;
    if quantity < amount as u32 { return 0; }
    let remaining = quantity - amount as u32;
    slots[selected_slot as usize] = if remaining == 0 { 0 } else { item * 1000 + remaining };
    1
}

#[no_mangle]
pub unsafe extern "C" fn minefade_player_set_movement(
    state: *mut MfPlayerState,
    x: f32,
    y: f32,
) {
    let Some(state) = (unsafe { state.as_mut() }) else { return };
    let (x, y) = normalize_movement(x, y);
    state.movement_x = x;
    state.movement_y = y;
}

#[no_mangle]
pub unsafe extern "C" fn minefade_player_rotate(
    state: *mut MfPlayerState,
    delta_x: f32,
    delta_y: f32,
) {
    let Some(state) = (unsafe { state.as_mut() }) else { return };
    let dx = finite_or(delta_x, 0.0);
    let dy = finite_or(delta_y, 0.0);
    state.yaw = finite_or(state.yaw, -PI * 0.5) + dx * 0.0042;
    state.pitch = (finite_or(state.pitch, 0.0) - dy * 0.0042).clamp(-1.35, 1.35);
    state.yaw = (state.yaw + PI).rem_euclid(2.0 * PI) - PI;
}

#[no_mangle]
pub unsafe extern "C" fn minefade_player_jump(state: *mut MfPlayerState) {
    let Some(state) = (unsafe { state.as_mut() }) else { return };
    if state.grounded != 0 {
        state.vertical_velocity = 7.2;
        state.grounded = 0;
    }
}

#[no_mangle]
pub unsafe extern "C" fn minefade_player_update(
    state: *mut MfPlayerState,
    delta_seconds: f32,
    in_fluid: u8,
    is_solid: MfIsSolidCallback,
    user_data: *mut c_void,
) {
    let Some(state) = (unsafe { state.as_mut() }) else { return };

    state.x = finite_or(state.x, 8.0);
    state.y = finite_or(state.y, 13.0).clamp(1.0, 94.0);
    state.z = finite_or(state.z, 8.0);
    state.yaw = finite_or(state.yaw, -PI * 0.5);
    state.pitch = finite_or(state.pitch, 0.0).clamp(-1.35, 1.35);
    state.vertical_velocity = finite_or(state.vertical_velocity, 0.0);
    let (movement_x, movement_y) = normalize_movement(state.movement_x, state.movement_y);
    state.movement_x = movement_x;
    state.movement_y = movement_y;

    let dt = finite_or(delta_seconds, 0.0).clamp(0.0, 0.05);
    if dt <= 0.0 {
        return;
    }

    state.animation_time = (finite_or(state.animation_time, 0.0) + dt).rem_euclid(4096.0);

    let fluid = in_fluid != 0;
    let speed = if fluid { 2.35 } else { 5.0 };
    let forward_x = state.yaw.cos();
    let forward_z = state.yaw.sin();
    let right_x = -forward_z;
    let right_z = forward_x;
    let move_x = (forward_x * movement_y + right_x * movement_x) * speed * dt;
    let move_z = (forward_z * movement_y + right_z * movement_x) * speed * dt;

    if move_x.abs() + move_z.abs() > 0.0001 {
        state.walk_phase += dt * if fluid { 5.0 } else { 8.5 };
        state.walk_phase = state.walk_phase.rem_euclid(2.0 * PI);
    }

    let horizontal_steps = ((move_x.abs().max(move_z.abs()) / 0.12).ceil() as i32).max(1);
    let step_x = move_x / horizontal_steps as f32;
    let step_z = move_z / horizontal_steps as f32;

    for _ in 0..horizontal_steps {
        let next_x = state.x + step_x;
        if !unsafe { collides_at(is_solid, user_data, next_x, state.y, state.z) } {
            state.x = next_x;
        }
        let next_z = state.z + step_z;
        if !unsafe { collides_at(is_solid, user_data, state.x, state.y, next_z) } {
            state.z = next_z;
        }
    }

    // Keep a grounded player resting on a supporting block. Without this
    // contact probe, gravity is applied on every frame, the player drops a
    // fraction of a centimetre, then gets snapped up by collision again.
    // The probe happens after horizontal movement so walking off an edge still
    // starts a real fall.
    if state.grounded != 0 && state.vertical_velocity <= 0.0
        && unsafe { collides_at(is_solid, user_data, state.x, state.y - 0.08, state.z) }
    {
        state.vertical_velocity = 0.0;
        return;
    }

    let gravity = if fluid { 5.0 } else { 22.0 };
    let terminal_velocity = if fluid { -2.0 } else { -32.0 };
    state.vertical_velocity = (state.vertical_velocity - gravity * dt).max(terminal_velocity);
    let vertical_move = state.vertical_velocity * dt;
    let vertical_steps = ((vertical_move.abs() / 0.10).ceil() as i32).max(1);
    let vertical_step = vertical_move / vertical_steps as f32;
    state.grounded = 0;

    for _ in 0..vertical_steps {
        let next_y = state.y + vertical_step;
        if !unsafe { collides_at(is_solid, user_data, state.x, next_y, state.z) } {
            state.y = next_y;
            continue;
        }
        if vertical_step < 0.0 {
            let foot_block_y = floor_i32(next_y + 0.06);
            state.y = (foot_block_y + 1) as f32;
            state.vertical_velocity = 0.0;
            state.grounded = 1;
        } else {
            let head_block_y = floor_i32(next_y + BODY_HEIGHT - 0.06);
            state.y = head_block_y as f32 - BODY_HEIGHT + 0.06;
            state.vertical_velocity = 0.0;
        }
        break;
    }

    if state.y <= 0.0 {
        state.y = 1.0;
        state.vertical_velocity = 0.0;
        state.grounded = 1;
    }
    state.y = state.y.clamp(1.0, 94.0);
}

#[no_mangle]
pub unsafe extern "C" fn minefade_player_compute_pose(
    state: *const MfPlayerState,
    animation_quality: u8,
    animations_enabled: u8,
    output: *mut MfPlayerPose,
) {
    let Some(output) = (unsafe { output.as_mut() }) else { return };
    *output = MfPlayerPose::default();
    if animations_enabled == 0 {
        return;
    }
    let Some(state) = (unsafe { state.as_ref() }) else { return };

    let quality = quality_scale(animation_quality.min(2));
    let move_amount = (state.movement_x * state.movement_x
        + state.movement_y * state.movement_y).sqrt().clamp(0.0, 1.0);
    let walking = move_amount > 0.05;
    let gait = finite_or(state.walk_phase, 0.0);
    let idle = finite_or(state.animation_time, 0.0);
    let jump = (state.vertical_velocity.abs() / 7.2).clamp(0.0, 1.0);
    let swing = if walking {
        gait.sin() * 0.34 * move_amount * quality
    } else {
        (idle * 1.35).sin() * 0.035 * quality
    };
    let breath = (idle * 1.9).sin() * 0.018 * quality;
    let settle = if state.grounded == 0 { jump * 0.05 } else { 0.0 };

    let blink_cycle = idle.rem_euclid(5.1);
    let blink = if blink_cycle > 4.88 {
        ((blink_cycle - 4.88) / 0.22).sin().abs()
    } else if blink_cycle < 0.10 {
        (1.0 - blink_cycle / 0.10).max(0.0) * 0.6
    } else {
        0.0
    };

    *output = MfPlayerPose {
        left_arm_pitch: swing * 0.96 - settle,
        right_arm_pitch: -swing * 0.96 - settle,
        left_leg_pitch: -swing,
        right_leg_pitch: swing,
        torso_bob: if walking {
            (gait * 2.0).cos().abs() * 0.026 * quality
        } else {
            breath
        },
        torso_pitch: if walking {
            state.movement_y.clamp(-1.0, 1.0) * 0.065
        } else {
            breath * 0.45
        },
        head_yaw: (idle * 0.72).sin() * 0.035 * quality,
        head_pitch: (idle * 1.05).sin() * 0.022 * quality - settle,
        eyelid: blink.clamp(0.0, 1.0),
        jump_amount: jump,
    };
}

#[no_mangle]
pub unsafe extern "C" fn minefade_entity_update(
    state: *mut MfEntityState,
    delta_seconds: f32,
    is_solid: MfIsSolidCallback,
    surface_height: MfSurfaceHeightCallback,
    user_data: *mut c_void,
) {
    let Some(state) = (unsafe { state.as_mut() }) else { return };
    let dt = finite_or(delta_seconds, 0.0).clamp(0.0, 0.05);
    if dt <= 0.0 {
        return;
    }
    state.phase = (finite_or(state.phase, 0.0)
        + dt * finite_or(state.speed, 0.0).clamp(0.0, 3.0)).rem_euclid(4096.0);

    let target_x = state.x + state.phase.sin() * dt * 0.5;
    let target_z = state.z + (state.phase * 0.7).cos() * dt * 0.5;
    let y_cell = floor_i32(state.y - 0.05);
    if unsafe { cell_is_solid(is_solid, user_data, floor_i32(target_x), y_cell, floor_i32(target_z)) } {
        state.x = target_x;
        state.z = target_z;
    }
    if let Some(get_height) = surface_height {
        state.y = unsafe { get_height(floor_i32(state.x), floor_i32(state.z), user_data) } as f32 + 1.0;
    }
}

#[no_mangle]
pub unsafe extern "C" fn minefade_entity_compute_pose(
    kind: i32,
    phase: f32,
    animation_time: f32,
    animation_quality: u8,
    animations_enabled: u8,
    output: *mut MfEntityPose,
) {
    let Some(output) = (unsafe { output.as_mut() }) else { return };
    *output = MfEntityPose::default();
    if animations_enabled == 0 {
        return;
    }
    let quality = quality_scale(animation_quality.min(2));
    let phase = finite_or(phase, 0.0);
    let time = finite_or(animation_time, 0.0);
    let gait = (phase * 8.0).sin() * 0.24 * quality;
    let idle = (time * 1.2 + phase).sin();
    let blink_cycle = (time + phase * 0.12).rem_euclid(6.0);
    let eyelid = if blink_cycle > 5.72 {
        ((blink_cycle - 5.72) / 0.28 * PI).sin().abs()
    } else {
        0.0
    };

    if kind == 2 {
        let flap = (phase * 13.0).sin() * 0.72 * quality;
        *output = MfEntityPose {
            front_left_leg_pitch: (phase * 8.0).sin() * 0.08 * quality,
            front_right_leg_pitch: -(phase * 8.0).sin() * 0.08 * quality,
            rear_left_leg_pitch: 0.0,
            rear_right_leg_pitch: 0.0,
            body_bob: (phase * 13.0).sin().abs() * 0.035 * quality,
            head_pitch: idle * 0.045 * quality,
            head_yaw: (time * 0.62).sin() * 0.035 * quality,
            tail_yaw: (phase * 2.4).sin() * 0.12 * quality,
            left_wing_roll: flap,
            right_wing_roll: -flap,
            eyelid: eyelid.clamp(0.0, 1.0),
        };
    } else {
        *output = MfEntityPose {
            front_left_leg_pitch: gait,
            front_right_leg_pitch: -gait,
            rear_left_leg_pitch: -gait,
            rear_right_leg_pitch: gait,
            body_bob: (phase * 16.0).cos().abs() * 0.018 * quality,
            head_pitch: idle * 0.05 * quality,
            head_yaw: (time * 0.48 + phase).sin() * 0.018 * quality,
            tail_yaw: (phase * 2.1).sin() * 0.16 * quality,
            left_wing_roll: 0.0,
            right_wing_roll: 0.0,
            eyelid: eyelid.clamp(0.0, 1.0),
        };
    }
}

#[no_mangle]
pub unsafe extern "C" fn minefade_environment_update(
    state: *mut MfEnvironmentState,
    delta_seconds: f32,
) {
    let Some(state) = (unsafe { state.as_mut() }) else { return };
    let dt = finite_or(delta_seconds, 0.0).clamp(0.0, 0.1);
    state.time_of_day = (finite_or(state.time_of_day, 0.25) + dt / 720.0).rem_euclid(1.0);
    let sun = (state.time_of_day * 2.0 * PI - PI * 0.5).sin();
    state.daylight = (0.17 + (sun + 1.0) * 0.415).clamp(0.17, 1.0);
}

#[no_mangle]
pub unsafe extern "C" fn minefade_environment_sky_color(
    state: *const MfEnvironmentState,
    output_rgb: *mut f32,
) {
    if output_rgb.is_null() {
        return;
    }
    let daylight = match unsafe { state.as_ref() } {
        Some(value) => finite_or(value.daylight, 1.0).clamp(0.17, 1.0),
        None => 1.0,
    };
    let night = 1.0 - daylight;
    unsafe {
        *output_rgb.add(0) = 0.42 - night * 0.30;
        *output_rgb.add(1) = 0.68 - night * 0.48;
        *output_rgb.add(2) = 0.88 - night * 0.30;
    }
}

#[no_mangle]
pub unsafe extern "C" fn minefade_environment_sun_direction(
    state: *const MfEnvironmentState,
    output_xyz: *mut f32,
) {
    if output_xyz.is_null() {
        return;
    }
    let time = match unsafe { state.as_ref() } {
        Some(value) => finite_or(value.time_of_day, 0.25),
        None => 0.25,
    };
    let angle = time * 2.0 * PI;
    unsafe {
        *output_xyz.add(0) = -angle.cos();
        *output_xyz.add(1) = -angle.sin().max(0.12);
        *output_xyz.add(2) = -0.35;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    extern "C" fn flat_ground(_x: i32, y: i32, _z: i32, _user: *mut c_void) -> u8 {
        if y <= 1 { 1 } else { 0 }
    }

    extern "C" fn flat_surface(_x: i32, _z: i32, _user: *mut c_void) -> i32 {
        0
    }

    #[test]
    fn inventory_merges_stacks_and_fills_empty_slots() {
        let mut slots = [2008u32, 0, 0, 0, 0, 0, 0, 0, 0];
        assert_eq!(unsafe { minefade_inventory_can_add(slots.as_ptr(), 9, 2, 60, 64) }, 1);
        assert_eq!(unsafe { minefade_inventory_add(slots.as_mut_ptr(), 9, 2, 60, 64) }, 1);
        assert_eq!(slots[0], 2064);
        assert_eq!(slots[1], 2004);
        assert_eq!(unsafe { minefade_inventory_selected_item_id(slots.as_ptr(), 9, 1) }, 2);
        assert_eq!(unsafe { minefade_inventory_consume_selected(slots.as_mut_ptr(), 9, 1, 4) }, 1);
        assert_eq!(slots[1], 0);
    }

    #[test]
    fn inventory_rejects_overflow_atomically_and_observes_stack_limit() {
        let mut slots = [2064u32, 2064, 3064, 0, 0, 0, 0, 0, 0];
        let before = slots;
        assert_eq!(unsafe { minefade_inventory_can_add(slots.as_ptr(), 3, 2, 1, 64) }, 0);
        assert_eq!(unsafe { minefade_inventory_add(slots.as_mut_ptr(), 3, 2, 1, 64) }, 0);
        assert_eq!(slots, before);
        let mut restricted = [0u32; 9];
        assert_eq!(unsafe { minefade_inventory_add(restricted.as_mut_ptr(), 9, 69, 9, 16) }, 1);
        assert_eq!(restricted[0], 69016);
        assert_eq!(restricted[1], 6909);
        assert_eq!(unsafe { minefade_inventory_consume_selected(restricted.as_mut_ptr(), 9, 0, 17) }, 0);
        assert_eq!(restricted[0], 69016);
    }

    #[test]
    fn movement_is_normalized_and_rotation_is_bounded() {
        let mut state = MfPlayerState { x: 0.0, y: 1.0, z: 0.0, grounded: 1, ..Default::default() };
        unsafe { minefade_player_set_movement(&mut state, 3.0, 4.0) };
        assert!((state.movement_x * state.movement_x + state.movement_y * state.movement_y - 1.0).abs() < 0.0001);
        unsafe { minefade_player_rotate(&mut state, 10000.0, 10000.0) };
        assert!((-PI..=PI).contains(&state.yaw));
        assert!((-1.35..=1.35).contains(&state.pitch));
    }

    #[test]
    fn jump_and_gravity_preserve_ground_and_vertical_limits() {
        let mut state = MfPlayerState { x: 0.5, y: 2.0, z: 0.5, grounded: 1, ..Default::default() };
        unsafe { minefade_player_jump(&mut state) };
        assert_eq!(state.grounded, 0);
        assert!(state.vertical_velocity > 7.0);
        for _ in 0..180 {
            unsafe { minefade_player_update(&mut state, 1.0 / 60.0, 0, Some(flat_ground), core::ptr::null_mut()) };
        }
        assert!(state.y >= 1.0);
        assert!(state.grounded != 0);
        assert_eq!(state.vertical_velocity, 0.0);
    }

    #[test]
    fn animations_are_finite_and_disable_cleanly() {
        let state = MfPlayerState { movement_y: 1.0, walk_phase: 1.2, animation_time: 2.0, grounded: 1, ..Default::default() };
        let mut pose = MfPlayerPose::default();
        unsafe { minefade_player_compute_pose(&state, 2, 1, &mut pose) };
        assert!(pose.left_arm_pitch.is_finite());
        assert!(pose.torso_bob.abs() > 0.0);
        unsafe { minefade_player_compute_pose(&state, 2, 0, &mut pose) };
        assert_eq!(pose, MfPlayerPose::default());
    }

    #[test]
    fn entities_and_environment_remain_deterministic() {
        let mut entity = MfEntityState { x: 2.0, y: 1.0, z: 2.0, phase: 0.0, speed: 0.5, kind: 2 };
        unsafe { minefade_entity_update(&mut entity, 0.016, Some(flat_ground), Some(flat_surface), core::ptr::null_mut()) };
        assert!(entity.x.is_finite() && entity.y.is_finite() && entity.z.is_finite());
        let mut pose = MfEntityPose::default();
        unsafe { minefade_entity_compute_pose(2, entity.phase, 1.0, 2, 1, &mut pose) };
        assert!(pose.left_wing_roll.abs() > 0.0);
        let mut env = MfEnvironmentState { time_of_day: 0.25, daylight: 1.0 };
        unsafe { minefade_environment_update(&mut env, 60.0) };
        assert!(env.time_of_day > 0.25 && env.daylight.is_finite());
    }
}
