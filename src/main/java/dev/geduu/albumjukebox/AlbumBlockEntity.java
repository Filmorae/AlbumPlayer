package dev.geduu.albumjukebox;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class AlbumBlockEntity extends BlockEntity {
    static final int SIZE = 27;
    private static final int PLAYING_SYNC_TICKS = 10;

    private final SimpleContainer inventory = new SimpleContainer(SIZE) {
        @Override
        public void setChanged() {
            super.setChanged();
            inventoryChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return Container.stillValidBlockEntity(AlbumBlockEntity.this, player);
        }
    };

    private ItemStack[] displayDiscs;
    private PlaybackMode mode = PlaybackMode.ONCE;
    private PlaybackStatus status = PlaybackStatus.STOPPED;
    private int selected = -1;
    private int trackNumber;
    private int storedPosition;
    private int revision;
    private ItemStack selectedDisc = ItemStack.EMPTY;
    private AlbumAudio.Track track;
    private AlbumAudio.Clip clip;
    private CompletableFuture<AlbumAudio.Clip> loading;
    private AudioStream stream;
    private UUID channelId;
    private int discChange;
    private int cueChange;
    private DiscColors colors = DiscColors.DEFAULT;
    private AlbumVisualState visualState = AlbumVisualState.EMPTY;
    private long nextVisualSync;
    private long visualReceivedAt;

    AlbumBlockEntity(BlockPos pos, BlockState state) {
        super(AlbumJukebox.ENTITY, pos, state);
    }

    ExtendedMenuProvider<AlbumMenu.Opening> provider(boolean storage) {
        return new ExtendedMenuProvider<>() {
            @Override
            public AlbumMenu.Opening getScreenOpeningData(ServerPlayer player) {
                return new AlbumMenu.Opening(storage, worldPosition);
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable(storage ? "container.album_jukebox.storage" : "block.album_jukebox.jukebox");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
                return new AlbumMenu(id, playerInventory, new AlbumMenu.Opening(storage, worldPosition), inventory, AlbumBlockEntity.this);
            }
        };
    }

    ItemStack displayDisc(int slot) {
        if (displayDiscs == null) {
            displayDiscs = new ItemStack[SIZE];
            for (int i = 0; i < SIZE; i++) {
                ItemStack disc = inventory.getItem(i);
                displayDiscs[i] = DiscColors.isBlanksDisc(disc) ? AlbumAudio.displayDisc(disc) : ItemStack.EMPTY;
            }
        }
        return displayDiscs[slot];
    }

    private void inventoryChanged() {
        displayDiscs = null;
        setChanged();
        revision++;
        if (selected >= 0 && !ItemStack.matches(selectedDisc, inventory.getItem(selected))) {
            cancelAudio();
            selected = -1;
            storedPosition = 0;
            selectedDisc = ItemStack.EMPTY;
            discChange++;
            colors = DiscColors.DEFAULT;
            track = null;
            clip = null;
            status = PlaybackStatus.STOPPED;
        }
        updateTrackNumber();
    }

    // Count the same non-empty Blanks slots as the playlist UI, not storage slot IDs.
    private void updateTrackNumber() {
        trackNumber = 0;
        if (selected < 0) return;
        for (int i = 0; i <= selected; i++) {
            if (DiscColors.isBlanksDisc(inventory.getItem(i))) trackNumber++;
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        if (input.getBooleanOr("AlbumVisual", false)) {
            visualState = new AlbumVisualState(input.getBooleanOr("HasDisc", false),
                input.getIntOr("Body", DiscColors.DEFAULT_BODY), input.getIntOr("Label", DiscColors.DEFAULT_LABEL),
                input.getIntOr("DiscChange", 0), AlbumVisualState.Arm.fromId(input.getIntOr("Arm", 0)),
                input.read("Channel", UUIDUtil.CODEC).orElse(null), input.getIntOr("CueChange", 0),
                input.getIntOr("PositionFrames", 0), input.getIntOr("DurationFrames", 0),
                Math.clamp(input.getIntOr("TrackNumber", 0), 0, SIZE));
            visualReceivedAt = System.nanoTime();
            return;
        }
        ContainerHelper.loadAllItems(input, inventory.getItems());
        displayDiscs = null;
        mode = PlaybackMode.fromId(input.getIntOr("Mode", 0));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, inventory.getItems());
        output.putInt("Mode", mode.ordinal());
    }

    public AlbumVisualState visualState() {
        return visualState;
    }

    public long visualReceivedAt() {
        return visualReceivedAt;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        // A new chunk tracker needs the current frame even between periodic broadcasts.
        var state = level instanceof ServerLevel ? visualSnapshot(visualArm()) : visualState;
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("AlbumVisual", true);
        tag.putBoolean("HasDisc", state.hasDisc());
        tag.putInt("Body", state.bodyColor());
        tag.putInt("Label", state.labelColor());
        tag.putInt("DiscChange", state.discChange());
        tag.putInt("Arm", state.arm().ordinal());
        tag.putInt("CueChange", state.cueChange());
        tag.putInt("PositionFrames", state.positionFrames());
        tag.putInt("DurationFrames", state.durationFrames());
        if (state.trackNumber() > 0) tag.putInt("TrackNumber", state.trackNumber());
        if (state.channel() != null) {
            tag.store("Channel", UUIDUtil.CODEC, state.channel());
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private AlbumVisualState.Arm visualArm() {
        return switch (status) {
            case PLAYING -> AlbumVisualState.Arm.PLAY;
            case PAUSED, LOADING -> AlbumVisualState.Arm.HOVER;
            default -> AlbumVisualState.Arm.REST;
        };
    }

    private AlbumVisualState visualSnapshot(AlbumVisualState.Arm arm) {
        return new AlbumVisualState(!selectedDisc.isEmpty(), colors.body(), colors.label(), discChange, arm,
            channelId, cueChange, position(), duration(), trackNumber);
    }

    private boolean updateVisualState(long gameTime) {
        var arm = visualArm();
        boolean periodic = arm == AlbumVisualState.Arm.PLAY && gameTime >= nextVisualSync;
        if (!periodic && visualState.discChange() == discChange && visualState.cueChange() == cueChange
            && visualState.arm() == arm && visualState.trackNumber() == trackNumber && Objects.equals(visualState.channel(), channelId)) {
            return false;
        }
        visualState = visualSnapshot(arm);
        nextVisualSync = gameTime + PLAYING_SYNC_TICKS;
        return true;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel server) {
            cancelAudio();
            Containers.dropContents(server, pos, inventory);
        }
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    public void setRemoved() {
        cancelAudio();
        super.setRemoved();
    }

    private void cancelAudio() {
        channelId = null;
        if (loading != null) {
            loading.cancel(false);
            loading = null;
        }
        if (stream != null) {
            stream.stop();
            stream = null;
        }
    }

    static void tick(Level level, BlockPos pos, BlockState state, AlbumBlockEntity jukebox) {
        jukebox.tickPlayback((ServerLevel) level);
        boolean visualChanged = jukebox.updateVisualState(level.getGameTime());
        boolean playing = jukebox.status == PlaybackStatus.PLAYING;
        if (state.getValue(AlbumBlock.PLAYING) != playing) {
            level.setBlock(pos, state.setValue(AlbumBlock.PLAYING, playing), Block.UPDATE_CLIENTS);
        }
        if (visualChanged) {
            var viewers = PlayerLookup.tracking(jukebox);
            if (!viewers.isEmpty()) {
                var packet = jukebox.getUpdatePacket();
                for (ServerPlayer viewer : viewers) {
                    viewer.connection.send(packet);
                }
            }
        }
    }

    private void tickPlayback(ServerLevel level) {
        if (loading != null && loading.isDone()) {
            var loaded = loading;
            loading = null;
            try {
                clip = loaded.join();
                resume(level);
            } catch (CompletionException exception) {
                AlbumJukebox.LOGGER.warn("Could not load AudioPlayer recording {}", track.id(), exception.getCause());
                status = PlaybackStatus.ERROR;
            }
        }

        if (stream == null || stream.end() == AudioStream.End.ACTIVE) {
            return;
        }
        var end = stream.end();
        stream = null;
        channelId = null;
        if (end != AudioStream.End.COMPLETE) {
            status = PlaybackStatus.ERROR;
            return;
        }
        storedPosition = duration();
        int next = switch (mode) {
            case ONCE -> following(selected, 1, false);
            case ALL -> following(selected, 1, true);
            case ONE -> selected;
        };
        if (next >= 0) {
            begin(level, next);
        } else {
            status = PlaybackStatus.FINISHED;
        }
    }

    private void begin(ServerLevel level, int slot) {
        if (slot < 0 || slot >= SIZE || !DiscColors.isBlanksDisc(inventory.getItem(slot))) {
            return;
        }
        cancelAudio();
        ItemStack disc = inventory.getItem(slot);
        AlbumAudio.Track nextTrack = AlbumAudio.track(disc);
        if (!Objects.equals(nextTrack, track)) {
            clip = null;
        }
        track = nextTrack;
        if (selected != slot || !ItemStack.matches(selectedDisc, disc)) {
            discChange++;
            colors = DiscColors.of(disc);
        }
        selected = slot;
        updateTrackNumber();
        selectedDisc = disc.copy();
        storedPosition = 0;
        cueChange++;
        revision++;
        if (!AlbumAudio.ready()) {
            status = PlaybackStatus.UNAVAILABLE;
        } else if (track == null) {
            status = PlaybackStatus.ERROR;
        } else if (clip != null) {
            resume(level);
        } else {
            status = PlaybackStatus.LOADING;
            loading = AlbumAudio.load(track);
        }
    }

    private void resume(ServerLevel level) {
        var channel = AlbumAudio.channel(level, worldPosition, track.range());
        if (channel == null) {
            status = PlaybackStatus.UNAVAILABLE;
            return;
        }
        storedPosition = Math.clamp(storedPosition, 0, duration() - 1);
        channelId = channel.id();
        stream = new AudioStream(channel.sink(), clip.frames(), storedPosition);
        status = PlaybackStatus.PLAYING;
        stream.start();
    }

    private int following(int slot, int direction, boolean wrap) {
        if (slot < 0) {
            slot = direction > 0 ? -1 : SIZE;
        }
        for (int step = 1; step <= SIZE; step++) {
            int index = slot + direction * step;
            if (wrap) {
                index = Math.floorMod(index, SIZE);
            } else if (index < 0 || index >= SIZE) {
                break;
            }
            if (DiscColors.isBlanksDisc(inventory.getItem(index))) {
                return index;
            }
        }
        return -1;
    }

    void handle(AlbumAction action) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        switch (action.command()) {
            case AlbumAction.TOGGLE -> toggle(server);
            case AlbumAction.PREVIOUS -> skip(server, -1);
            case AlbumAction.NEXT -> skip(server, 1);
            case AlbumAction.MODE -> {
                mode = mode.next();
                setChanged();
            }
            case AlbumAction.SELECT -> {
                if (action.revision() == revision) {
                    begin(server, action.value());
                }
            }
            case AlbumAction.SEEK -> {
                if (action.revision() == revision && clip != null
                    && (status == PlaybackStatus.PLAYING || status == PlaybackStatus.PAUSED)) {
                    boolean wasPlaying = status == PlaybackStatus.PLAYING;
                    cancelAudio();
                    storedPosition = Math.clamp(action.value(), 0, duration() - 1);
                    cueChange++;
                    if (wasPlaying) {
                        resume(server);
                    }
                }
            }
            default -> { }
        }
    }

    private void skip(ServerLevel level, int direction) {
        int target = following(selected, direction, mode == PlaybackMode.ALL);
        if (target != selected) {
            begin(level, target);
        }
    }

    private void toggle(ServerLevel level) {
        switch (status) {
            case PLAYING -> {
                stream.stop();
                storedPosition = Math.min(stream.position(), duration() - 1);
                stream = null;
                channelId = null;
                status = PlaybackStatus.PAUSED;
            }
            case PAUSED -> resume(level);
            case LOADING -> {
                cancelAudio();
                status = PlaybackStatus.STOPPED;
            }
            case STOPPED, UNAVAILABLE, ERROR -> begin(level, selected >= 0 ? selected : following(-1, 1, false));
            case FINISHED -> begin(level, following(-1, 1, false));
        }
    }

    int position() {
        return stream != null ? stream.position() : storedPosition;
    }

    int duration() {
        return clip == null ? 0 : clip.frames().length;
    }

    int selected() {
        return selected;
    }

    int revision() {
        return revision;
    }

    PlaybackMode mode() {
        return mode;
    }

    PlaybackStatus status() {
        return status;
    }
}
