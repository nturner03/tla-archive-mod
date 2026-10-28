package io.github.nturner03.tlaarchive.team;

import io.github.nturner03.tlaarchive.manifest.ArchiveManifest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What changed between the manifest a team last acknowledged and the active one (migration policy, owner ruling
 * 2026-10-28): entries added, entries removed, and quantities raised or lowered. Added and changed entries follow the
 * active manifest's order; removed ones are sorted by key (saved data keeps no order).
 */
public record ManifestChanges(String fromLabel, List<String> added, List<Removed> removed, List<Changed> changed) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ManifestChanges> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManifestChanges::fromLabel,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManifestChanges::added,
            Removed.STREAM_CODEC.apply(ByteBufCodecs.list()), ManifestChanges::removed,
            Changed.STREAM_CODEC.apply(ByteBufCodecs.list()), ManifestChanges::changed,
            ManifestChanges::new);

    public ManifestChanges {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        changed = List.copyOf(changed);
    }

    public static ManifestChanges between(SeenManifest seen, ArchiveManifest current) {
        var added = new ArrayList<String>();
        var changed = new ArrayList<Changed>();
        var currentKeys = new HashSet<String>();
        for (var entry : current.entries()) {
            currentKeys.add(entry.key());
            var before = seen.quantities().get(entry.key());
            if (before == null) {
                added.add(entry.key());
            } else if (before != entry.quantity()) {
                changed.add(new Changed(entry.key(), before, entry.quantity()));
            }
        }
        var removed = new ArrayList<Removed>();
        seen.quantities().forEach((key, quantity) -> {
            if (!currentKeys.contains(key)) {
                removed.add(new Removed(key, quantity));
            }
        });
        removed.sort(Comparator.comparing(Removed::key));
        return new ManifestChanges(seen.label(), added, removed, changed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
    }

    public int size() {
        return added.size() + removed.size() + changed.size();
    }

    /** An entry the team acknowledged that the active manifest no longer has. */
    public record Removed(String key, long quantity) {
        static final StreamCodec<RegistryFriendlyByteBuf, Removed> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Removed::key,
                ByteBufCodecs.VAR_LONG, Removed::quantity,
                Removed::new);
    }

    /** An entry whose required quantity was raised or lowered. */
    public record Changed(String key, long from, long to) {
        static final StreamCodec<RegistryFriendlyByteBuf, Changed> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Changed::key,
                ByteBufCodecs.VAR_LONG, Changed::from,
                ByteBufCodecs.VAR_LONG, Changed::to,
                Changed::new);

        public boolean raised() {
            return to > from;
        }
    }
}
