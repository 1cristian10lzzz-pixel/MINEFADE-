package com.minefade.nativeengine;

/** Java-to-C++ boundary. All GL-facing calls are dispatched on the GLSurfaceView thread. */
public final class NativeBridge {
    static {
        System.loadLibrary("minefade_native");
    }

    private NativeBridge() { }

    public static native boolean nativeInit(float displayDensity, byte[] blockAtlasRgba, int atlasWidth, int atlasHeight);
    public static native String nativeGetLastInitError();
    public static native void nativeResize(int width, int height);
    public static native void nativeRender();
    public static native boolean nativeConsumeEngineFailure();
    public static native void nativeTouch(int action, int pointerId, float x, float y);
    public static native void nativePause();
    public static native void nativeResume();
    public static native void nativeSetStoragePath(String path);
    public static native boolean nativeConsumeExitRequest();
    public static native int nativeConsumeSoundEvents();
    public static native void nativeSetMultiplayerMode(boolean enabled, int playerSlot);
    public static native void nativeSetGraphicsOptions(int quality, int renderDistanceChunks, boolean waterWaves, float fieldOfView, boolean contactShadows);
    public static native void nativeSetInputOptions(float sensitivityX, float sensitivityY, float joystickDeadzone, boolean invertLookY, boolean leftHanded);
    public static native void nativeSetHudOptions(boolean showStats);
    public static native void nativeSetAvatarAppearance(int[] choices);
    public static native float[] nativeGetPlayerState();
    public static native void nativeSetRemotePlayers(float[] values);
    public static native int[] nativeConsumeBlockEdits();
    public static native void nativeApplyRemoteBlockEdits(int[] values);
}
