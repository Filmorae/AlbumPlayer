package dev.geduu.albumjukebox.client;

import dev.geduu.albumjukebox.AlbumMenu;
import dev.geduu.albumjukebox.AlbumVisualState;
import dev.geduu.albumjukebox.AlbumVisualState.Arm;

final class DeckMotion {
    private static final float DEGREES_PER_SECOND = 200F;
    private static final float HOVER_LIFT = 0.38F;
    private static final long SWAP_HOLD_NANOS = 55_000_000L;
    private static final long MAX_PREDICTION_NANOS = 1_000_000_000L;
    private static final long FRAME_NANOS = AlbumMenu.FRAME_MILLIS * 1_000_000L;

    private long previousTime;
    private long swapUntil;
    private AlbumVisualState display;
    private int cueChange;
    private boolean cueing;
    private float angle;
    private float speed;
    private float swing;
    private float lift;

    void update(AlbumVisualState target, long receivedAt, long now) {
        float targetSwing = target.arm() == Arm.REST ? 0 : DeckGeometry.grooveAngle(progress(target, receivedAt, now));
        if (display == null || now - previousTime > MAX_PREDICTION_NANOS) {
            display = target;
            cueChange = target.cueChange();
            previousTime = now;
            cueing = false;
            swapUntil = 0;
            swing = targetSwing;
            lift = target.arm() == Arm.HOVER ? HOVER_LIFT : 0;
            speed = target.arm() == Arm.PLAY ? DEGREES_PER_SECOND : 0;
            return;
        }
        float seconds = Math.clamp((now - previousTime) / 1_000_000_000F, 0F, 0.1F);
        previousTime = now;

        float targetSpeed = target.arm() == Arm.PLAY ? DEGREES_PER_SECOND : 0;
        float newSpeed = approach(speed, targetSpeed, 1110F * seconds);
        angle = (angle + (speed + newSpeed) * 0.5F * seconds) % 360F;
        speed = newSpeed;

        boolean changingDisc = display.discChange() != target.discChange();
        cueing |= changingDisc || cueChange != target.cueChange() || ((swing == 0) != (targetSwing == 0));
        cueChange = target.cueChange();
        if (cueing) {
            if ((swing > 0 || targetSwing > 0) && lift < 1) {
                lift = approach(lift, 1, 9F * seconds);
                return;
            }
            if (changingDisc) {
                display = target;
                if (swing > 0 && target.hasDisc()) {
                    swapUntil = now + SWAP_HOLD_NANOS;
                }
            }
            if (now < swapUntil) {
                return;
            }
            float slew = Math.clamp(10F * Math.abs(targetSwing - swing), 55F, 175F);
            swing = approach(swing, targetSwing, slew * seconds);
            if (swing != targetSwing) {
                return;
            }
            cueing = false;
        }

        if (target.arm() == Arm.PLAY && lift == 0) {
            // A late calibration can briefly trail the estimate. Never backtrack during a continuous pass.
            float smoothing = (float) -Math.expm1(-16F * seconds);
            swing += Math.max(0, targetSwing - swing) * smoothing;
        } else if (target.arm() == Arm.HOVER && lift >= HOVER_LIFT) {
            swing = approach(swing, targetSwing, 40F * seconds);
        }
        float targetLift = target.arm() == Arm.HOVER ? HOVER_LIFT : 0;
        lift = approach(lift, targetLift, 7.5F * seconds);
    }

    private static double progress(AlbumVisualState state, long receivedAt, long now) {
        if (state.durationFrames() <= 0) {
            return 0;
        }
        long elapsed = state.arm() == Arm.PLAY ? Math.clamp(now - receivedAt, 0L, MAX_PREDICTION_NANOS) : 0L;
        return (state.positionFrames() + (double) elapsed / FRAME_NANOS) / state.durationFrames();
    }

    private static float approach(float current, float target, float distance) {
        return current < target ? Math.min(target, current + distance) : Math.max(target, current - distance);
    }

    AlbumVisualState display() { return display; }
    float angle() { return angle; }
    float swingAngle() { return swing; }
    float lift() { return lift * lift * (3F - 2F * lift); }
}
