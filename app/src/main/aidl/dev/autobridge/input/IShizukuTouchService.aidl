package dev.autobridge.input;

/** Runs inside the Shizuku shell-UID user-service process. */
interface IShizukuTouchService {
    boolean tap(int x, int y);
    boolean swipe(int fromX, int fromY, int toX, int toY, long durationMs);
    boolean keyevent(int keyCode);

    /** Scrcpy/ScreenOnAuto-style panel power control; never locks the device. */
    boolean isDisplayPowerControlAvailable();
    boolean setDisplayPower(boolean on);

    /** Hidden InputManager MotionEvent injection capability probe and pointer stream. */
    boolean isRealTouchAvailable();
    boolean touchDown(int pointerId, int x, int y);
    boolean touchMove(in int[] pointerIds, in int[] xs, in int[] ys);
    boolean touchUp(int pointerId, int x, int y);
    boolean touchCancel();

    /**
     * Runs a bounded shell command in the Shizuku shell-UID process and returns its combined
     * output, or null on failure. Used by the installer-source spoof (pm set-installer-package),
     * which the projection route needs so Android Auto treats a sideloaded build as a store app.
     */
    @nullable String runShellCommand(in String[] args, long timeoutMs);

    void destroy();
}
