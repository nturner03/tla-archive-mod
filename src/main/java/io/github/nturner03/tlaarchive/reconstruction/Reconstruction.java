package io.github.nturner03.tlaarchive.reconstruction;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import io.github.nturner03.tlaarchive.ArchiveConfig;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.controller.ArchiveControllerBlockEntity;
import io.github.nturner03.tlaarchive.controller.ArchiveState;
import io.github.nturner03.tlaarchive.controller.TeamAccess;
import io.github.nturner03.tlaarchive.manifest.ActiveManifest;
import io.github.nturner03.tlaarchive.manifest.ManifestMatcher;
import io.github.nturner03.tlaarchive.team.ArchiveTeams;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

/**
 * Starts, advances and recovers reconstructions (roadmap Phase 1 step 5; {@code docs/architecture.md} →
 * <i>Reconstruction</i>). Everything here runs on the server thread, so an operation's extraction is atomic with respect
 * to all other game logic within its tick.
 *
 * <ol>
 * <li>Revalidate: test flag, owner team, controller {@link ArchiveState#LIVE}, a complete plan from the cached
 * inventory, the plan the player confirmed, and a simulated extraction of every planned key in full.</li>
 * <li>Extract every key into the journal's buffer, trusting only {@code MODULATE} amounts. Any shortfall rolls
 * everything back in the same tick.</li>
 * <li>Commit: record completion and clear the buffer in one journal change.</li>
 * </ol>
 *
 * Without the debug pacing ({@link ArchiveConfig#DEBUG_KEYS_PER_TICK}, {@link ArchiveConfig#DEBUG_HOLD_STAGED_TICKS}) all
 * three happen inside {@link #start}.
 */
public final class Reconstruction {
    /** Ticks between attempts to put overflow back while a controller is active. */
    public static final int ROLLBACK_RETRY_TICKS = 20;

    /**
     * Debug pacing: how many keys to extract per tick (0 = all in the activation tick), and how many ticks after
     * activation the commit may happen (0 = at once). Gametests pass their own; players get {@link #fromConfig()}.
     */
    public record Pacing(int keysPerTick, int holdStagedTicks) {
        public static final Pacing IMMEDIATE = new Pacing(0, 0);

        public static Pacing fromConfig() {
            return new Pacing(ArchiveConfig.debugKeysPerTick(), ArchiveConfig.debugHoldStagedTicks());
        }
    }

    /** What {@link #start} did. {@code operation} is present once extraction began, even if it then rolled back. */
    public record StartResult(Optional<ReconstructionOperation> operation, String message) {
        static StartResult refused(String message) {
            return new StartResult(Optional.empty(), message);
        }

        public boolean started() {
            return operation.filter(op -> op.state() != ReconstructionOperation.State.ROLLING_BACK
                    && op.state() != ReconstructionOperation.State.ROLLED_BACK).isPresent();
        }
    }

    private Reconstruction() {
    }

    public static ReconstructionJournal journal(MinecraftServer server) {
        return ReconstructionJournal.get(server);
    }

    /**
     * Why {@code player} can't reconstruct at {@code controller} now, or empty if they may. Doesn't look at stock;
     * {@link #plan} and {@link #start} do.
     */
    public static Optional<String> blocker(ArchiveControllerBlockEntity controller, ServerPlayer player) {
        if (!ArchiveConfig.reconstructionEnabled()) {
            return Optional.of("reconstruction is disabled (server config reconstruction.enabled_for_testing is "
                    + "off; it is a test-only prototype)");
        }
        if (!controller.isOwnedBy(player)) {
            return Optional.of("only the team that owns this archive can reconstruct");
        }
        var stateBlocker = controller.reconstructionBlocker();
        if (stateBlocker.isPresent()) {
            return stateBlocker;
        }
        if (ActiveManifest.matcher().isEmpty()) {
            return Optional.of("no valid archive manifest is loaded");
        }
        var team = controller.ownerTeam().orElseThrow();
        var journal = journal(player.server);
        if (journal.completedFor(team).isPresent()) {
            return Optional.of("this team's reconstruction is already complete");
        }
        if (!journal.pendingFor(team).isEmpty()) {
            return Optional.of("an earlier operation is still running or has overflow to put back");
        }
        return Optional.empty();
    }

