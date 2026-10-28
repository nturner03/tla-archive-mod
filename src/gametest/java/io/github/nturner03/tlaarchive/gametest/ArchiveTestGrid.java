package io.github.nturner03.tlaarchive.gametest;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import io.github.nturner03.tlaarchive.ModRegistries;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

/**
 * Builds AE2 networks inside the {@code tla_archive:archive_grid} template (8×4×8) using only the AE2 API and registry
 * ids. Reviewed port of the spike harness {@code SpikeGrid} (branch {@code spike/grid-extraction}).
 *
 * <pre>
 * z=1  network A: x=1 creative energy cell | x=2 drive | x=3 cable + storage bus facing east | x=4 chest
 *      controller A on top of the energy cell (1,2,1)
 * z=5  network B: x=1 creative energy cell | x=2 drive; controller B on top of its energy cell (1,2,5)
 * </pre>
 */
public final class ArchiveTestGrid {
    public static final String TEMPLATE = "archive_grid";

    public static final BlockPos ENERGY = new BlockPos(1, 1, 1);
    public static final BlockPos DRIVE = new BlockPos(2, 1, 1);
    public static final BlockPos BUS = new BlockPos(3, 1, 1);
    public static final BlockPos CHEST = new BlockPos(4, 1, 1);
    public static final BlockPos CONTROLLER = ENERGY.above();
    /** A second controller on network A, touching the drive. */
    public static final BlockPos SECOND_CONTROLLER = DRIVE.above();

    public static final BlockPos ENERGY_B = new BlockPos(1, 1, 5);
    public static final BlockPos DRIVE_B = new BlockPos(2, 1, 5);
    public static final BlockPos CONTROLLER_B = ENERGY_B.above();

    private final GameTestHelper helper;

    private ArchiveTestGrid(GameTestHelper helper) {
        this.helper = helper;
    }

    /**
     * Network A with {@code cellContent} in its drive's cell, a storage bus on an empty chest, and a controller.
     * AE2 creates grid nodes only in block-ticking chunks, and gametest chunks have no player nearby, so the
     * template's chunks are force-loaded (as AE2's own {@code GameTestPlotAdapter} does).
     */
    public static ArchiveTestGrid networkA(GameTestHelper helper, List<ItemStack> cellContent) {
        var grid = new ArchiveTestGrid(helper);
        grid.forceChunks();
        grid.placeDrive(ENERGY, DRIVE, cellContent);
        helper.setBlock(CHEST, Blocks.CHEST);
        var busPos = helper.absolutePos(BUS);
        PartHelper.setPart(helper.getLevel(), busPos, null, null, partItem("ae2:fluix_glass_cable"));
        PartHelper.setPart(helper.getLevel(), busPos, Direction.EAST, null, partItem("ae2:storage_bus"));
        helper.setBlock(CONTROLLER, ModRegistries.ARCHIVE_CONTROLLER.get());
        return grid;
    }

    /** A second, unconnected network with its own controller. */
    public void addNetworkB(List<ItemStack> cellContent) {
        placeDrive(ENERGY_B, DRIVE_B, cellContent);
        helper.setBlock(CONTROLLER_B, ModRegistries.ARCHIVE_CONTROLLER.get());
    }

    private void forceChunks() {
        var min = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        var max = new ChunkPos(helper.absolutePos(new BlockPos(7, 0, 7)));
        for (int x = Math.min(min.x, max.x); x <= Math.max(min.x, max.x); x++) {
            for (int z = Math.min(min.z, max.z); z <= Math.max(min.z, max.z); z++) {
                helper.getLevel().setChunkForced(x, z, true);
            }
        }
    }

    private void placeDrive(BlockPos energy, BlockPos drive, List<ItemStack> cellContent) {
        helper.setBlock(energy, block("ae2:creative_energy_cell"));
        helper.setBlock(drive, block("ae2:drive"));
        var driveInv = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(drive),
                null);
        if (driveInv == null) {
            throw new IllegalStateException("drive exposes no item handler");
        }
        var cell = new ItemStack(item("ae2:item_storage_cell_4k"));
        var cellInv = StorageCells.getCellInventory(cell, null);
        if (cellInv == null) {
            throw new IllegalStateException("no cell inventory");
        }
        for (var stack : cellContent) {
            long inserted = cellInv.insert(AEItemKey.of(stack), stack.getCount(), Actionable.MODULATE,
                    IActionSource.empty());
            if (inserted != stack.getCount()) {
                throw new IllegalStateException("cell refused " + stack);
            }
        }
        if (!driveInv.insertItem(0, cell, false).isEmpty()) {
            throw new IllegalStateException("drive refused the cell");
        }
    }

    public ArchiveControllerBlockEntity controller() {
        return controllerAt(CONTROLLER);
    }

    public ArchiveControllerBlockEntity controllerB() {
        return controllerAt(CONTROLLER_B);
    }

    /** Places a second controller on network A. */
    public ArchiveControllerBlockEntity addSecondController() {
        helper.setBlock(SECOND_CONTROLLER, ModRegistries.ARCHIVE_CONTROLLER.get());
        return controllerAt(SECOND_CONTROLLER);
    }

    private ArchiveControllerBlockEntity controllerAt(BlockPos pos) {
        if (helper.getBlockEntity(pos) instanceof ArchiveControllerBlockEntity controller) {
            return controller;
        }
        throw new IllegalStateException("no controller at " + pos);
    }

    public ChestBlockEntity chest() {
        return (ChestBlockEntity) helper.getBlockEntity(CHEST);
    }

    /** Network A's storage, through the drive's node. */
    public MEStorage storage() {
        return node(DRIVE).getGrid().getStorageService().getInventory();
    }

    /** Network B's storage, through its drive's node. */
    public MEStorage storageB() {
        return node(DRIVE_B).getGrid().getStorageService().getInventory();
    }

    /**
     * Mounts {@code extra} on network A through an off-world managed node connected to the drive, the way the spike
     * did. The test destroys the returned node when it is done.
     */
    public IManagedGridNode attachStorage(MEStorage extra) {
        var managed = GridHelper.createManagedNode(this, new IGridNodeListener<ArchiveTestGrid>() {
            @Override
            public void onSaveChanges(ArchiveTestGrid owner, IGridNode node) {
            }
        });
        managed.setInWorldNode(false);
        managed.addService(IStorageProvider.class, mounts -> mounts.mount(extra));
        managed.create(helper.getLevel(), helper.absolutePos(DRIVE.above()));
        GridHelper.createConnection(managed.getNode(), node(DRIVE));
        return managed;
    }

    /** Block entity hosts return their node only for an exposed side, never for {@code null}. */
    @Nullable
    public IGridNode findNode(BlockPos pos) {
        var host = GridHelper.getNodeHost(helper.getLevel(), helper.absolutePos(pos));
        if (host == null) {
            return null;
        }
        for (var side : Direction.values()) {
            var node = host.getGridNode(side);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    public IGridNode node(BlockPos pos) {
        var node = findNode(pos);
        if (node == null) {
            throw new IllegalStateException("no grid node at " + pos + " yet");
        }
        return node;
    }

    public static Block block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalStateException("missing block " + id));
    }

    static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalStateException("missing item " + id));
    }

    static IPartItem<?> partItem(String id) {
        return (IPartItem<?>) item(id);
    }
}
