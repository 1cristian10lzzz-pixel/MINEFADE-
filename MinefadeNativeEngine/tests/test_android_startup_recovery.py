#!/usr/bin/env python3
"""Source-level regression checks for Android startup recovery and phase-2 navigation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/minefade/nativeengine/MainActivity.java").read_text(encoding="utf-8")
SURFACE = (ROOT / "app/src/main/java/com/minefade/nativeengine/MinefadeSurfaceView.java").read_text(encoding="utf-8")
BRIDGE = (ROOT / "app/src/main/java/com/minefade/nativeengine/NativeBridge.java").read_text(encoding="utf-8")
ENGINE = (ROOT / "app/src/main/cpp/engine/Engine.cpp").read_text(encoding="utf-8")
RML = (ROOT / "app/src/main/cpp/ui/RmlUiRuntime.cpp").read_text(encoding="utf-8")
HOME = (ROOT / "app/src/main/java/com/minefade/nativeengine/MinefadeHomeScreen.java").read_text(encoding="utf-8")


def test_rmlui_failure_does_not_abort_a_valid_world():
    start = ENGINE.index("if (!ui_.Initialise(")
    ready = ENGINE.index("ready_ = true;", start)
    recovery = ENGINE[start:ready]
    assert "El HUD RmlUi no está disponible" in recovery
    assert "return failInitialisation" not in recovery
    assert "ready_ = true;" in ENGINE[ready:ready + 40]


def test_android_emergency_controls_and_selected_world_retry_are_connected():
    assert "private WorldManager.WorldInfo requestedWorld;" in MAIN
    assert "requestedWorld = world;" in MAIN
    assert "startGame(requestedMultiplayer, requestedPlayerSlot, requestedWorld)" in MAIN
    assert "showFallbackTouchControls();" in MAIN
    assert "dispatchFallbackWorldTouch" in MAIN
    assert "overlay.setOnTouchListener" in MAIN
    assert "nativeFallbackAction" in SURFACE
    assert "nativeIsUiReady" in SURFACE
    assert "nativeFallbackAction" in BRIDGE


def test_joystick_hit_box_is_shared_when_rmlui_is_unavailable():
    helper = RML.index("bool RmlUiRuntime::IsInMovementPad")
    touch = RML.index("bool RmlUiRuntime::Touch", helper)
    update = RML.index("void RmlUiRuntime::UpdateAndRender()", touch)
    assert helper < touch < update
    assert "if (IsInMovementPad(x, y))" in RML[touch:update]
    assert "ui_.IsInMovementPad(x, y)" in ENGINE


def test_phase_two_navigation_and_layout_regressions():
    assert "LinearLayout moduleRail = column();" in HOME
    assert "moduleScroll.addView(moduleRail" in HOME
    assert "pageHeader.addView(search" in HOME
    assert "pageHeader.addView(importButton" in HOME
    assert "for (int col = 0; col < 5; col++)" in HOME
    assert "SERVIDORES PÚBLICOS" not in HOME


if __name__ == "__main__":
    test_rmlui_failure_does_not_abort_a_valid_world()
    test_android_emergency_controls_and_selected_world_retry_are_connected()
    test_joystick_hit_box_is_shared_when_rmlui_is_unavailable()
    test_phase_two_navigation_and_layout_regressions()
    print("Minefade Android startup recovery and phase-2 UI checks passed.")
