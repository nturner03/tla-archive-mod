package io.github.nturner03.tlaarchive.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * One team's archive: its identity (the FTB team id, owner ruling 2), when it was first claimed, when FTB Teams
 * deleted the team, its milestones, and the manifest it last acknowledged (migration policy). It never holds counts:
 * counts are a cache rebuilt from storage (design: <i>Team state, persistence, and performance</i>). No archive
 * settings exist yet.
 */
public record TeamArchive(UUID team, long createdAt, Optional<Long> deletedAt, List<Milestone> milestones,
        Optional<SeenManifest> seen) {
    public static final Codec<TeamArchive> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("team").forGetter(TeamArchive::team),
            Codec.LONG.fieldOf("created_at").forGetter(TeamArchive::createdAt),
            Codec.LONG.optionalFieldOf("deleted_at").forGetter(TeamArchive::deletedAt),
            Milestone.CODEC.listOf().fieldOf("milestones").forGetter(TeamArchive::milestones),
            SeenManifest.CODEC.optionalFieldOf("seen").forGetter(TeamArchive::seen))
            .apply(i, TeamArchive::new));

    public TeamArchive {
        milestones = List.copyOf(milestones);
    }

    static TeamArchive create(UUID team, long gameTime) {
        return new TeamArchive(team, gameTime, Optional.empty(), List.of(), Optional.empty());
    }

    TeamArchive withMilestone(Milestone milestone) {
        var list = new ArrayList<>(milestones);
        list.add(milestone);
        return new TeamArchive(team, createdAt, deletedAt, list, seen);
    }

    TeamArchive deleted(long gameTime) {
        return new TeamArchive(team, createdAt, Optional.of(gameTime), milestones, seen);
    }

    TeamArchive withSeen(SeenManifest manifest) {
        return new TeamArchive(team, createdAt, deletedAt, milestones, Optional.of(manifest));
    }

    /** The milestone recording reconstruction {@code operation}, if there is one. */
    public Optional<Milestone> milestoneFor(UUID operation) {
        return milestones.stream().filter(m -> m.operation().filter(operation::equals).isPresent()).findFirst();
    }

    public Optional<Milestone> reconstructionComplete() {
        return milestones.stream().filter(m -> m.type().equals(Milestone.RECONSTRUCTION_COMPLETE)).findFirst();
    }
}
