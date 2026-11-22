package dev.geduu.albumjukebox;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

record DiscColors(int body, int label) {
    static final int DEFAULT_BODY = 0x353b3d;
    static final int DEFAULT_LABEL = 0xe6e6e6;
    static final DiscColors DEFAULT = new DiscColors(DEFAULT_BODY, DEFAULT_LABEL);
    private static final Identifier BLANKS_CUSTOM = Identifier.fromNamespaceAndPath("audioplayerblanks", "custom_music_disc");
    private static final Identifier BLANKS_BASE = Identifier.fromNamespaceAndPath("audioplayerblanks", "music_disc");

    static boolean isBlanksDisc(ItemStack disc) {
        var itemId = BuiltInRegistries.ITEM.getKey(disc.getItem());
        return BLANKS_CUSTOM.equals(itemId) || BLANKS_BASE.equals(itemId);
    }

    static DiscColors of(ItemStack disc) {
        var itemId = BuiltInRegistries.ITEM.getKey(disc.getItem());
        var data = disc.get(DataComponents.CUSTOM_MODEL_DATA);
        if (BLANKS_CUSTOM.equals(itemId)) {
            return new DiscColors(color(data, 0, DEFAULT_BODY), color(data, 1, DEFAULT_LABEL));
        }
        var dye = disc.get(DataComponents.DYED_COLOR);
        if (BLANKS_BASE.equals(itemId)) {
            return new DiscColors(dye == null ? color(data, 0, DEFAULT_BODY) : rgb(dye.rgb()), DEFAULT_LABEL);
        }
        return dye == null ? DEFAULT : new DiscColors(rgb(dye.rgb()), DEFAULT_LABEL);
    }

    private static int color(CustomModelData data, int index, int fallback) {
        return data != null && index < data.colors().size() ? rgb(data.colors().get(index)) : fallback;
    }

    private static int rgb(int color) {
        return color & 0xffffff;
    }
}