    /** A plan from the controller's grid as cached now, or empty if the grid isn't active or no manifest is loaded. */
    public static Optional<ExtractionPlan> plan(ArchiveControllerBlockEntity controller) {
        var matcher = ActiveManifest.matcher().orElse(null);
        var grid = controller.activeGrid().orElse(null);
        if (matcher == null || grid == null) {
            return Optional.empty();
        }
        return Optional.of(ExtractionPlan.build(matcher, grid.getStorageService().getCachedInventory()));
    }

    /**
     * Revalidates everything and runs the operation. {@code confirmed} is the plan the player was shown; if the fresh
     * plan differs, nothing is extracted and the player has to look again.
     */
    public static StartResult start(ArchiveControllerBlockEntity controller, ServerPlayer player,
            @Nullable ExtractionPlan confirmed, Pacing pacing) {
        var blocker = blocker(controller, player);
        if (blocker.isPresent()) {
            return StartResult.refused("Reconstruction refused: " + blocker.get() + ".");
        }
        var grid = controller.activeGrid().orElse(null);
        var plan = plan(controller).orElse(null);
        if (grid == null || plan == null) {
            return StartResult.refused("Reconstruction refused: the network is not active.");
        }
        if (!plan.complete()) {
            return StartResult.refused("Reconstruction refused: not every entry is covered (" + plan.shortfalls().stream()
                    .map(s -> s.entry().displayName() + " " + s.available() + "/" + s.entry().quantity())
                    .collect(Collectors.joining(", ")) + ").");
        }
        if (confirmed != null && !confirmed.amounts().equals(plan.amounts())) {
            return StartResult.refused("Reconstruction refused: the stock changed since the preview. Preview it again.");
        }
        var storage = grid.getStorageService().getInventory();
        var source = IActionSource.ofMachine(controller);
        for (var e : plan.amounts().entrySet()) {
            long available = storage.extract(e.getKey(), e.getValue(), Actionable.SIMULATE, source);
            if (available < e.getValue()) {
                return StartResult.refused("Reconstruction refused: only " + available + " of " + e.getValue() + " "
                        + e.getKey().getDisplayName().getString() + " can be extracted now. Nothing was taken.");
            }
        }

        var level = (ServerLevel) controller.getLevel();
        long now = level.getGameTime();
        var op = new ReconstructionOperation(UUID.randomUUID(), controller.ownerTeam().orElseThrow(), player.getUUID(),
                level.dimension(), controller.getBlockPos(), plan.manifestLabel(), plan.amounts(),
                pacing.keysPerTick(), now, now + pacing.holdStagedTicks());
        var journal = journal(level.getServer());
        journal.add(op);
        TlaArchive.LOGGER.info("Reconstruction {} started by {} for team {} at {} {}: {} keys", op.id(),
                player.getGameProfile().getName(), op.team(), op.dimension().location(), op.controllerPos(),
                plan.amounts().size());
        advance(op, storage, source, now, level.getServer());
        journal.setDirty();
        return new StartResult(Optional.of(op), switch (op.state()) {
            case COMMITTED -> "Reconstruction complete. The required stock was consumed.";
            case EXTRACTING, STAGED -> "Reconstruction started (debug pacing): " + op.state() + ".";
            case ROLLING_BACK, ROLLED_BACK -> "Reconstruction rolled back: " + op.outcome()
                    + ". Nothing was consumed.";
        });
    }

