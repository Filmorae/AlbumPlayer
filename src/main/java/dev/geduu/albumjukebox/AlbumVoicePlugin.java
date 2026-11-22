package dev.geduu.albumjukebox;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

public final class AlbumVoicePlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return AlbumJukebox.ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class,
                event -> AlbumMeterInput.accept(event.getId(), event.getRawAudio()));
        }
    }
}
