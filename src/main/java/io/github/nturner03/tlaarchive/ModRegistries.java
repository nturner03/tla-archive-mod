package io.github.nturner03.tlaarchive;

import io.github.nturner03.tlaarchive.catalog.CatalogMenu;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlock;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistries {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TlaArchive.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TlaArchive.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TlaArchive.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, TlaArchive.MOD_ID);

    public static final DeferredBlock<ArchiveControllerBlock> ARCHIVE_CONTROLLER = BLOCKS.register(
            "archive_controller",
            () -> new ArchiveControllerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.2f, 11f)
                    .sound(SoundType.METAL)));

    public static final DeferredItem<BlockItem> ARCHIVE_CONTROLLER_ITEM =
            ITEMS.registerSimpleBlockItem(ARCHIVE_CONTROLLER);

    @SuppressWarnings("DataFlowIssue") // the data fixer type may be null
    public static final Supplier<BlockEntityType<ArchiveControllerBlockEntity>> ARCHIVE_CONTROLLER_BE =
            BLOCK_ENTITIES.register("archive_controller", () -> BlockEntityType.Builder
                    .of(ArchiveControllerBlockEntity::new, ARCHIVE_CONTROLLER.get())
                    .build(null));

    public static final Supplier<MenuType<CatalogMenu>> CATALOG_MENU =
            MENUS.register("catalog", () -> IMenuTypeExtension.create(CatalogMenu::new));

    private ModRegistries() {
    }

    static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
    }
}
