package dev.geduu.albumjukebox;

import de.maxhenkel.audioplayer.AudioPlayerMod;
import de.maxhenkel.audioplayer.api.AudioPlayerApi;
import de.maxhenkel.audioplayer.api.AudioPlayerConstants;
import de.maxhenkel.audioplayer.audioloader.AudioStorageManager;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class AlbumAudio {
    record Track(UUID id, float range) {}
    record Clip(byte[][] frames) {}
    record PlaybackChannel(UUID id, AudioStream.Sink sink) {}

    private static final Executor LOADER = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "Album Jukebox audio loader");
        thread.setDaemon(true);
        return thread;
    });

    static boolean installed() {
        return FabricLoader.getInstance().isModLoaded("audioplayer") && FabricLoader.getInstance().isModLoaded("voicechat");
    }

    static Track track(ItemStack stack) {
        if (!DiscColors.isBlanksDisc(stack) || !stack.has(DataComponents.JUKEBOX_PLAYABLE) || !installed()) {
            return null;
        }
        return AudioPlayerApi.instance().getAudioData(stack.copy()).map(data -> {
            UUID id = data.getSoundId();
            float defaultRange = AudioPlayerMod.SERVER_CONFIG.musicDiscRange.get();
            float maxRange = AudioPlayerMod.SERVER_CONFIG.maxMusicDiscRange.get();
            Float customRange = data.getRange();
            float range = customRange != null && Float.isFinite(customRange) ? customRange : defaultRange;
            return new Track(id, Math.clamp(range, 0F, Math.max(0F, maxRange)));
        }).orElse(null);
    }

    static ItemStack displayDisc(ItemStack disc) {
        var lore = disc.get(DataComponents.LORE);
        if (disc.isEmpty() || disc.has(DataComponents.CUSTOM_NAME) || (lore != null && !lore.lines().isEmpty())) {
            return disc;
        }
        Track recording = track(disc);
        if (recording == null) {
            return disc;
        }
        String name = AudioStorageManager.metadataManager().getFileName(recording.id());
        if (name == null || name.isBlank()) {
            return disc;
        }
        int extension = name.lastIndexOf('.');
        ItemStack display = disc.copy();
        display.set(DataComponents.CUSTOM_NAME, Component.literal(extension > 0 ? name.substring(0, extension) : name));
        return display;
    }

    static CompletableFuture<Clip> load(Track track) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var audio = AudioStorageManager.audioCache().getAudio(track.id());
                float maxDuration = AudioPlayerMod.SERVER_CONFIG.maxMusicDiscDuration.get();
                if (audio.getOpusFrames().length == 0 || (maxDuration >= 0 && audio.getDurationSeconds() > maxDuration)) {
                    throw new IllegalArgumentException("Empty audio or music disc duration limit exceeded");
                }
                return new Clip(audio.getOpusFrames());
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, LOADER);
    }

    static boolean ready() {
        return installed() && AudioPlayerApi.instance().getVoicechatServerApi() != null;
    }

    static PlaybackChannel channel(ServerLevel level, BlockPos pos, float range) {
        var api = AudioPlayerApi.instance().getVoicechatServerApi();
        if (api == null) {
            return null;
        }
        Vec3 center = pos.getCenter();
        var channel = api.createLocationalAudioChannel(UUID.randomUUID(), api.fromServerLevel(level),
            api.createPosition(center.x, center.y, center.z));
        if (channel == null) {
            return null;
        }
        channel.setCategory(AudioPlayerConstants.MUSIC_DISC_CATEGORY);
        channel.setDistance(range);
        return new PlaybackChannel(channel.getId(), new AudioStream.Sink() {
            @Override
            public void send(byte[] frame) {
                channel.send(frame);
            }

            @Override
            public void close() {
                channel.flush();
            }
        });
    }
}
