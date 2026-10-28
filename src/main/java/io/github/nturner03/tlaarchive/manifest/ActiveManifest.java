package io.github.nturner03.tlaarchive.manifest;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * The manifest the server is using, replaced on every datapack (re)load. Controllers compare {@link #generation()}
 * to notice a reload and restart their count.
 */
public final class ActiveManifest {
    @Nullable
    private static volatile ManifestMatcher current;
    private static volatile int generation;

    private ActiveManifest() {
    }

    /** The active manifest's matcher, or empty when no valid manifest loaded. */
    public static Optional<ManifestMatcher> matcher() {
        return Optional.ofNullable(current);
    }

    public static int generation() {
        return generation;
    }

    /** Stands in for a {@code /reload} with a changed manifest. Only for gametests that run in a batch of their own. */
    @VisibleForTesting
    public static void setForTesting(@Nullable ManifestMatcher matcher) {
        set(matcher);
    }

    static void set(@Nullable ManifestMatcher matcher) {
        current = matcher;
        generation++;
    }
}
