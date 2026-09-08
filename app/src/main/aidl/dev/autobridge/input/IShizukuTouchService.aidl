package dev.autobridge.input;

/** Runs inside the Shizuku shell-UID user-service process. */
interface IShizukuTouchService {
    boolean tap(int x, int y);
    boolean swipe(int fromX, int fromY, int toX, int toY, long durationMs);
    boolean keyevent(int keyCode);
    void destroy();
}
