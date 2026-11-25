package dev.geduu.albumjukebox.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.geduu.albumjukebox.AlbumBlockEntity;
import dev.geduu.albumjukebox.AlbumJukebox;
import dev.geduu.albumjukebox.AlbumMeterInput;
import dev.geduu.albumjukebox.AlbumVisualState;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

final class AlbumJukeboxRenderer implements BlockEntityRenderer<AlbumBlockEntity, AlbumJukeboxRenderer.State> {
    private static final RenderType SURFACE =
        RenderTypes.entityCutoutCull(AlbumJukebox.id("textures/block/turntable_parts.png"));
    private static final float DISC_RADIUS = 4.58F;
    private static final float LABEL_RADIUS = 1.68F;
    private static final float HOLE_RADIUS = 0.39F;
    private static final float DISC_THICKNESS = 0.17F;
    private static final int DISC_SIDES = 48;
    private static final DiscPoint[] RIM = new DiscPoint[DISC_SIDES];
    private static final Box ARM = new Box(-0.30F, -0.15F, -6.95F, 0.30F, 0.31F, 0.38F, 0xff92938b);
    private static final Box COUNTERWEIGHT = new Box(-0.48F, -0.24F, -0.18F, 0.48F, 0.44F, 0.68F, 0xff565955);
    private static final Box CARTRIDGE = new Box(-0.45F, -0.31F, -7.73F, 0.45F, 0.27F, -6.95F, 0xff43484b);
    private static final Box NEEDLE = new Box(-0.09F, -0.46F, DeckGeometry.NEEDLE_FRONT,
        0.09F, -0.31F, DeckGeometry.NEEDLE_BACK, 0xffd3cab0);
    private final Map<AlbumBlockEntity, Deck> decks = new WeakHashMap<>();

    private static final class Deck {
        final DeckMotion motion = new DeckMotion();
        final FrontDisplay.Spectrum spectrum = new FrontDisplay.Spectrum();
    }

    static {
        for (int i = 0; i < DISC_SIDES; i++) {
            double angle = 2 * Math.PI * i / DISC_SIDES;
            RIM[i] = new DiscPoint((float) Math.cos(angle), (float) Math.sin(angle));
        }
    }

    static final class State extends BlockEntityRenderState {
        float facingAngle;
        float discAngle;
        float armAngle;
        float armLift;
        boolean hasDisc;
        int body;
        int label;
        long vfdLit;
        long vfdPeak;
        int trackNumber;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(AlbumBlockEntity block, State state, float partialTick, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(block, state, partialTick, camera, crumbling);
        state.lightCoords = LevelRenderer.getLightCoords(block.getLevel(), block.getBlockPos().above());
        long now = System.nanoTime();
        var deck = decks.computeIfAbsent(block, ignored -> new Deck());
        var motion = deck.motion;
        var visual = block.visualState();
        motion.update(visual, block.visualReceivedAt(), now);
        var disc = motion.display();
        state.facingAngle = 180F - block.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING).toYRot();
        state.discAngle = motion.angle();
        state.armAngle = motion.swingAngle();
        state.armLift = motion.lift();
        state.hasDisc = disc.hasDisc();
        state.body = 0xff000000 | disc.bodyColor();
        state.label = 0xff000000 | disc.labelColor();
        state.vfdLit = state.vfdPeak = 0;
        state.trackNumber = 0;

        var facing = block.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
        Vec3 center = block.getBlockPos().getCenter();
        double dx = camera.x - center.x;
        double dz = camera.z - center.z;
        double distanceSquared = camera.distanceToSqr(center);
        boolean frontVisible = dx * facing.getStepX() + dz * facing.getStepZ() > 0;
        boolean active = visual.arm() == AlbumVisualState.Arm.PLAY && visual.channel() != null
            && frontVisible && distanceSquared <= FrontDisplay.RANGE_SQUARED;
        var spectrum = deck.spectrum;
        if (spectrum.channel() != null && (!active || !spectrum.channel().equals(visual.channel()))) {
            AlbumMeterInput.unwatchWorld(spectrum.channel());
            spectrum.reset();
        }
        if (active) {
            // Digits require only the existing visual snapshot, even when no PCM is heard.
            state.trackNumber = visual.trackNumber();
            if (AlbumMeterInput.watchWorld(visual.channel(), distanceSquared, now)) {
                spectrum.update(visual.channel(), now);
                state.vfdLit = spectrum.lit();
                state.vfdPeak = spectrum.peak();
            } else {
                spectrum.reset();
            }
        }
    }

    @Override
    public void submit(State state, PoseStack poses, SubmitNodeCollector queue, CameraRenderState camera) {
        poses.pushPose();
        poses.translate(0.5F, 0F, 0.5F);
        poses.mulPose(Axis.YP.rotationDegrees(state.facingAngle));
        poses.translate(-0.5F, 0F, -0.5F);
        if (state.hasDisc) {
            drawDisc(state, poses, queue);
        }
        drawArm(state, poses, queue);
        FrontDisplay.draw(poses, queue, state.vfdLit, state.vfdPeak, state.trackNumber);
        poses.popPose();
    }

