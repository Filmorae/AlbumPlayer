package dev.geduu.albumjukebox.client;

final class DeckGeometry {
    static final float DISC_X = 9.25F;
    static final float DISC_Z = 8F;
    static final float PIVOT_X = 2.75F;
    static final float PIVOT_Z = 11.75F;
    static final float NEEDLE_FRONT = -7.53F;
    static final float NEEDLE_BACK = -7.30F;

    private static final float OUTER_ANGLE = 31F;
    private static final double INNER_RADIUS = 2.25;
    private static final double ARM_LENGTH = -(NEEDLE_FRONT + NEEDLE_BACK) / 2.0;
    private static final double PIVOT_DISTANCE = Math.hypot(DISC_X - PIVOT_X, DISC_Z - PIVOT_Z);
    private static final double CENTER_ANGLE = Math.atan2(DISC_X - PIVOT_X, PIVOT_Z - DISC_Z);
    private static final double SUM_SQUARED = PIVOT_DISTANCE * PIVOT_DISTANCE + ARM_LENGTH * ARM_LENGTH;
    private static final double TWICE_PRODUCT = 2 * PIVOT_DISTANCE * ARM_LENGTH;
    private static final double OUTER_RADIUS = Math.sqrt(
        SUM_SQUARED - TWICE_PRODUCT * Math.cos(CENTER_ANGLE - Math.toRadians(OUTER_ANGLE)));

    private DeckGeometry() {}

    static float grooveAngle(double progress) {
        double radius = OUTER_RADIUS + (INNER_RADIUS - OUTER_RADIUS) * Math.clamp(progress, 0.0, 1.0);
        // Solve the triangle formed by the spindle, the pivot, and the stylus.
        double offset = Math.acos(Math.clamp((SUM_SQUARED - radius * radius) / TWICE_PRODUCT, -1.0, 1.0));
        return (float) Math.toDegrees(CENTER_ANGLE - offset);
    }
}
