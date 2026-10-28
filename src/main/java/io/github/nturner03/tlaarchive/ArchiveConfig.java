package io.github.nturner03.tlaarchive;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Server config ({@code serverconfig/tla_archive-server.toml} in the world). Reconstruction is destructive and stays
 * behind {@link #RECONSTRUCTION_ENABLED}, off by default, until recovery after an interrupted extraction is proven
 * ({@code AGENTS.md} → <i>Rules</i>).
 */
public final class ArchiveConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue RECONSTRUCTION_ENABLED;
    public static final ModConfigSpec.IntValue DEBUG_KEYS_PER_TICK;
    public static final ModConfigSpec.IntValue DEBUG_HOLD_STAGED_TICKS;

    @Nullable
    private static volatile Boolean reconstructionOverride;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.comment("Reconstruction: the destructive final consumption of the manifest's stock.",
                "PROTOTYPE. Test worlds only: it permanently removes items from the AE2 network.")
                .push("reconstruction");
        RECONSTRUCTION_ENABLED = builder
                .comment("Test flag. Reconstruction is refused while this is false.")
                .define("enabled_for_testing", false);
        DEBUG_KEYS_PER_TICK = builder
                .comment("Debug: extract at most this many keys per tick. 0 extracts every key in the activation tick.",
                        "Only for proving recovery after a forced restart mid-extraction.")
                .defineInRange("debug_keys_per_tick", 0, 0, Integer.MAX_VALUE);
        DEBUG_HOLD_STAGED_TICKS = builder
                .comment("Debug: commit a fully staged operation no earlier than this many ticks after activation.",
                        "0 commits as soon as everything is staged.",
                        "Only for proving recovery after a forced restart between staging and commit.")
                .defineInRange("debug_hold_staged_ticks", 0, 0, 72_000);
        builder.pop();
        SPEC = builder.build();
    }

    private ArchiveConfig() {
    }

    /** The test flag. False until the server config has loaded. */
    public static boolean reconstructionEnabled() {
        var override = reconstructionOverride;
        if (override != null) {
            return override;
        }
        return SPEC.isLoaded() && RECONSTRUCTION_ENABLED.get();
    }

    /** Gametests set the flag without a config file; {@code null} goes back to the config value. */
    @VisibleForTesting
    public static void overrideReconstructionEnabled(@Nullable Boolean enabled) {
        reconstructionOverride = enabled;
    }

    public static int debugKeysPerTick() {
        return SPEC.isLoaded() ? DEBUG_KEYS_PER_TICK.get() : 0;
    }

    public static int debugHoldStagedTicks() {
        return SPEC.isLoaded() ? DEBUG_HOLD_STAGED_TICKS.get() : 0;
    }
}
