package dev.geduu.albumjukebox;

import java.util.UUID;

/** Plain Java regression check: no audio device, Minecraft world, or external test libraries required. */
public final class MeterInputCheck {
    private static final short[] LOW = tone(250);
    private static final short[] HIGH = tone(4000);

    public static void main(String[] args) {
        AlbumMeterInput.clear();
        UUID low = UUID.randomUUID(), high = UUID.randomUUID();
        check(AlbumMeterInput.watchWorld(low, 4, System.nanoTime()), "first world subscription");
        check(AlbumMeterInput.watchWorld(high, 9, System.nanoTime()), "second world subscription");
        feed(low, LOW, 5);
        feed(high, HIGH, 5);
        check(peak(low) == AlbumMeterInput.bandForFrequency(250), "low frequency isolated");
        check(peak(high) == AlbumMeterInput.bandForFrequency(4000), "high frequency isolated");

        var first = AlbumMeterInput.sample(low);
        feed(low, LOW, 1);
        check(AlbumMeterInput.sample(low) == first, "world hop is two 20 ms packets");
        feed(low, LOW, 1);
        check(AlbumMeterInput.sample(low) != first, "world spectrum updates at 25 Hz");

        first = AlbumMeterInput.sample(low);
        AlbumMeterInput.watchGui(low);
        check(AlbumMeterInput.sample(low) == first, "GUI shares world FFT state");
        feed(low, LOW, 1);
        check(AlbumMeterInput.sample(low) != first, "GUI spectrum updates at 50 Hz");
        AlbumMeterInput.watchGui(null);
        check(AlbumMeterInput.sample(low) != null && AlbumMeterInput.sample(high) != null,
            "closing GUI does not cancel world channels");
        AlbumMeterInput.unwatchWorld(low);
        check(AlbumMeterInput.sample(low) == null && AlbumMeterInput.sample(high) != null,
            "stopping one deck releases only that channel");

        UUID gui = UUID.randomUUID();
        AlbumMeterInput.watchGui(gui);
        feed(gui, LOW, 5);
        check(AlbumMeterInput.sample(gui) != null, "GUI-only subscription");
        AlbumMeterInput.watchGui(null);
        check(AlbumMeterInput.sample(gui) == null, "GUI-only subscription released");

        AlbumMeterInput.clear();
        UUID[] channels = new UUID[5];
        for (int i = 0; i < channels.length; i++) channels[i] = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            check(AlbumMeterInput.watchWorld(channels[i], i + 4, System.nanoTime()), "world budget admission " + i);
            feed(channels[i], LOW, 5);
        }
        check(!AlbumMeterInput.watchWorld(channels[4], 10, System.nanoTime()), "fifth distant stream denied");
        check(AlbumMeterInput.watchWorld(channels[4], 1, System.nanoTime()), "nearby stream evicts farthest");
        check(AlbumMeterInput.sample(channels[3]) == null, "farthest stream removed");
        AlbumMeterInput.watchGui(channels[3]);
        feed(channels[3], HIGH, 5);
        check(AlbumMeterInput.sample(channels[3]) != null, "GUI always allowed beyond world budget");

        AlbumMeterInput.clear();
        UUID stale = UUID.randomUUID();
        AlbumMeterInput.watchWorld(stale, 1, System.nanoTime() - 400_000_000L);
        feed(stale, LOW, 5);
        check(AlbumMeterInput.sample(stale) == null, "expired lease skips FFT");
        AlbumMeterInput.prune();
        check(AlbumMeterInput.watchWorld(stale, 1, System.nanoTime()), "fresh lease after cleanup");
        feed(stale, LOW, 5);
        check(AlbumMeterInput.sample(stale) != null, "analysis restarts");
        AlbumMeterInput.accept(stale, new short[0]);
        check(AlbumMeterInput.sample(stale) == null, "stream flush clears sample");
        AlbumMeterInput.clear();
        check(AlbumMeterInput.sample(stale) == null, "disconnect clears state");
        System.out.println("MeterInputCheck: all checks passed");
    }

    private static short[] tone(int hz) {
        short[] result = new short[960];
        for (int i = 0; i < result.length; i++) {
            result[i] = (short) (20000 * Math.sin(2 * Math.PI * hz * i / 48_000));
        }
        return result;
    }

    private static void feed(UUID id, short[] pcm, int count) {
        for (int i = 0; i < count; i++) AlbumMeterInput.accept(id, pcm);
    }

    private static int peak(UUID id) {
        var sample = AlbumMeterInput.sample(id);
        check(sample != null, "sample ready");
        int peak = 0;
        for (int i = 1; i < AlbumMeterInput.BANDS; i++) if (sample.level(i) > sample.level(peak)) peak = i;
        return peak;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
