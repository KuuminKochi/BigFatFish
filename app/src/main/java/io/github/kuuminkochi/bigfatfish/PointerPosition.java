package io.github.kuuminkochi.bigfatfish;

/** Cursor anchor in display coordinates; touchpad contact positions are not cursor positions. */
final class PointerPosition {
    boolean known;
    float x;
    float y;

    void update(float cursorX, float cursorY, float rawX, float rawY, boolean rawIsCursor) {
        if (Float.isFinite(cursorX) && Float.isFinite(cursorY)) {
            x = cursorX;
            y = cursorY;
            known = true;
        } else if (rawIsCursor && Float.isFinite(rawX) && Float.isFinite(rawY)) {
            x = rawX;
            y = rawY;
            known = true;
        }
        // No valid cursor coordinates: keep the last anchor, or remain hidden until one arrives.
    }
}
