package dev.geduu.albumjukebox.client;

import dev.geduu.albumjukebox.AlbumAction;
import dev.geduu.albumjukebox.AlbumJukebox;
import dev.geduu.albumjukebox.AlbumMenu;
import dev.geduu.albumjukebox.PlaybackMode;
import dev.geduu.albumjukebox.PlaybackStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

final class AlbumScreen extends AbstractContainerScreen<AlbumMenu> {
    private static final Identifier CHEST = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final Identifier ICONS = AlbumJukebox.id("textures/gui/controls.png");
    private static final int WIDTH = 182;
    private static final int HEIGHT = 246;
    private static final int PEAK_BUTTON_X = WIDTH - 19;
    private static final int PEAK_BUTTON_Y = 16;
    private static final int ICON_ATLAS_WIDTH = 160;
    private static final int TOP = 164;
    private static final int BOTTOM = 170;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 8;
    private static final int WHITE = 0xfff0f3f2;
    private static final int MUTED = 0xff9ca7aa;
    private static final int INACTIVE = 0xff596365;
    private static final int GREEN = 0xff8bda78;

    private final List<Integer> tracks = new ArrayList<>(AlbumMenu.DISC_SLOTS);
    private float zoom;
    private float originX;
    private float originY;
    private boolean showList;
    private boolean showVfd;
    private final AlbumVfd meter = new AlbumVfd();
    private int firstRow;
    private boolean scrubbing;
    private boolean scrollDragging;
    private double scrollGrip;
    private int desiredFrame;
    private int seekRevision;
    private int previousFrame = -1;
    private int previousRevision = -1;
    private PlaybackStatus previousStatus;
    private long positionTime;

