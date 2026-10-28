package io.github.nturner03.tlaarchive.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * Something a team achieved, kept as history (design: current readiness is kept apart from historical milestones).
 * It records the manifest it was achieved against, so a later manifest change can't silently rewrite what it meant.
 * {@code manifestId} and {@code manifestVersion} are empty when only the label is known (a milestone backfilled from a
 * journal written before team state existed).
 */
public record Milestone(
        String type,
        long gameTime,
        String manifestLabel,
        Optional<String> manifestId,
        Optional<Integer> manifestVersion,
        Optional<UUID> operation) {

    /** The team's reconstruction committed. {@code operation} is its journal id. */
    public static final String RECONSTRUCTION_COMPLETE = "reconstruction_complete";

    public static final Codec<Milestone> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("type").forGetter(Milestone::type),
            Codec.LONG.fieldOf("game_time").forGetter(Milestone::gameTime),
            Codec.STRING.fieldOf("manifest_label").forGetter(Milestone::manifestLabel),
            Codec.STRING.optionalFieldOf("manifest_id").forGetter(Milestone::manifestId),
            Codec.INT.optionalFieldOf("manifest_version").forGetter(Milestone::manifestVersion),
            UUIDUtil.CODEC.optionalFieldOf("operation").forGetter(Milestone::operation))
            .apply(i, Milestone::new));
}
