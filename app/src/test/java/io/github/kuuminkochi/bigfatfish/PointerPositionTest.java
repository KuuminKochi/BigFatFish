package io.github.kuuminkochi.bigfatfish;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PointerPositionTest {
    @Test public void fingerMotionDuringScrollDoesNotMoveStationaryCursor() {
        PointerPosition pointer = new PointerPosition();
        pointer.update(800f, 500f, 800f, 500f, true);
        pointer.update(800f, 500f, 600f, 900f, false);
        assertEquals(800f, pointer.x, 0f);
        assertEquals(500f, pointer.y, 0f);
        pointer.update(Float.NaN, Float.NaN, 100f, 200f, false);
        assertEquals(800f, pointer.x, 0f);
        assertEquals(500f, pointer.y, 0f);
        pointer.update(850f, 520f, 100f, 200f, false);
        assertEquals(850f, pointer.x, 0f);
        assertEquals(520f, pointer.y, 0f);
    }

    @Test public void unknownGestureDoesNotInventAnAnchorButRealMouseCanInitializeIt() {
        PointerPosition pointer = new PointerPosition();
        pointer.update(Float.NaN, Float.NaN, 100f, 200f, false);
        assertFalse(pointer.known);
        pointer.update(Float.NaN, Float.NaN, 700f, 400f, true);
        assertTrue(pointer.known);
        assertEquals(700f, pointer.x, 0f);
        assertEquals(400f, pointer.y, 0f);
    }
}
