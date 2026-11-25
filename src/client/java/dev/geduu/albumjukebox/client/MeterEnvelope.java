package dev.geduu.albumjukebox.client;

import java.util.Arrays;

/** Keeps the bar release and optional independent peak marker separate. */
final class MeterEnvelope {
    private final float[] shown;
    private final float[] peaks;
    private final long[] peakUntil;
    private final float release;
    private final float peakRelease;
    private final long holdNanos;

    MeterEnvelope(int bands, float release, float peakRelease, long holdNanos) {
        shown = new float[bands];
        peaks = new float[bands];
        peakUntil = new long[bands];
        this.release = release;
        this.peakRelease = peakRelease;
        this.holdNanos = holdNanos;
    }

    void update(int band, float target, float delta, long now, boolean peakHold) {
        shown[band] = Math.max(target, shown[band] - delta * release);
        if (!peakHold) {
            // No freestanding peak, nor any saved peak to reappear when re-enabled.
            peaks[band] = 0;
            peakUntil[band] = 0;
        } else if (target > 0 && target >= peaks[band]) {
            peaks[band] = target;
            peakUntil[band] = now + holdNanos;
        } else if (now >= peakUntil[band]) {
            peaks[band] = Math.max(shown[band], peaks[band] - delta * peakRelease);
        }
    }

    int lit(int band, int rows) { return Math.round(shown[band] * rows); }
    int peak(int band, int rows) { return Math.round(peaks[band] * rows); }

    void reset() {
        Arrays.fill(shown, 0);
        Arrays.fill(peaks, 0);
        Arrays.fill(peakUntil, 0);
    }
}
