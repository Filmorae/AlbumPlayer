package dev.geduu.albumjukebox;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class AlbumMenu extends AbstractContainerMenu {
    public record Opening(boolean storage, BlockPos pos) {
        static final StreamCodec<RegistryFriendlyByteBuf, Opening> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, Opening::storage, BlockPos.STREAM_CODEC, Opening::pos, Opening::new);
    }

    public static final int FRAME_MILLIS = 20;
    public static final int DISC_SLOTS = AlbumBlockEntity.SIZE;
    private static final int SELECTED = 0;
    private static final int STATUS = 1;
    private static final int MODE = 2;
    private static final int POSITION = 3;
    private static final int DURATION = 5;
    private static final int REVISION = 7;

    public final boolean storage;
    private final Container inventory;
    private final Level level;
    private final BlockPos pos;
    private final AlbumBlockEntity jukebox;
    private final SimpleContainerData state = new SimpleContainerData(9);

    AlbumMenu(int id, Inventory playerInventory, Opening opening) {
        this(id, playerInventory, opening, new SimpleContainer(DISC_SLOTS), null);
    }

    AlbumMenu(int id, Inventory playerInventory, Opening opening, Container inventory, AlbumBlockEntity jukebox) {
        super(AlbumJukebox.MENU, id);
        this.storage = opening.storage();
        this.pos = opening.pos();
        this.level = playerInventory.player.level();
        this.inventory = inventory;
        this.jukebox = jukebox;
        for (int i = 0; i < DISC_SLOTS; i++) {
            int x = 8 + i % 9 * 18;
            int y = 18 + i / 9 * 18;
            addSlot(new Slot(inventory, i, x, y) {
                @Override
                public ItemStack getItem() {
                    return !storage && jukebox != null ? jukebox.displayDisc(getContainerSlot()) : super.getItem();
                }

                @Override
                public boolean mayPlace(ItemStack stack) {
                    return storage && (jukebox == null
                        ? DiscColors.isBlanksDisc(stack) && stack.has(DataComponents.JUKEBOX_PLAYABLE)
                        : AlbumAudio.track(stack) != null);
                }

                @Override
                public boolean isActive() {
                    return storage;
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        state.set(SELECTED, -1);
        if (storage) {
            addStandardInventorySlots(playerInventory, 8, 85);
        } else {
            updatePlaybackData();
            addDataSlots(state);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return inventory.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (!storage || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean moved = index < DISC_SLOTS
            ? moveItemStackTo(stack, DISC_SLOTS, slots.size(), true)
            : moveItemStackTo(stack, 0, DISC_SLOTS, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY, original);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player player) {
        if (storage) {
            super.clicked(slot, button, input, player);
        }
    }

    @Override
    public void broadcastChanges() {
        updatePlaybackData();
        super.broadcastChanges();
    }

    @Override
    public void broadcastFullState() {
        updatePlaybackData();
        super.broadcastFullState();
    }

    private void updatePlaybackData() {
        if (storage || jukebox == null) {
            return;
        }
        state.set(SELECTED, jukebox.selected());
        state.set(STATUS, jukebox.status().ordinal());
        state.set(MODE, jukebox.mode().ordinal());
        writeInt(POSITION, jukebox.position());
        writeInt(DURATION, jukebox.duration());
        writeInt(REVISION, jukebox.revision());
    }

    // Menu data packets carry signed 16-bit values. Long tracks need two words.
    private void writeInt(int offset, int value) {
        state.set(offset, (short) value);
        state.set(offset + 1, (short) (value >>> 16));
    }

    private int readInt(int offset) {
        return (state.get(offset) & 0xffff) | ((state.get(offset + 1) & 0xffff) << 16);
    }

    void handle(AlbumAction action) {
        if (jukebox != null) {
            jukebox.handle(action);
        }
    }

    public int selected() {
        return state.get(SELECTED);
    }

    public int position() {
        return readInt(POSITION);
    }

    public int duration() {
        return readInt(DURATION);
    }

    public int revision() {
        return readInt(REVISION);
    }

    public PlaybackMode mode() {
        return PlaybackMode.fromId(state.get(MODE));
    }

    public PlaybackStatus status() {
        return PlaybackStatus.fromId(state.get(STATUS));
    }

    public UUID audioChannel() {
        if (!storage && level.isClientSide() && level.getBlockEntity(pos) instanceof AlbumBlockEntity block) {
            return block.visualState().channel();
        }
        return null;
    }

    public ItemStack disc(int slot) {
        return slot >= 0 && slot < DISC_SLOTS ? getSlot(slot).getItem() : ItemStack.EMPTY;
    }
}
