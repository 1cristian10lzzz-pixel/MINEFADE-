package com.minefade.nativeengine;

/** Java-to-C++ boundary. All GL-facing calls are dispatched on the GLSurfaceView thread. */
public final class NativeBridge {
    static {
        System.loadLibrary("minefade_native");
    }

    private NativeBridge() { }

    public static native boolean nativeInit(float displayDensity, byte[] blockAtlasRgba, int atlasWidth, int atlasHeight);
    public static native void nativeResize(int width, int height);
    public static native void nativeRender();
    public static native void nativeTouch(int action, int pointerId, float x, float y);
    public static native void nativePause();
    public static native void nativeResume();
    public static native void nativeSetStoragePath(String path);
    public static native boolean nativeConsumeExitRequest();
    public static native int nativeConsumeSoundEvents();
}
