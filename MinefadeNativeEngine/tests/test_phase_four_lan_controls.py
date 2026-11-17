#!/usr/bin/env python3
"""Source-level checks for phase-4 LAN discovery and touch-control integration."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/minefade/nativeengine"
LAN = (JAVA / "LanMultiplayerService.java").read_text(encoding="utf-8")
MAIN = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
HOME = (JAVA / "MinefadeHomeScreen.java").read_text(encoding="utf-8")
RML = (ROOT / "app/src/main/cpp/ui/RmlUiRuntime.cpp").read_text(encoding="utf-8")
ENGINE = (ROOT / "app/src/main/cpp/engine/Engine.cpp").read_text(encoding="utf-8")


def test_local_menu_starts_real_nsd_discovery_and_join_flow():
    assert 'private static final String SERVICE_TYPE = "_minefade._tcp."' in LAN
    assert "nsdManager.discoverServices(SERVICE_TYPE" in LAN
    assert "nsdManager.resolveService(service" in LAN
    assert "WifiManager.MulticastLock" in LAN
    assert "acquireMulticastLock();" in LAN
    assert "nsdManager.registerService(service" in LAN
    assert "server.bind(new InetSocketAddress(0))" in LAN
    assert "socket.connect(new InetSocketAddress(game.host, game.port), 5000)" in LAN
    assert "LanMultiplayerService.PROTOCOL_VERSION" not in MAIN  # protocol remains transport-owned
    assert "lanMultiplayer.discover(createLanListener(false))" in MAIN
    assert "lanMultiplayer.startHost(" in MAIN
    assert "lanMultiplayer.join(game," in MAIN
    assert "if (listener != null) listener.onSearchMultiplayer();" in HOME


def test_lan_protocol_checks_bounds_and_cleans_up():
    assert "parseInt(helloFields[1], -1) != PROTOCOL_VERSION" in LAN
    assert "Math.abs(x) > 1_000_000" in LAN
    assert "Math.abs(moveX) > 1.5f" in LAN
    assert "blockId < 0 || blockId > 65535" in LAN
    assert "MAX_PLAYERS = 8" in LAN
    assert "releaseMulticastLock();" in LAN
    assert "io.shutdownNow();" in LAN


def test_touch_layout_has_modes_and_hand_mirroring():
    assert '"control_mode"' in HOME
    assert "controlMode_ == 1" in RML and "controlMode_ == 2" in RML
    assert "leftHanded_" in RML
    assert "leftHanded ? Gravity.RIGHT : Gravity.LEFT" in MAIN
    assert "dispatchFallbackWorldTouch" in MAIN
    assert "ui_.IsInMovementPad(x, y)" in ENGINE


if __name__ == "__main__":
    test_local_menu_starts_real_nsd_discovery_and_join_flow()
    test_lan_protocol_checks_bounds_and_cleans_up()
    test_touch_layout_has_modes_and_hand_mirroring()
    print("Minefade phase-4 LAN/touch checks passed.")
