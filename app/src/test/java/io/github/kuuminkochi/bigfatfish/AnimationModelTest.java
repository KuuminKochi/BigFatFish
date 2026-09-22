package io.github.kuuminkochi.bigfatfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void classicSpringSettlesToExactRestWithoutIdleDrive() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(2f, 7f, 60_000L, true, -1, 500L);
        model.configurePhysics(false, 7f, 20f);

        model.onPointer(80f, 0f, 0, true, 10L);
        assertTrue(Math.abs(model.angleRadians()) > 0.000001f);
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
    public void realisticRopeSlackensBendsAndGravityLaterTightensIt() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(1f, 7f, 60_000L, false, -1, 500L);
        model.configurePhysics(true, 40f, 20f);

        model.onPointer(0f, 28f, 0, true, 16L);
        float slackDistance = (float) Math.hypot(model.pendantX(), model.pendantY());
        assertTrue(slackDistance < 40f);
        assertTrue(Math.abs(model.ropePointX(4)) > 0.001f);
        float pathLength = 0f;
        for (int i = 1; i < model.ropePointCount(); i++) {
            pathLength += (float) Math.hypot(model.ropePointX(i) - model.ropePointX(i - 1),
                    model.ropePointY(i) - model.ropePointY(i - 1));
        }
        assertTrue(pathLength > slackDistance + 1f);

        model.advance(1_500L);
        float tautDistance = (float) Math.hypot(model.pendantX(), model.pendantY());
        assertTrue(tautDistance > slackDistance);
        assertTrue(tautDistance <= 40.01f);
        assertTrue(Float.isFinite(model.angleRadians()));
    }

    @Test
    public void realisticRopeReachIsBoundedAndZeroLengthStaysFinite() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(2f, 7f, 60_000L, false, -1, 500L);
        model.configurePhysics(true, 0f, 32f);

        model.onPointer(Float.MAX_VALUE, -Float.MAX_VALUE, 0, true, 1L);
        model.advance(60_000L);

        assertEquals(0f, model.pendantX(), 0f);
        assertEquals(0f, model.pendantY(), 0f);
        assertTrue(Float.isFinite(model.angleRadians()));
        assertTrue(model.isAtRest());
        for (int i = 0; i < model.ropePointCount(); i++) {
            assertTrue(Float.isFinite(model.ropePointX(i)));
            assertTrue(Float.isFinite(model.ropePointY(i)));
        }
    }

    @Test
    public void realisticLengthChangesKeepEveryRopeNodeWithinConfiguredReach() {
        AnimationModel model = new AnimationModel(0L);
        model.configure(1f, 4f, 60_000L, false, -1, 500L);
        model.configurePhysics(true, 80f, 20f);
        model.onPointer(120f, -75f, 0, true, 16L);
        model.advance(1_000L);

        for (int i = 0; i < model.ropePointCount(); i++) {
            float distance = (float) Math.hypot(model.ropePointX(i), model.ropePointY(i));
            assertTrue(distance <= 80.01f);
        }
        model.configurePhysics(true, 20f, 20f);
        model.onPointer(-300f, 100f, 0, true, 1_001L);
        for (int i = 0; i < model.ropePointCount(); i++) {
            assertTrue(Math.hypot(model.ropePointX(i), model.ropePointY(i)) <= 20.01f);
        }
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
        assertEquals(beforeChange, model.opacity(), 0.0001f);
        model.advance(1_550L);
        assertEquals(.25f, model.opacity(), 0f);
    }

    @Test
    public void zeroActiveOpacityDoesNotFlashOpaqueOnStartup() {
        AnimationModel model = new AnimationModel(10_000L);
        model.configureOpacity(true, 0f, 0f);
        assertEquals(0f, model.opacity(), 0f);
        model.advance(10_033L);
        assertEquals(0f, model.opacity(), 0f);
    }

    @Test
    public void renderingInterpolatesBetweenFixedStepsWithoutChangingPhysics() {
        AnimationModel model = new AnimationModel(0);
        model.configurePhysics(true, 80f, 24f);
        model.onPointer(0f, 60f, 0, true, 0);
        model.advance(20);
        int end = model.ropePointCount() - 1;
        float simulationY = model.pendantY();
        float firstRenderY = model.renderRopePointY(end);
        model.advance(23);
        assertEquals(simulationY, model.pendantY(), 0f);
        assertTrue(model.renderRopePointY(end) > firstRenderY);
        assertTrue(model.renderRopePointY(end) <= simulationY);
    }

    @Test
    public void interpolationDoesNotTrailAnOldAnchorOrLength() {
        AnimationModel model = new AnimationModel(0);
        model.configurePhysics(true, 80f, 24f);
        model.onPointer(20f, 30f, 0, true, 16);
        model.advance(20);
        model.onPointer(300f, -300f, 0, true, 23);
        int end = model.ropePointCount() - 1;
        assertEquals(model.pendantX(), model.renderRopePointX(end), 0f);
        assertEquals(model.pendantY(), model.renderRopePointY(end), 0f);
        model.configurePhysics(true, 10f, 24f);
        for (int i = 0; i < model.ropePointCount(); i++) {
            assertTrue(Math.hypot(model.renderRopePointX(i), model.renderRopePointY(i)) <= 10.01f);
        }
    }
}
