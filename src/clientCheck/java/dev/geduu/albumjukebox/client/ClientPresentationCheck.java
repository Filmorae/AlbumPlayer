package dev.geduu.albumjukebox.client;

/** Dependency-free regression check of client-only UI preferences and both meter envelopes. */
public final class ClientPresentationCheck {
    public static void main(String[] args) {
        AlbumClientSession.reset();
        check(AlbumClientSession.peakHold() && !AlbumClientSession.showList() && !AlbumClientSession.showVfd(), "defaults");
        AlbumClientSession.rememberView(false, true);
        AlbumClientSession.togglePeakHold();
        check(!AlbumClientSession.peakHold() && AlbumClientSession.showVfd() && !AlbumClientSession.showList(), "meter preference");
        AlbumClientSession.rememberView(true, true);
        check(AlbumClientSession.showList() && AlbumClientSession.showVfd(), "playlist keeps underlying meter view");
        AlbumClientSession.rememberView(false, true);
        check(!AlbumClientSession.showList() && AlbumClientSession.showVfd() && !AlbumClientSession.peakHold(), "restore meter");
        AlbumClientSession.reset();
        check(AlbumClientSession.peakHold() && !AlbumClientSession.showVfd() && !AlbumClientSession.showList(), "disconnect restores defaults");
        meter(24, 18, 2.8F, 1.65F, 360_000_000L);
        meter(12, 8, 3.2F, 2.5F, 240_000_000L);
        System.out.println("ClientPresentationCheck: all checks passed");
    }

    private static void meter(int bands, int rows, float release, float peakRelease, long hold) {
        MeterEnvelope env = new MeterEnvelope(bands, release, peakRelease, hold);
        long t = 1_000_000_000L;
        env.update(0, 1, 0.05F, t, true);
        env.update(0, 0.1F, 0.1F, t + 100_000_000L, true);
        int bar = env.lit(0, rows);
        check(bar < rows && env.peak(0, rows) == rows, "peak stays while bar falls");
        env.update(0, 0.1F, 0, t + 100_000_000L, false);
        check(env.peak(0, rows) == 0 && env.lit(0, rows) == bar, "disabling clears peak but not bar");
        env.update(0, 0.1F, .1F, t + 200_000_000L, true);
        check(env.peak(0, rows) < rows, "re-enable does not restore stale peak");
        env.update(0, 1, .05F, t + 230_000_000L, true);
        env.update(0, 0, .1F, t + hold + 240_000_000L, true);
        check(env.peak(0, rows) < rows, "peak releases when hold expires");
        for (int b = 1; b < bands; b++) check(env.peak(b, rows) == 0 && env.lit(b, rows) == 0, "columns isolated");
        env.reset();
        check(env.peak(0, rows) == 0 && env.lit(0, rows) == 0, "reset clears display");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
