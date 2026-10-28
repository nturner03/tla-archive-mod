package io.github.nturner03.tlaarchive.spike.grid;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartHelper;
import appeng.api.storage.IStorageProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

/**
 * SPIKE (throwaway). Builds a small AE2 network inside a gametest using only the AE2 API and registry ids:
 *
 * <pre>
 * x=1 creative energy cell | x=2 drive (one 1k item cell) | x=3 cable + storage bus facing east | x=4 chest
 * </pre>
 *
 * Optionally attaches an off-world "controller" node (our own managed grid node) to the drive, which can mount an
 * extra {@link MEStorage}, the way a real archive controller would join the network.
 */
final class SpikeGrid {
    static final BlockPos ENERGY = new BlockPos(1, 1, 1);
    static final BlockPos DRIVE = new BlockPos(2, 1, 1);
    static final BlockPos BUS = new BlockPos(3, 1, 1);
    static final BlockPos CHEST = new BlockPos(4, 1, 1);

    final GameTestHelper helper;
    @Nullable
    IManagedGridNode controllerNode;

    private SpikeGrid(GameTestHelper helper) {
        this.helper = helper;
    }

    /** Builds the network; {@code cellContent} is written into the drive's cell before it is inserted. */
    static SpikeGrid build(GameTestHelper helper, Map<Item, Long> cellContent) {
        var grid = new SpikeGrid(helper);
        // AE2 only creates grid nodes in block-ticking chunks; gametest chunks have no player nearby, so force them
        // (as AE2's own GameTestPlotAdapter does).
        var min = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        var max = new ChunkPos(helper.absolutePos(new BlockPos(7, 0, 7)));
        for (int x = Math.min(min.x, max.x); x <= Math.max(min.x, max.x); x++) {
            for (int z = Math.min(min.z, max.z); z <= Math.max(min.z, max.z); z++) {
                helper.getLevel().setChunkForced(x, z, true);
            }
        }
        helper.setBlock(ENERGY, block("ae2:creative_energy_cell"));
        helper.setBlock(DRIVE, block("ae2:drive"));
        helper.setBlock(CHEST, Blocks.CHEST);

        var level = helper.getLevel();
        var busPos = helper.absolutePos(BUS);
        PartHelper.setPart(level, busPos, null, null, partItem("ae2:fluix_glass_cable"));
        PartHelper.setPart(level, busPos, Direction.EAST, null, partItem("ae2:storage_bus"));

        var driveInv = level.getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(DRIVE), null);
        if (driveInv == null) {
            throw new IllegalStateException("drive exposes no item handler");
        }
        var cell = new ItemStack(item("ae2:item_storage_cell_1k"));
        var cellInv = StorageCells.getCellInventory(cell, null);
        if (cellInv == null) {
            throw new IllegalStateException("no cell inventory");
        }
        cellContent.forEach((what, amount) -> {
            if (cellInv.insert(AEItemKey.of(what), amount, Actionable.MODULATE, IActionSource.empty()) != amount) {
                throw new IllegalStateException("cell refused " + amount + " " + what);
            }
        });
        var rest = driveInv.insertItem(0, cell, false);
        if (!rest.isEmpty()) {
            throw new IllegalStateException("drive refused the cell");
        }
        return grid;
    }

    /** Attaches our own off-world node to the drive's node, optionally mounting {@code extraStorage}. */
    void attachController(@Nullable MEStorage extraStorage) {
        var node = GridHelper.createManagedNode(this, new IGridNodeListener<SpikeGrid>() {
            @Override
            public void onSaveChanges(SpikeGrid owner, IGridNode node) {
            }
        });
        node.setInWorldNode(false);
        if (extraStorage != null) {
            node.addService(IStorageProvider.class, mounts -> mounts.mount(extraStorage));
        }
        node.create(helper.getLevel(), helper.absolutePos(DRIVE.above()));
        GridHelper.createConnection(node.getNode(), driveNode());
        controllerNode = node;
    }

    void destroy() {
        if (controllerNode != null) {
            controllerNode.destroy();
            controllerNode = null;
        }
    }

    IGridNode driveNode() {
        var node = findDriveNode();
        if (node == null) {
            throw new IllegalStateException("drive has no grid node yet");
        }
        return node;
    }

    /** Block entity hosts only return their node for an exposed side ({@code getGridNode(null)} is null). */
    @Nullable
    private IGridNode findDriveNode() {
        var host = GridHelper.getNodeHost(helper.getLevel(), helper.absolutePos(DRIVE));
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

    boolean isActive() {
        var node = findDriveNode();
        return node != null && node.isActive();
    }

    String describe() {
        var abs = helper.absolutePos(DRIVE);
        var where = " tick=" + helper.getTick() + " be=" + helper.getLevel().getBlockEntity(abs)
                + " ticking=" + helper.getLevel().getChunkSource().isPositionTicking(ChunkPos.asLong(abs));
        var node = findDriveNode();
        if (node == null) {
            return "drive has no node" + where;
        }
        return "powered=" + node.isPowered() + " online=" + node.isOnline() + " active=" + node.isActive()
                + " nodes=" + node.getGrid().size() + " pathing=" + node.getGrid().getPathingService().isNetworkBooting()
                + " power=" + node.getGrid().getEnergyService().getStoredPower();
    }

    IGrid grid() {
        return driveNode().getGrid();
    }

    ChestBlockEntity chest() {
        return (ChestBlockEntity) helper.getBlockEntity(CHEST);
    }

    static Block block(String id) {
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
