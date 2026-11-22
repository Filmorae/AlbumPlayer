package dev.geduu.albumjukebox;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record AlbumAction(int menuId, int command, int value, int revision) implements CustomPacketPayload {
    public static final int TOGGLE = 0;
    public static final int PREVIOUS = 1;
    public static final int NEXT = 2;
    public static final int MODE = 3;
    public static final int SELECT = 4;
    public static final int SEEK = 5;

    public static final Type<AlbumAction> TYPE = new Type<>(AlbumJukebox.id("control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlbumAction> CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, AlbumAction::menuId,
        ByteBufCodecs.VAR_INT, AlbumAction::command,
        ByteBufCodecs.VAR_INT, AlbumAction::value,
        ByteBufCodecs.VAR_INT, AlbumAction::revision,
        AlbumAction::new);

    @Override
    public Type<AlbumAction> type() {
        return TYPE;
    }
}
