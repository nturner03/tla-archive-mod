package io.github.nturner03.tlaarchive.team;

import com.mojang.serialization.Dynamic;
import io.github.nturner03.tlaarchive.TlaArchive;
import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import io.github.nturner03.tlaarchive.reconstruction.ReconstructionOperation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Every team's archive, keyed by FTB team id, in the overworld's SavedData ({@code data/tla_archive_teams.dat}). Not
 * FTB's team {@code extraData}, which FTB copies when a party is created ({@code docs/spikes/ftb-teams.md}). Records
 * are kept after a team is deleted, as history.
 */
public final class ArchiveTeams extends SavedData {
    static final String NAME = TlaArchive.MOD_ID + "_teams";
    /** The layout of this file. A newer one is kept unread rather than overwritten. */
    static final int FORMAT = 1;
    private static final SavedData.Factory<ArchiveTeams> FACTORY =
            new SavedData.Factory<>(ArchiveTeams::new, ArchiveTeams::load);

    private final Map<UUID, TeamArchive> teams = new LinkedHashMap<>();
    /** Records that can't be read back. Saved again unchanged, never dropped. */
    private final List<Tag> unreadable = new ArrayList<>();
    @Nullable
    private CompoundTag newerFormat;

    public static ArchiveTeams get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public Optional<TeamArchive> find(UUID team) {
        return Optional.ofNullable(teams.get(team));
    }

    public Collection<TeamArchive> all() {
        return teams.values();
    }

    /** Creates the team's archive the first time one of its controllers is claimed. */
    public TeamArchive claim(UUID team, long gameTime) {
        var existing = teams.get(team);
        if (existing != null) {
            return existing;
        }
        var created = TeamArchive.create(team, gameTime);
        teams.put(team, created);
        setDirty();
        TlaArchive.LOGGER.info("Archive created for team {}", team);
        return created;
    }

    /** FTB Teams deleted the team (owner ruling 7d). The record stays as history. */
    public void markDeleted(UUID team, long gameTime) {
        var existing = teams.get(team);
        if (existing != null && existing.deletedAt().isEmpty()) {
            teams.put(team, existing.deleted(gameTime));
            setDirty();
        }
    }

    /**
     * Records a committed reconstruction once. {@code manifest} is the manifest it was planned against when that is
     * still known; otherwise only the operation's label is kept.
     */
    public void recordReconstruction(ReconstructionOperation op, Optional<ArchiveManifest> manifest) {
        var archive = claim(op.team(), op.startedAt());
        if (archive.milestoneFor(op.id()).isPresent()) {
            return;
        }
        var known = manifest.filter(m -> m.label().equals(op.manifestLabel()));
        teams.put(op.team(), archive.withMilestone(new Milestone(Milestone.RECONSTRUCTION_COMPLETE, op.finishedAt(),
                op.manifestLabel(), known.map(ArchiveManifest::id), known.map(ArchiveManifest::version),
                Optional.of(op.id()))));
        setDirty();
    }

    @VisibleForTesting
    public void put(TeamArchive archive) {
        teams.put(archive.team(), archive);
        setDirty();
    }

    // --- Persistence ---

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (newerFormat != null) {
            return newerFormat.copy();
        }
        tag.putInt("format", FORMAT);
        var list = new ListTag();
        for (var archive : teams.values()) {
            TeamArchive.CODEC.encodeStart(NbtOps.INSTANCE, archive)
                    .ifSuccess(list::add)
                    .ifError(error -> TlaArchive.LOGGER.error("Can't save the archive of team {}: {}",
                            archive.team(), error.message()));
        }
        list.addAll(unreadable);
        tag.put("teams", list);
        return tag;
    }

    @VisibleForTesting
    public static ArchiveTeams load(CompoundTag tag, HolderLookup.Provider registries) {
        var teams = new ArchiveTeams();
        int format = tag.getInt("format");
        if (format > FORMAT) {
            TlaArchive.LOGGER.error("Team archive data has format {}, newer than this mod reads ({}); keeping it "
                    + "unchanged and starting without team state", format, FORMAT);
            teams.newerFormat = tag.copy();
            return teams;
        }
        var list = tag.getList("teams", Tag.TAG_COMPOUND);
        for (var entry : list) {
            TeamArchive.CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, entry))
                    .ifSuccess(archive -> teams.teams.put(archive.team(), archive))
                    .ifError(error -> {
                        TlaArchive.LOGGER.error("Team archive record can't be read, keeping it unchanged: {} ({})",
                                entry, error.message());
                        teams.unreadable.add(entry.copy());
                    });
        }
        return teams;
    }
}