    /**
     * Advances, aborts or rolls back the controller's operations. Called every server tick by the controller, frozen or
     * not, so stock can always go back. Only a controller owned by the operation's team touches it.
     */
    public static void tick(ArchiveControllerBlockEntity controller, long gameTime) {
        if (!(controller.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var journal = journal(level.getServer());
        if (!journal.hasPending()) {
            return;
        }
        for (var op : journal.pendingAt(level.dimension(), controller.getBlockPos())) {
            if (controller.ownerTeam().filter(op.team()::equals).isEmpty()) {
                continue;
            }
            var grid = controller.activeGrid().orElse(null);
            boolean justAborted = false;
            if ((op.state() == ReconstructionOperation.State.EXTRACTING
                    || op.state() == ReconstructionOperation.State.STAGED)
                    && (grid == null || controller.state() != ArchiveState.LIVE)) {
                justAborted = op.abort("the controller stopped being live (power, grid or manifest change)");
            }
            if (grid == null) {
                if (justAborted) {
                    journal.setDirty();
                }
                continue;
            }
            var storage = grid.getStorageService().getInventory();
            var source = IActionSource.ofMachine(controller);
            if (op.state() == ReconstructionOperation.State.ROLLING_BACK) {
                if (justAborted || gameTime % ROLLBACK_RETRY_TICKS == 0) {
                    op.rollback(storage, source, gameTime);
                    report(level.getServer(), op);
                }
            } else {
                advance(op, storage, source, gameTime, level.getServer());
            }
            journal.setDirty();
        }
    }

    private static void advance(ReconstructionOperation op, MEStorage storage, IActionSource source, long gameTime,
            MinecraftServer server) {
        op.step(storage, source);
        if (op.state() == ReconstructionOperation.State.ROLLING_BACK) {
            op.rollback(storage, source, gameTime);
        } else if (op.state() == ReconstructionOperation.State.STAGED && gameTime >= op.commitAt()) {
            op.commit(gameTime);
            // A manifest reload aborts a running operation, so the active manifest is the one it was planned against.
            ArchiveTeams.get(server).recordReconstruction(op, ActiveManifest.matcher().map(ManifestMatcher::manifest));
        }
        report(server, op);
    }

    private static void report(MinecraftServer server, ReconstructionOperation op) {
        if (!op.state().terminal() && op.state() != ReconstructionOperation.State.ROLLING_BACK) {
            return;
        }
        if (op.state() == ReconstructionOperation.State.COMMITTED) {
            TlaArchive.LOGGER.info("Reconstruction {} for team {} committed", op.id(), op.team());
        } else if (op.state() == ReconstructionOperation.State.ROLLED_BACK) {
            TlaArchive.LOGGER.info("Reconstruction {} for team {} rolled back ({})", op.id(), op.team(), op.outcome());
        }
        if (op.state() == ReconstructionOperation.State.ROLLING_BACK) {
            return; // retried quietly; the readout shows the overflow
        }
        var player = server.getPlayerList().getPlayer(op.startedBy());
        if (player != null && op.finishedAt() > op.startedAt()) {
            player.sendSystemMessage(Component.literal(op.state() == ReconstructionOperation.State.COMMITTED
                    ? "Reconstruction complete."
                    : "Reconstruction rolled back: " + op.outcome() + ". Nothing was consumed."));
        }
    }

    /** The controller unloaded or was removed: its running operations stop and roll back when it is active again. */
    public static void controllerGone(ArchiveControllerBlockEntity controller) {
        if (!(controller.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var journal = journal(level.getServer());
        if (!journal.hasPending()) {
            return;
        }
        for (var op : journal.pendingAt(level.dimension(), controller.getBlockPos())) {
            if (op.abort("the controller was unloaded or removed")) {
                TlaArchive.LOGGER.info("Reconstruction {} aborted: controller at {} unloaded or removed", op.id(),
                        op.controllerPos());
                journal.setDirty();
            }
        }
    }

    /** A member joined or left {@code team}, or it was deleted (owner ruling 7e). */
    public static void onTeamChanged(UUID team, String reason) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            journal(server).abortTeam(team, reason);
        }
    }

    /**
     * On server start: every operation that was running rolls back (owner ruling 3), and a committed operation from
     * before team state existed gets its milestone.
     */
    public static void recoverOnStart(MinecraftServer server) {
        var teams = ArchiveTeams.get(server);
        journal(server).committed().forEach(op -> teams.recordReconstruction(op, Optional.empty()));
        int recovered = journal(server).recoverAfterLoad();
        if (recovered > 0) {
            TlaArchive.LOGGER.warn("{} interrupted reconstruction(s) will roll back when their controllers are active",
                    recovered);
        }
    }

    /**
     * Moves the player's team's overflow into their inventory, as far as it has room. Only the team's own overflow is
     * reachable. Returns the number of items moved.
     */
    public static long takeOverflow(ServerPlayer player) {
        var team = TeamAccess.teamOf(player).orElse(null);
        if (team == null) {
            return 0;
        }
        var journal = journal(player.server);
        long moved = 0;
        for (var op : journal.pendingFor(team)) {
            moved += op.moveOverflowTo(player.getInventory(), player.serverLevel().getGameTime());
        }
        if (moved > 0) {
            journal.setDirty();
            TlaArchive.LOGGER.info("{} took {} overflow items of team {}", player.getGameProfile().getName(), moved,
                    team);
        }
        return moved;
    }
}
