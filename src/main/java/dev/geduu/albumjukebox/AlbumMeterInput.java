package dev.geduu.albumjukebox;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AlbumMeterInput {
    public static final int BANDS = 24;
    private static final int SAMPLE_RATE = 48_000;
    private static final int WINDOW_SIZE = 4096;
    private static final int WINDOW_MASK = WINDOW_SIZE - 1;
    private static final int GUI_HOP_SIZE = 960;
    private static final int WORLD_HOP_SIZE = 1920;
    private static final int MAX_WORLD_CHANNELS = 4;
    private static final long WORLD_LEASE_NANOS = 300_000_000L;
    private static final long PCM_GAP_NANOS = 250_000_000L;
    private static final int LOW_HZ = 47;
    private static final int HIGH_HZ = 15_000;
    private static final double BAND_RATIO = Math.pow((double) HIGH_HZ / LOW_HZ, 1.0 / BANDS);
    private static final double[] WINDOW = new double[WINDOW_SIZE];
    private static final double[] COS = new double[WINDOW_SIZE / 2];
    private static final double[] SIN = new double[WINDOW_SIZE / 2];
    private static final int[] REVERSE = new int[WINDOW_SIZE];
    private static final int[] EDGES = new int[BANDS + 1];
    private static final double NORMALIZATION;

    // The voice-chat callback and the renderer use the same analysis by channel.
    // Each world lease expires unless a nearby front face is still rendered.
    private static final Map<UUID, Meter> METERS = new ConcurrentHashMap<>();
    private static volatile UUID guiChannel;

    static {
        double windowSum = 0;
        for (int i = 0; i < WINDOW_SIZE; i++) {
            WINDOW[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / WINDOW_MASK);
            windowSum += WINDOW[i];
            REVERSE[i] = Integer.reverse(i) >>> (Integer.SIZE - Integer.numberOfTrailingZeros(WINDOW_SIZE));
        }
        NORMALIZATION = 2 / (windowSum * 32768);
        for (int i = 0; i < COS.length; i++) {
            double angle = -2 * Math.PI * i / WINDOW_SIZE;
            COS[i] = Math.cos(angle);
            SIN[i] = Math.sin(angle);
        }
        for (int i = 0; i < EDGES.length; i++) {
            EDGES[i] = (int) Math.ceil(LOW_HZ * Math.pow(BAND_RATIO, i) * WINDOW_SIZE / SAMPLE_RATE);
        }
    }

    private AlbumMeterInput() {}

    public static final class Sample {
        private final float[] levels;
        private final long time;

        private Sample(float[] levels) {
            this.levels = levels;
            time = System.nanoTime();
        }

        public float level(int band) { return levels[band]; }
        public long time() { return time; }
    }

    public static int bandForFrequency(int hz) {
        return Math.clamp((int) Math.floor(Math.log((double) hz / LOW_HZ) / Math.log(BAND_RATIO)), 0, BANDS - 1);
    }

    public static synchronized void watchGui(UUID channel) {
        if (Objects.equals(guiChannel, channel)) {
            return;
        }
        if (channel != null) {
            METERS.computeIfAbsent(channel, Meter::new);
        }
        guiChannel = channel;
        prune();
    }

    /** Refresh a short-lived world-render subscription. The nearest four streams win. */
    public static synchronized boolean watchWorld(UUID channel, double distanceSquared, long now) {
        if (channel == null) {
            return false;
        }
        Meter meter = METERS.get(channel);
        if (meter == null || meter.worldUntil <= now) {
            int active = 0;
            Meter farthest = null;
            for (Meter candidate : METERS.values()) {
                if (candidate.worldUntil > now) {
                    active++;
                    if (farthest == null || candidate.distanceSquared > farthest.distanceSquared) {
                        farthest = candidate;
                    }
                }
            }
            if (active >= MAX_WORLD_CHANNELS) {
                if (farthest == null || distanceSquared >= farthest.distanceSquared) {
                    return false;
                }
                farthest.worldUntil = 0L;
                if (!farthest.channel.equals(guiChannel)) {
                    METERS.remove(farthest.channel, farthest);
                }
            }
            meter = METERS.computeIfAbsent(channel, Meter::new);
        }
        meter.distanceSquared = distanceSquared;
        meter.worldUntil = now + WORLD_LEASE_NANOS;
        return true;
    }

    public static synchronized void unwatchWorld(UUID channel) {
        if (channel == null) return;
        Meter meter = METERS.get(channel);
        if (meter != null) {
            meter.worldUntil = 0L;
            if (!channel.equals(guiChannel)) METERS.remove(channel, meter);
        }
    }

    public static synchronized void prune() {
        long now = System.nanoTime();
        METERS.entrySet().removeIf(entry -> !entry.getKey().equals(guiChannel) && entry.getValue().worldUntil <= now);
    }

    public static synchronized void clear() {
        guiChannel = null;
        METERS.clear();
    }

    public static Sample sample(UUID channel) {
        Meter current = channel == null ? null : METERS.get(channel);
        return current == null ? null : current.sample;
    }

    static void accept(UUID source, short[] pcm) {
        if (source == null || pcm == null) return;
        Meter current = METERS.get(source);
        if (current == null) return;
        long now = System.nanoTime();
        boolean gui = source.equals(guiChannel);
        if (gui || current.worldUntil > now) {
            current.accept(pcm, gui ? GUI_HOP_SIZE : WORLD_HOP_SIZE, now);
        }
    }

    private static final class Meter {
        private final UUID channel;
        private final short[] history = new short[WINDOW_SIZE];
        private final double[] real = new double[WINDOW_SIZE];
        private final double[] imaginary = new double[WINDOW_SIZE];
        private int write;
        private int pending;
        private volatile Sample sample;
        private volatile long worldUntil;
        private double distanceSquared;
        private long lastPcm;

        private Meter(UUID channel) {
            this.channel = channel;
        }

        private synchronized void accept(short[] pcm, int hopSize, long now) {
            if (pcm.length == 0 || now - lastPcm > PCM_GAP_NANOS) {
                write = 0;
                pending = 0;
                sample = null;
            }
            lastPcm = now;
            if (pcm.length == 0) return;
            for (short value : pcm) {
                history[write] = value;
                write = (write + 1) & WINDOW_MASK;
            }
            pending += pcm.length;
            if (pending < (sample == null ? WINDOW_SIZE : hopSize)) {
                return;
            }
            pending = 0;
            sample = new Sample(spectrum());
        }

        private float[] spectrum() {
            // The write position now points at the oldest sample in the analysis window.
            for (int i = 0; i < WINDOW_SIZE; i++) {
                int slot = REVERSE[i];
                real[slot] = history[(write + i) & WINDOW_MASK] * WINDOW[i];
                imaginary[slot] = 0;
            }
            for (int half = 1; half < WINDOW_SIZE; half *= 2) {
                int length = half * 2;
                int stride = WINDOW_SIZE / length;
                for (int base = 0; base < WINDOW_SIZE; base += length) {
                    for (int i = 0; i < half; i++) {
                        int low = base + i;
                        int high = low + half;
                        int phase = i * stride;
                        double re = real[high] * COS[phase] - imaginary[high] * SIN[phase];
                        double im = real[high] * SIN[phase] + imaginary[high] * COS[phase];
                        real[high] = real[low] - re;
                        imaginary[high] = imaginary[low] - im;
                        real[low] += re;
                        imaginary[low] += im;
                    }
                }
            }
            float[] levels = new float[BANDS];
            for (int band = 0; band < BANDS; band++) {
                double peakPower = 0;
                for (int bin = EDGES[band]; bin < EDGES[band + 1]; bin++) {
                    double power = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
                    peakPower = Math.max(peakPower, power);
                }
                levels[band] = (float) (Math.sqrt(peakPower) * NORMALIZATION);
            }
            return levels;
        }
    }
}
