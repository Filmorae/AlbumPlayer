package dev.geduu.albumjukebox.client;

import dev.geduu.albumjukebox.AlbumJukebox;
import dev.geduu.albumjukebox.AlbumMeterInput;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class AlbumJukeboxClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(AlbumJukebox.ENTITY, context -> new AlbumJukeboxRenderer());
        MenuScreens.register(AlbumJukebox.MENU, AlbumScreen::new);
        ClientTickEvents.END_CLIENT_TICK.register(client -> AlbumMeterInput.prune());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AlbumMeterInput.clear();
            AlbumClientSession.reset();
        });
    }
}
