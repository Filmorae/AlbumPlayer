package dev.geduu.albumjukebox.client;

import dev.geduu.albumjukebox.AlbumJukebox;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class AlbumJukeboxClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(AlbumJukebox.ENTITY, context -> new AlbumJukeboxRenderer());
        MenuScreens.register(AlbumJukebox.MENU, AlbumScreen::new);
    }
}