    AlbumScreen(AlbumMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 168);
        inventoryLabelY = 74;
        if (!menu.storage) {
            showList = AlbumClientSession.showList();
            showVfd = AlbumClientSession.showVfd();
        }
    }

    @Override
    protected void init() {
        super.init();
        zoom = Math.max(0.1F, Math.min(1F, Math.min((width - 12F) / WIDTH, (height - 12F) / HEIGHT)));
        originX = (width - WIDTH * zoom) / 2F;
        originY = (height - HEIGHT * zoom) / 2F;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (menu.storage) {
            super.extractBackground(graphics, mouseX, mouseY, delta);
            graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, leftPos, topPos, 0, 0, 176, 71, 256, 256);
            graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, leftPos, topPos + 71, 0, 126, 176, 96, 256, 256);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (menu.storage) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            return;
        }
        refreshTracks();
        double x = localX(mouseX);
        double y = localY(mouseY);
        graphics.pose().pushMatrix();
        graphics.pose().translate(originX, originY).scale(zoom);
        panel(graphics, 0, TOP);
        panel(graphics, BOTTOM, HEIGHT);
        if (showList) {
            list(graphics, x, y, mouseX, mouseY);
        } else if (showVfd) {
            drawVfd(graphics, x, y);
        } else {
            disc(graphics);
        }
        controls(graphics, x, y);
        graphics.pose().popMatrix();
        controlsTooltip(graphics, x, y, mouseX, mouseY);
    }

    private static void panel(GuiGraphicsExtractor graphics, int top, int bottom) {
        graphics.fill(0, top, WIDTH, bottom, 0xdc101416);
        graphics.outline(0, top, WIDTH, bottom - top, 0x8594a09f);
    }

    private void disc(GuiGraphicsExtractor graphics) {
        ItemStack disc = menu.disc(menu.selected());
        if (disc.isEmpty()) {
            icon(graphics, 7, 75, 57, INACTIVE, 32);
            graphics.centeredText(font, Component.translatable(tracks.isEmpty()
                ? "album_jukebox.empty" : "album_jukebox.ready"), WIDTH / 2, 109, MUTED);
            return;
        }

        graphics.pose().pushMatrix();
        graphics.pose().translate(WIDTH / 2F - 56, 20).scale(7F);
        graphics.fakeItem(disc, 0, 0);
        graphics.pose().popMatrix();
        graphics.centeredText(font, truncate(title(disc), WIDTH - 24), WIDTH / 2, 135, WHITE);
    }

    private void drawVfd(GuiGraphicsExtractor graphics, double x, double y) {
        boolean held = AlbumClientSession.peakHold();
        button(graphics, held ? 8 : 9, PEAK_BUTTON_X, PEAK_BUTTON_Y, x, y, true, held ? GREEN : MUTED);
        meter.draw(graphics, font, menu.status() == PlaybackStatus.PLAYING ? menu.audioChannel() : null);
        var disc = menu.disc(menu.selected());
        if (!disc.isEmpty()) {
            graphics.centeredText(font, truncate(title(disc), WIDTH - 24), WIDTH / 2, 135, WHITE);
        }
    }

    @Override
    public void removed() {
        if (showVfd) {
            meter.suspend();
        }
        super.removed();
    }

    private void list(GuiGraphicsExtractor graphics, double x, double y, int mouseX, int mouseY) {
        if (tracks.isEmpty()) {
            graphics.centeredText(font, Component.translatable("album_jukebox.empty"), WIDTH / 2, TOP / 2 - 4, MUTED);
            return;
        }
        for (int row = firstRow; row < Math.min(tracks.size(), firstRow + VISIBLE_ROWS); row++) {
            int slot = tracks.get(row);
            int top = 9 + (row - firstRow) * ROW_HEIGHT;
            boolean hover = within(x, y, 6, top, WIDTH - 20, ROW_HEIGHT);
            if (slot == menu.selected()) {
                graphics.fill(5, top, WIDTH - 13, top + ROW_HEIGHT, 0x85435d43);
                graphics.fill(5, top, 7, top + ROW_HEIGHT, GREEN);
            } else if (hover) {
                graphics.fill(5, top, WIDTH - 13, top + ROW_HEIGHT, 0x405d6667);
            }
            String name = title(menu.disc(slot));
            graphics.text(font, Integer.toString(row + 1), 13, top + 5, slot == menu.selected() ? GREEN : MUTED, false);
            graphics.text(font, truncate(name, WIDTH - 51), 33, top + 5, WHITE, false);
            if (hover && font.width(name) > WIDTH - 51) {
                graphics.setTooltipForNextFrame(Component.literal(name), mouseX, mouseY);
            }
        }
        if (maxScroll() > 0) {
            int top = 9 + (int) Math.round(firstRow * (double) (144 - thumbHeight()) / maxScroll());
            graphics.fill(WIDTH - 9, 9, WIDTH - 6, 153, 0xff343d40);
            graphics.fill(WIDTH - 9, top, WIDTH - 6, top + thumbHeight(), MUTED);
        }
    }

    private void controls(GuiGraphicsExtractor graphics, double x, double y) {
        int length = menu.duration();
        int position = scrubbing && seekRevision == menu.revision() ? desiredFrame : displayedPosition();
        int knob = 8 + (length == 0 ? 0 : Math.round((WIDTH - 16) * (Math.min(position, length) / (float) length)));
        graphics.fill(8, 183, WIDTH - 8, 186, 0xff4b5558);
        if (length > 0) {
            graphics.fill(8, 183, knob, 186, WHITE);
            graphics.fill(knob - 1, 180, knob + 2, 189, scrubbing ? GREEN : WHITE);
        }
        graphics.text(font, time(position), 8, 191, WHITE, false);
        String total = length == 0 ? "--:--" : time(length);
        graphics.text(font, total, WIDTH - 8 - font.width(total), 191, MUTED, false);

        boolean hasTracks = !tracks.isEmpty();
        PlaybackStatus status = menu.status();
        button(graphics, 0, 66, 219, x, y, canMove(-1), WHITE);
        button(graphics, status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING ? 2 : 1,
            WIDTH / 2, 219, x, y, hasTracks, WHITE);
        button(graphics, 3, 116, 219, x, y, canMove(1), WHITE);
        button(graphics, 4, 19, 230, x, y, true, showList ? GREEN : WHITE);
        button(graphics, menu.mode() == PlaybackMode.ONE ? 6 : 5, WIDTH - 19, 230, x, y, true,
            menu.mode() == PlaybackMode.ONCE ? WHITE : GREEN);
        String key = switch (status) {
            case LOADING -> "loading";
            case ERROR -> "error";
            case UNAVAILABLE -> "unavailable";
            default -> null;
        };
        if (key != null) {
            graphics.centeredText(font, Component.translatable("album_jukebox." + key), WIDTH / 2, 192, MUTED);
        }
    }

    private void controlsTooltip(GuiGraphicsExtractor graphics, double x, double y, int mouseX, int mouseY) {
        String key = null;
        if (isPeakButton(x, y)) {
            key = AlbumClientSession.peakHold() ? "peak_hold.disable" : "peak_hold.enable";
        } else if (isButton(x, y, 19, 230)) {
            key = showList ? "hide_list" : "show_list";
        } else if (isButton(x, y, WIDTH - 19, 230)) {
            key = "mode." + menu.mode().name().toLowerCase(Locale.ROOT);
        } else if (isButton(x, y, WIDTH / 2, 219)) {
            key = switch (menu.status()) {
                case PLAYING -> "pause";
                case LOADING -> "cancel";
                default -> "play";
            };
        } else if (isButton(x, y, 66, 219)) {
            key = "previous";
        } else if (isButton(x, y, 116, 219)) {
            key = "next";
        }
        if (key == null && !showList && canToggleVfd(x, y)) {
            key = showVfd ? "show_disc" : "show_vfd";
            if (showVfd) {
                mouseY = Math.round(originY + (TOP - 9) * zoom) + 12;
            }
        }
        if (key != null) {
            graphics.setTooltipForNextFrame(Component.translatable("album_jukebox." + key), mouseX, mouseY);
        }
    }

    private static void button(GuiGraphicsExtractor graphics, int icon, int cx, int cy, double x, double y,
                               boolean enabled, int color) {
        if (enabled && isButton(x, y, cx, cy)) {
            graphics.fill(cx - 11, cy - 11, cx + 12, cy + 12, 0x404b595c);
        }
        icon(graphics, icon, cx - 8, cy - 8, enabled ? color : INACTIVE, 16);
    }

    private static void icon(GuiGraphicsExtractor graphics, int index, int x, int y, int tint, int size) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y).scale(size / 16F);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICONS, 0, 0, index * 16, 0, 16, 16, ICON_ATLAS_WIDTH, 16, tint);
        graphics.pose().popMatrix();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (menu.storage) {
            return super.mouseClicked(event, doubleClick);
        }
        if (event.button() != 0) {
            return false;
        }
        refreshTracks();
        double x = localX(event.x());
        double y = localY(event.y());
        // The peak button overlaps the broad click-to-toggle VFD region; handle it first.
        if (isPeakButton(x, y)) {
            AlbumClientSession.togglePeakHold();
        } else if (within(x, y, 6, 176, WIDTH - 12, 15) && canSeek()) {
            scrubbing = true;
            seekRevision = menu.revision();
            previewSeek(x);
        } else if (isButton(x, y, 19, 230)) {
            showList = !showList;
            AlbumClientSession.rememberView(showList, showVfd);
            if (showList) {
                meter.suspend();
                int index = tracks.indexOf(menu.selected());
                if (index >= 0 && (index < firstRow || index >= firstRow + VISIBLE_ROWS)) {
                    firstRow = Math.min(index, maxScroll());
                }
            }
        } else if (isButton(x, y, WIDTH - 19, 230)) {
            send(AlbumAction.MODE, 0, menu.revision());
        } else if (isButton(x, y, WIDTH / 2, 219) && !tracks.isEmpty()) {
            send(AlbumAction.TOGGLE, 0, menu.revision());
        } else if (isButton(x, y, 66, 219) && canMove(-1)) {
            send(AlbumAction.PREVIOUS, 0, menu.revision());
        } else if (isButton(x, y, 116, 219) && canMove(1)) {
            send(AlbumAction.NEXT, 0, menu.revision());
        } else if (!showList && canToggleVfd(x, y)) {
            showVfd = !showVfd;
            AlbumClientSession.rememberView(showList, showVfd);
            if (!showVfd) {
                meter.suspend();
            }
        } else if (showList && within(x, y, WIDTH - 13, 9, 13, 144) && maxScroll() > 0) {
            double thumbTop = 9 + firstRow * (144 - thumbHeight()) / (double) maxScroll();
            scrollGrip = y >= thumbTop && y < thumbTop + thumbHeight() ? y - thumbTop : thumbHeight() / 2D;
            scrollDragging = true;
            dragScroll(y);
        } else if (showList && within(x, y, 6, 9, WIDTH - 20, 144)) {
            int row = firstRow + (int) ((y - 9) / ROW_HEIGHT);
            if (row < tracks.size()) {
                send(AlbumAction.SELECT, tracks.get(row), menu.revision());
            }
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (scrubbing) {
            previewSeek(localX(event.x()));
            return true;
        }
        if (scrollDragging) {
            dragScroll(localY(event.y()));
            return true;
        }
        return menu.storage && super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (scrubbing && event.button() == 0) {
            scrubbing = false;
            if (canSeek() && seekRevision == menu.revision()) {
                previewSeek(localX(event.x()));
                send(AlbumAction.SEEK, desiredFrame, seekRevision);
            }
            return true;
        }
        if (scrollDragging && event.button() == 0) {
            scrollDragging = false;
            return true;
        }
        return menu.storage && super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (showList && within(localX(mouseX), localY(mouseY), 0, 0, WIDTH, TOP)) {
            firstRow = Math.clamp(firstRow - (int) Math.signum(scrollY) * 2, 0, maxScroll());
            return true;
        }
        return menu.storage && super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isPeakButton(double x, double y) {
        return !showList && showVfd && isButton(x, y, PEAK_BUTTON_X, PEAK_BUTTON_Y);
    }

    private boolean canToggleVfd(double x, double y) {
        if (showVfd) {
            return within(x, y, 8, 15, WIDTH - 16, 112);
        }
        return !menu.disc(menu.selected()).isEmpty() && within(x, y, WIDTH / 2 - 56, 20, 112, 112);
    }

    private void refreshTracks() {
        tracks.clear();
        for (int slot = 0; slot < AlbumMenu.DISC_SLOTS; slot++) {
            if (!menu.disc(slot).isEmpty()) {
                tracks.add(slot);
            }
        }
        firstRow = Math.min(firstRow, maxScroll());
    }

    private boolean canSeek() {
        return menu.duration() > 0 && (menu.status() == PlaybackStatus.PLAYING || menu.status() == PlaybackStatus.PAUSED);
    }

    private void previewSeek(double x) {
        desiredFrame = (int) Math.round(menu.duration() * Math.clamp((x - 8) / (WIDTH - 16), 0, 1));
    }

    private int displayedPosition() {
        int current = menu.position();
        long now = System.nanoTime();
        if (current != previousFrame || menu.status() != previousStatus || menu.revision() != previousRevision) {
            previousFrame = current;
            previousStatus = menu.status();
            previousRevision = menu.revision();
            positionTime = now;
        }
        int predicted = previousStatus == PlaybackStatus.PLAYING ? (int) Math.min(5, (now - positionTime) / (AlbumMenu.FRAME_MILLIS * 1_000_000L)) : 0;
        return Math.min(current + predicted, menu.duration());
    }

    private boolean canMove(int direction) {
        if (tracks.isEmpty()) {
            return false;
        }
        int selected = menu.selected();
        if (selected < 0) {
            return true;
        }
        if (menu.mode() == PlaybackMode.ALL) {
            return tracks.size() > 1;
        }
        return direction < 0 ? tracks.getFirst() < selected : tracks.getLast() > selected;
    }

    private int maxScroll() {
        return Math.max(0, tracks.size() - VISIBLE_ROWS);
    }

    private int thumbHeight() {
        return Math.max(16, 144 * VISIBLE_ROWS / tracks.size());
    }

    private void dragScroll(double y) {
        if (maxScroll() > 0) {
            firstRow = (int) Math.round(Math.clamp((y - 9 - scrollGrip) / (144 - thumbHeight()), 0, 1) * maxScroll());
        }
    }

    private void send(int action, int value, int revision) {
        ClientPlayNetworking.send(new AlbumAction(menu.containerId, action, value, revision));
    }

    private double localX(double x) { return (x - originX) / zoom; }
    private double localY(double y) { return (y - originY) / zoom; }

    private static boolean within(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static boolean isButton(double x, double y, int cx, int cy) {
        return within(x, y, cx - 11, cy - 11, 23, 23);
    }

    private static String title(ItemStack disc) {
        var custom = disc.get(DataComponents.CUSTOM_NAME);
        if (custom != null) {
            return custom.getString();
        }
        var lore = disc.get(DataComponents.LORE);
        if (lore != null && !lore.lines().isEmpty()) {
            return lore.lines().getFirst().getString();
        }
        return disc.getHoverName().getString();
    }

    private String truncate(String text, int maxWidth) {
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
    }

    private static String time(int frames) {
        int seconds = frames / (1000 / AlbumMenu.FRAME_MILLIS);
        int rest = seconds % 60;
        return seconds / 60 + (rest < 10 ? ":0" : ":") + rest;
    }
}