    private static void drawDisc(State state, PoseStack poses, SubmitNodeCollector queue) {
        poses.pushPose();
        poses.translate(DeckGeometry.DISC_X / 16F, 9.02F / 16F, DeckGeometry.DISC_Z / 16F);
        poses.mulPose(Axis.YN.rotationDegrees(state.discAngle));
        poses.scale(1F / 16F, 1F / 16F, 1F / 16F);
        int body = state.body;
        int label = state.label;
        int light = state.lightCoords;
        queue.submitCustomGeometry(poses, SURFACE, (pose, output) -> {
            for (int i = 0; i < DISC_SIDES; i++) {
                DiscPoint a = RIM[i];
                DiscPoint b = RIM[(i + 1) % DISC_SIDES];
                discTop(pose, output, light, a, b, HOLE_RADIUS, LABEL_RADIUS, label, 1);
                discTop(pose, output, light, a, b, LABEL_RADIUS, DISC_RADIUS, body, 0);
                discWall(pose, output, light, a, b, DISC_RADIUS, body, 0, 1);
                discWall(pose, output, light, b, a, HOLE_RADIUS, label, 1, -1);
                a.emit(pose, output, light, HOLE_RADIUS, 0F, body, 0, 0, -1, 0);
                a.emit(pose, output, light, DISC_RADIUS, 0F, body, 0, 0, -1, 0);
                b.emit(pose, output, light, DISC_RADIUS, 0F, body, 0, 0, -1, 0);
                b.emit(pose, output, light, HOLE_RADIUS, 0F, body, 0, 0, -1, 0);
            }
        });
        poses.popPose();
    }

    private static void discTop(PoseStack.Pose pose, VertexConsumer output, int light, DiscPoint a, DiscPoint b,
                                float inner, float outer, int color, int atlasColumn) {
        a.emit(pose, output, light, inner, DISC_THICKNESS, color, atlasColumn, 0, 1, 0);
        b.emit(pose, output, light, inner, DISC_THICKNESS, color, atlasColumn, 0, 1, 0);
        b.emit(pose, output, light, outer, DISC_THICKNESS, color, atlasColumn, 0, 1, 0);
        a.emit(pose, output, light, outer, DISC_THICKNESS, color, atlasColumn, 0, 1, 0);
    }

    private static void discWall(PoseStack.Pose pose, VertexConsumer output, int light, DiscPoint a, DiscPoint b,
                                 float radius, int color, int atlasColumn, int normal) {
        a.emit(pose, output, light, radius, 0F, color, atlasColumn, a.x * normal, 0, a.z * normal);
        a.emit(pose, output, light, radius, DISC_THICKNESS, color, atlasColumn, a.x * normal, 0, a.z * normal);
        b.emit(pose, output, light, radius, DISC_THICKNESS, color, atlasColumn, b.x * normal, 0, b.z * normal);
        b.emit(pose, output, light, radius, 0F, color, atlasColumn, b.x * normal, 0, b.z * normal);
    }

    private static void drawArm(State state, PoseStack poses, SubmitNodeCollector queue) {
        poses.pushPose();
        // The front is north. On its right, the clockwise groove travels away from the pivot.
        poses.translate(DeckGeometry.PIVOT_X / 16F, 9.65F / 16F, DeckGeometry.PIVOT_Z / 16F);
        poses.mulPose(Axis.YN.rotationDegrees(state.armAngle));
        poses.mulPose(Axis.XP.rotationDegrees(10F * state.armLift));
        poses.scale(1F / 16F, 1F / 16F, 1F / 16F);
        int light = state.lightCoords;
        queue.submitCustomGeometry(poses, SURFACE, (pose, output) -> {
            ARM.draw(pose, output, light);
            COUNTERWEIGHT.draw(pose, output, light);
            CARTRIDGE.draw(pose, output, light);
            NEEDLE.draw(pose, output, light);
        });
        poses.popPose();
    }

    private static void vertex(VertexConsumer output, PoseStack.Pose pose, float x, float y, float z,
                               float u, float v, int color, int light, float normalX, float normalY, float normalZ) {
        output.addVertex(pose, x, y, z).setColor(color).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normalX, normalY, normalZ);
    }

    private record DiscPoint(float x, float z) {
        void emit(PoseStack.Pose pose, VertexConsumer output, int light, float radius, float height,
                  int color, int atlasColumn, float normalX, float normalY, float normalZ) {
            float px = x * radius;
            float pz = z * radius;
            // Half-texel inset keeps the rim within its own tile.
            float scale = 63F / (128F * DISC_RADIUS);
            float u = (atlasColumn + 0.5F + px * scale) / 4F;
            float v = 0.5F + pz * scale;
            vertex(output, pose, px, height, pz, u, v, color, light, normalX, normalY, normalZ);
        }
    }

    private record Box(float left, float bottom, float front, float right, float top, float back, int color) {
        private static final int[][] FACES = {
            {0, 4, 5, 1, -1, 0, 0}, {3, 2, 6, 7, 1, 0, 0},
            {0, 3, 7, 4, 0, -1, 0}, {1, 5, 6, 2, 0, 1, 0},
            {0, 1, 2, 3, 0, 0, -1}, {4, 7, 6, 5, 0, 0, 1}
        };

        void draw(PoseStack.Pose pose, VertexConsumer output, int light) {
            for (int[] face : FACES) {
                for (int i = 0; i < 4; i++) {
                    int corner = face[i];
                    boolean xHigh = (corner & 3) >= 2;
                    boolean yHigh = ((corner + 1) & 2) != 0;
                    boolean zHigh = corner >= 4;
                    // All four points remain in the solid-white atlas tile, but
                    // span distinct UVs so shader-pack tangent generation is valid.
                    float u = i < 2 ? 0.88F : 0.92F;
                    float v = i == 0 || i == 3 ? 0.52F : 0.48F;
                    vertex(output, pose, xHigh ? right : left, yHigh ? top : bottom, zHigh ? back : front,
                        u, v, color, light, face[4], face[5], face[6]);
                }
            }
        }
    }
}
