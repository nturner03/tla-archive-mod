package io.github.nturner03.tlaarchive;

import appeng.api.AECapabilities;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import io.github.nturner03.tlaarchive.catalog.CatalogPayloads;
import io.github.nturner03.tlaarchive.catalog.CatalogSync;
import io.github.nturner03.tlaarchive.manifest.ManifestLoader;
import io.github.nturner03.tlaarchive.reconstruction.Reconstruction;
import io.github.nturner03.tlaarchive.reconstruction.ReconstructionCommands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(TlaArchive.MOD_ID)
public final class TlaArchive {
    public static final String MOD_ID = "tla_archive";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TlaArchive(IEventBus modEventBus, ModContainer modContainer) {
        ModRegistries.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.SERVER, ArchiveConfig.SPEC);
        modEventBus.addListener(TlaArchive::registerCapabilities);
        modEventBus.addListener(CatalogPayloads::register);
        modEventBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(ModRegistries.ARCHIVE_CONTROLLER_ITEM.get());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) ->
                CatalogSync.forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(TlaArchive::addReloadListeners);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                ReconstructionCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> Reconstruction.recoverOnStart(event.getServer()));
        registerTeamListeners();
    }

    /**
     * Owner ruling 7e: a membership change of the owning team, or its deletion, aborts its running reconstruction.
     * {@code PLAYER_CHANGED} fires for every join, leave, kick and party creation, online or offline
     * ({@code docs/spikes/ftb-teams.md}).
     */
    private static void registerTeamListeners() {
        TeamEvent.PLAYER_CHANGED.register(event -> {
            Reconstruction.onTeamChanged(event.getTeam().getId(), "a member joined or left the team");
            event.getPreviousTeam().ifPresent(previous ->
                    Reconstruction.onTeamChanged(previous.getId(), "a member joined or left the team"));
        });
        TeamEvent.DELETED.register(event ->
                Reconstruction.onTeamChanged(event.getTeam().getId(), "the team was deleted"));
    }

    /** AE2 cables find grid node hosts through this capability, not by block entity type. */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                ModRegistries.ARCHIVE_CONTROLLER_BE.get(), (be, context) -> be);
    }

    private static void addReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ManifestLoader(event.getRegistryAccess()));
    }
}
