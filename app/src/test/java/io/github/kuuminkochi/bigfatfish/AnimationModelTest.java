package io.github.kuuminkochi.bigfatfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AnimationModelTest {
    @Test
    public void sleepPersistsUntilGenuinePointerActivity() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(1f, 7f, 1_000L, true, -1, 500L);

        model.advance(1_001L);
        assertEquals(AnimationModel.SLEEP, model.mode());
        model.advance(60_000L);
        assertEquals(AnimationModel.SLEEP, model.mode());

        model.onPointer(0f, 0f, 0, true, 60_001L);
        assertEquals(AnimationModel.ACTIVE, model.mode());
        model.advance(60_002L);
        assertEquals(AnimationModel.ACTIVE, model.mode());
    }

    @Test
    public void heldButtonPreventsSleepAndReleaseCanBecomeIdle() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(1f, 7f, 1_000L, true, -1, 500L);

        model.onPointer(0f, 0f, 1, true, 100L);
        model.advance(20_000L);
        assertEquals(AnimationModel.ACTIVE, model.mode());

        model.onPointer(0f, 0f, 0, true, 20_001L);
        model.advance(21_002L);
        assertEquals(AnimationModel.SLEEP, model.mode());
    }

    @Test
    public void pendulumSettlesToExactRestWithoutIdleDrive() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(2f, 7f, 60_000L, true, -1, 500L);

        model.onPointer(80f, 0f, 0, true, 10L);
        assertNotEquals(0f, model.angleRadians(), 0.000001f);
        model.advance(5_000L);
        assertTrue(model.isAtRest());
        assertEquals(0f, model.angleRadians(), 0f);

        model.advance(25_000L);
        assertTrue(model.isAtRest());
        assertEquals(0f, model.angleRadians(), 0f);
    }

    @Test
    public void movementDoesNotRestartActiveSpriteTimeline() {
        AnimationModel model = new AnimationModel(100L);
        model.configure(1f, 7f, 60_000L, true, -1, 500L);

        model.advance(600L);
        long before = model.frameElapsedMs(600L);
        model.onPointer(1f, 0f, 0, true, 700L);
        long after = model.frameElapsedMs(700L);
        assertTrue(before > 0L);
        assertTrue(after > before);
        assertFalse(model.mode() == AnimationModel.SLEEP);
    }

    @Test
    public void reactionSupportsFullManifestDuration() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(1f, 7f, 60_000L, false, 1, 240_000L);

        model.onPointer(0f, 0f, 1, true, 1L);
        model.advance(10_001L);
        assertEquals(AnimationModel.REACT, model.mode());

        model.advance(240_002L);
        assertEquals(AnimationModel.ACTIVE, model.mode());
    }
    @Test
    public void realisticPhysicsRemainsFiniteAtExtremePointerPacketsAndZeroLength() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(2f, 7f, 60_000L, false, -1, 500L);
        model.configurePhysics(true, 0f);

        model.onPointer(Float.MAX_VALUE, -Float.MAX_VALUE, 0, true, 1L);
        model.advance(60_000L);

        assertTrue(Float.isFinite(model.angleRadians()));
    }

    @Test
    public void realisticLengthChangesPendulumResponse() {
        AnimationModel shortModel = new AnimationModel(0L);
        AnimationModel longModel = new AnimationModel(0L);
        shortModel.configure(1f, 2f, 60_000L, false, -1, 500L);
        longModel.configure(1f, 2f, 60_000L, false, -1, 500L);
        shortModel.configurePhysics(true, 8f);
        longModel.configurePhysics(true, 80f);

        shortModel.onPointer(20f, 10f, 0, true, 16L);
        longModel.onPointer(20f, 10f, 0, true, 16L);
        shortModel.advance(300L);
        longModel.advance(300L);

        assertNotEquals(shortModel.angleRadians(), longModel.angleRadians(), 0.0001f);
    }

    @Test
    public void fadingIsIndependentOfSleepAndHeldButtonsAndReversesOnInput() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(1f, 7f, 1_000L, false, -1, 500L);
        model.configureOpacity(true, 1f, 0f);

        model.advance(1_351L);
        assertEquals(0f, model.opacity(), 0.0001f);
        assertEquals(AnimationModel.ACTIVE, model.mode());

        model.onPointer(0f, 0f, 1, true, 1_352L);
        model.advance(20_000L);
        assertEquals(1f, model.opacity(), 0f);

        model.onPointer(0f, 0f, 0, true, 20_001L);
        model.advance(21_352L);
        assertEquals(0f, model.opacity(), 0.0001f);
        model.onPointer(0f, 0f, 0, true, 21_353L);
        assertEquals(0f, model.opacity(), 0f);
        model.advance(21_528L);
        assertEquals(.5f, model.opacity(), .001f);
        model.advance(21_703L);
        assertEquals(1f, model.opacity(), 0f);
    }

    @Test
    public void opacityConfigurationDoesNotRestartAnOngoingFade() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(1f, 7f, 1_000L, false, -1, 500L);
        model.configureOpacity(true, 1f, 0f);
        model.advance(1_175L);
        float before = model.opacity();

        model.configureOpacity(true, 1f, 0f);

        assertEquals(before, model.opacity(), 0.0001f);
        model.advance(1_200L);
        assertTrue(model.opacity() < before);
        float beforeChange = model.opacity();
        model.configureOpacity(true, 1f, .25f);
        assertEquals(beforeChange, model.opacity(), .0001f);
        model.advance(1_550L);
        assertEquals(.25f, model.opacity(), .0001f);
    }

    @Test
    public void zeroActiveOpacityDoesNotFlashOpaqueOnStartup() {
        AnimationModel model = new AnimationModel(10_000L);
        model.configureOpacity(true, 0f, 0f);
        assertEquals(0f, model.opacity(), 0f);
        model.advance(10_033L);
        assertEquals(0f, model.opacity(), 0f);
    }
}
