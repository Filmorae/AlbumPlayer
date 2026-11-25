package dev.geduu.albumjukebox.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.geduu.albumjukebox.AlbumJukebox;
import dev.geduu.albumjukebox.AlbumMeterInput;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Lit segments only. The glass and unlit segments are part of the baked block model. */
final class FrontDisplay {
    static final int COLUMNS = 12;
    static final int ROWS = 8;
    static final double RANGE_SQUARED = 16 * 16;
    private static final RenderType EMISSIVE = RenderTypes.entityTranslucentEmissive(
        AlbumJukebox.id("textures/entity/vfd_white.png"), false);
    private static final int LIT = 0xffe0efed;
    private static final int PEAK = 0xfff5fffe;
    private static final int FULL_BRIGHT = 0x00f000f0;
    // The north face is 16 x 7.5 model units; its full UV range is 128 x 128 texels.
    private static final float FRONT_Z = -0.0015F;
    private static final float PANEL_TEXELS = 128F;
    private static final int SPECTRUM_X = 18;
    private static final int SPECTRUM_Y = 94; // baseline, doubled from the original 64px layout
    private static final int DIGIT_Y = 48;
    private static final int[] DIGIT_X = {97, 105};
    // A B C D E F G, beginning at the top and going clockwise.
    private static final int[] DIGIT_MASKS = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};
    private static final int[][] SEGMENTS = {
        {1, 0, 5, 3}, {4, 4, 6, 10}, {4, 14, 6, 20}, {1, 21, 5, 24},
        {0, 14, 2, 20}, {0, 4, 2, 10}, {1, 11, 5, 13}
    };

    private FrontDisplay() {}

    static final class Spectrum {
        private final MeterEnvelope envelope = new MeterEnvelope(COLUMNS, 3.2F, 2.5F, 240_000_000L);
        private UUID channel;
        private long lastRender;
        private long lit;
        private long peak;

        void update(UUID nextChannel, long now) {
            if (!Objects.equals(channel, nextChannel) || now - lastRender > 300_000_000L) {
                reset();
                channel = nextChannel;
                lastRender = now;
            }
            float delta = Math.clamp((now - lastRender) / 1_000_000_000F, 0F, 0.1F);
            lastRender = now;
            var sample = AlbumMeterInput.sample(channel);
            boolean fresh = sample != null && now - sample.time() < 140_000_000L;
            lit = 0;
            peak = 0;
            boolean peakHold = AlbumClientSession.peakHold();
            for (int column = 0; column < COLUMNS; column++) {
                float amplitude = fresh ? Math.max(sample.level(column * 2), sample.level(column * 2 + 1)) : 0F;
                float target = amplitude <= 0 ? 0 : (float) Math.clamp(1 + 20 * Math.log10(amplitude) / 60, 0, 1);
                envelope.update(column, target, delta, now, peakHold);
                int count = envelope.lit(column, ROWS);
                int top = envelope.peak(column, ROWS);
                lit |= (long) count << (column * 4);
                peak |= (long) top << (column * 4);
            }
        }

        void reset() {
            envelope.reset();
            channel = null;
            lastRender = 0;
            lit = peak = 0;
        }

        long lit() { return lit; }
        long peak() { return peak; }
        UUID channel() { return channel; }
    }

    static void draw(PoseStack poses, SubmitNodeCollector queue, long lit, long peak, int trackNumber) {
        if ((lit | peak) == 0 && trackNumber <= 0) return;
        queue.submitCustomGeometry(poses, EMISSIVE, (pose, output) -> {
            for (int column = 0; column < COLUMNS; column++) {
                int count = (int) ((lit >>> (column * 4)) & 15);
                int top = (int) ((peak >>> (column * 4)) & 15);
                int x = SPECTRUM_X + column * 6;
                for (int row = 0; row < ROWS; row++) {
                    if (row < count || row + 1 == top) {
                        int y = SPECTRUM_Y - 2 - row * 6;
                        quad(pose, output, x, y, x + 4, y + 4, row + 1 == top ? PEAK : LIT);
                    }
                }
            }
            if (trackNumber >= 1 && trackNumber <= 27) {
                digit(pose, output, DIGIT_X[0], trackNumber / 10);
                digit(pose, output, DIGIT_X[1], trackNumber % 10);
            }
        });
    }

    private static void digit(PoseStack.Pose pose, VertexConsumer output, int x, int digit) {
        int mask = DIGIT_MASKS[digit];
        for (int i = 0; i < SEGMENTS.length; i++) {
            if ((mask & (1 << i)) != 0) {
                int[] box = SEGMENTS[i];
                quad(pose, output, x + box[0], DIGIT_Y + box[1], x + box[2], DIGIT_Y + box[3], LIT);
            }
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer output, int left, int top, int right, int bottom, int color) {
        // North-facing texture U is mirrored relative to world X.
        float x0 = 1F - right / PANEL_TEXELS;
        float x1 = 1F - left / PANEL_TEXELS;
        float y0 = (PANEL_TEXELS - bottom) * (7.5F / 16F / PANEL_TEXELS);
        float y1 = (PANEL_TEXELS - top) * (7.5F / 16F / PANEL_TEXELS);
        // Even with a solid-white texture the UVs must span a nonzero area.
        // Iris derives at_tangent from the triangle's UV Jacobian; constant UVs
        // produce a zero tangent which Photon normalizes when building its TBN.
        vertex(pose, output, x0, y0, 0.875F, 0.875F, color);
        vertex(pose, output, x0, y1, 0.875F, 0.125F, color);
        vertex(pose, output, x1, y1, 0.125F, 0.125F, color);
        vertex(pose, output, x1, y0, 0.125F, 0.875F, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer output, float x, float y, float u, float v, int color) {
        output.addVertex(pose, x, y, FRONT_Z).setColor(color).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0, 0, -1);
    }
}
