package dev.geduu.albumjukebox.client;

import dev.geduu.albumjukebox.AlbumMeterInput;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class AlbumVfd {
    private static final int BANDS = AlbumMeterInput.BANDS;
    private static final int ROWS = 18;
    private static final int LEFT = 20;
    private static final int TOP = 38;
    private static final int COLUMN_STEP = 6;
    private static final int ROW_STEP = 4;
    private static final int BOTTOM = TOP + ROWS * ROW_STEP;
    private static final int LIT = 0xe0bef8f5;
    private static final int PEAK = 0xffeeffff;
    private static final int UNLIT = 0x49406e6c;
    private static final int[] TICK_HZ = {63, 250, 1000, 4000, 12000};
    private static final String[] TICK_LABELS = {"63", "250", "1k", "4k", "12k"};

    private final MeterEnvelope envelope = new MeterEnvelope(BANDS, 2.8F, 1.65F, 360_000_000L);
    private UUID channel;
    private long lastRender;

    void draw(GuiGraphicsExtractor graphics, Font font, UUID nextChannel) {
        AlbumMeterInput.watchGui(nextChannel);
        long now = System.nanoTime();
        float delta = Math.clamp((now - lastRender) / 1_000_000_000F, 0F, 0.10F);
        lastRender = now;
        if (!Objects.equals(channel, nextChannel)) {
            channel = nextChannel;
            if (nextChannel != null) {
                reset();
            }
        }
        var sample = AlbumMeterInput.sample(nextChannel);
        boolean fresh = sample != null && now - sample.time() < 140_000_000L;

        graphics.fill(LEFT - 7, TOP - 7, LEFT + BANDS * COLUMN_STEP + 6, BOTTOM + 18, 0xac061112);
        graphics.outline(LEFT - 7, TOP - 7, BANDS * COLUMN_STEP + 13, BOTTOM - TOP + 25, 0x60466461);
        boolean peakHold = AlbumClientSession.peakHold();
        for (int band = 0; band < BANDS; band++) {
            float target = fresh ? level(sample.level(band)) : 0;
            envelope.update(band, target, delta, now, peakHold);

            int x = LEFT + band * COLUMN_STEP;
            int lit = envelope.lit(band, ROWS);
            int peak = envelope.peak(band, ROWS) - 1;
            for (int row = 0; row < ROWS; row++) {
                int y = BOTTOM - (row + 1) * ROW_STEP;
                if (row < lit || row == peak) {
                    graphics.fill(x - 1, y - 1, x + 5, y + 4, 0x1855d9d2);
                }
                graphics.fill(x, y, x + 4, y + 3, row == peak ? PEAK : row < lit ? LIT : UNLIT);
            }
        }
        for (int i = 0; i < TICK_HZ.length; i++) {
            int x = LEFT + AlbumMeterInput.bandForFrequency(TICK_HZ[i]) * COLUMN_STEP + 2;
            graphics.centeredText(font, TICK_LABELS[i], x, BOTTOM + 4, 0xff9eafad);
        }
    }

    void suspend() {
        AlbumMeterInput.watchGui(null);
        channel = null;
        reset();
    }

    private void reset() {
        envelope.reset();
    }

    private static float level(float amplitude) {
        return amplitude <= 0 ? 0 : (float) Math.clamp(1 + 20 * Math.log10(amplitude) / 60, 0, 1);
    }
}
