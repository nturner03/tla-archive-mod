package io.github.nturner03.tlaarchive;

import appeng.api.AECapabilities;
import com.mojang.logging.LogUtils;
import io.github.nturner03.tlaarchive.manifest.ManifestLoader;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

@Mod(TlaArchive.MOD_ID)
public final class TlaArchive {
    public static final String MOD_ID = "tla_archive";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TlaArchive(IEventBus modEventBus) {
        ModRegistries.register(modEventBus);
        modEventBus.addListener(TlaArchive::registerCapabilities);
        NeoForge.EVENT_BUS.addListener(TlaArchive::addReloadListeners);
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
