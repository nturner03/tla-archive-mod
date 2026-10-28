package io.github.nturner03.tlaarchive.catalog;

import io.github.nturner03.tlaarchive.ModRegistries;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The open catalog: a menu without slots, so vanilla handles opening, closing, distance and dimension changes. While
 * it is open on the server, {@link CatalogSync} sends its viewer the manifest and counts. It closes as soon as the
 * viewer stops being in the owner team ({@link #stillValid}), so another team never sees the counts.
 */
public final class CatalogMenu extends AbstractContainerMenu {
    /** How far the viewer may be from the controller, as for a vanilla container. */
    public static final double MAX_DISTANCE = 4.0;

    private final BlockPos pos;
    @Nullable
    private final ArchiveControllerBlockEntity controller;
    @Nullable
    private final CatalogSync sync;

    /** Client side: the server wrote the controller position. */
    public CatalogMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        super(ModRegistries.CATALOG_MENU.get(), containerId);
        this.pos = extraData.readBlockPos();
        this.controller = null;
        this.sync = null;
    }

    /** Server side. */
    public CatalogMenu(int containerId, ServerPlayer viewer, ArchiveControllerBlockEntity controller) {
        super(ModRegistries.CATALOG_MENU.get(), containerId);
        this.pos = controller.getBlockPos();
        this.controller = controller;
        this.sync = new CatalogSync(this, viewer, controller);
    }

    public BlockPos pos() {
        return pos;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (controller == null) {
            return true;
        }
        return !controller.isRemoved() && player instanceof ServerPlayer viewer && controller.isOwnedBy(viewer)
                && player.canInteractWithBlock(pos, MAX_DISTANCE);
    }

    /** The viewer acknowledged the manifest changes. Ignored unless the menu is still valid for them. */
    void acknowledgeChanges(ServerPlayer player) {
        if (sync != null && stillValid(player)) {
            sync.acknowledgeChanges();
        }
    }

    /** Called every server tick while the menu is open. */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (sync != null) {
            sync.tick();
        }
    }
}
