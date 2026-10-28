package io.github.nturner03.tlaarchive.controller;

import com.mojang.serialization.MapCodec;
import io.github.nturner03.tlaarchive.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** The archive controller block. Using it with an empty hand prints the counts to its owner team (minimal display). */
public final class ArchiveControllerBlock extends BaseEntityBlock {
    public static final MapCodec<ArchiveControllerBlock> CODEC = simpleCodec(ArchiveControllerBlock::new);

    public ArchiveControllerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ArchiveControllerBlockEntity(pos, state);
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModRegistries.ARCHIVE_CONTROLLER_BE.get(),
                        ArchiveControllerBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof ArchiveControllerBlockEntity controller) {
            controller.claimFor(player);
            if (controller.ownerTeam().isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "This archive controller has no owner team: FTB Teams has no team for you yet."));
            }
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ArchiveControllerBlockEntity controller) {
            ControllerReadout.lines(controller, serverPlayer, level.getGameTime())
                    .forEach(serverPlayer::sendSystemMessage);
        }
        return InteractionResult.CONSUME;
    }
}
