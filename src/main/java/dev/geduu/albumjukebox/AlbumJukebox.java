package dev.geduu.albumjukebox;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AlbumJukebox implements ModInitializer {
    public static final String ID = "album_jukebox";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);
    public static AlbumBlock BLOCK;
    public static BlockItem ITEM;
    public static BlockEntityType<AlbumBlockEntity> ENTITY;
    public static MenuType<AlbumMenu> MENU;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    @Override
    public void onInitialize() {
        var blockKey = ResourceKey.create(Registries.BLOCK, id("jukebox"));
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, blockKey,
            new AlbumBlock(BlockBehaviour.Properties.of().setId(blockKey).strength(2.5F, 6.0F).sound(SoundType.WOOD)));
        var itemKey = ResourceKey.create(Registries.ITEM, id("jukebox"));
        ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey,
            new BlockItem(BLOCK, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
        ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("jukebox"),
            FabricBlockEntityTypeBuilder.create(AlbumBlockEntity::new, BLOCK).build());
        MENU = Registry.register(BuiltInRegistries.MENU, id("jukebox"),
            new ExtendedMenuType<>(AlbumMenu::new, AlbumMenu.Opening.CODEC));

        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("functional_blocks"))).register(output -> output.accept(ITEM));

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && !player.isSpectator()
                && level.getBlockState(hit.getBlockPos()).is(BLOCK)) {
                return BLOCK.open(level, hit.getBlockPos(), player);
            }
            return InteractionResult.PASS;
        });

        PayloadTypeRegistry.serverboundPlay().register(AlbumAction.TYPE, AlbumAction.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AlbumAction.TYPE, (action, context) -> {
            var player = context.player();
            if (player.containerMenu instanceof AlbumMenu menu && menu.containerId == action.menuId()
                && !menu.storage && menu.stillValid(player)) {
                menu.handle(action);
            }
        });
    }
}
