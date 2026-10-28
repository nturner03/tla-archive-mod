package io.github.nturner03.tlaarchive.client;

import io.github.nturner03.tlaarchive.ModRegistries;
import io.github.nturner03.tlaarchive.TlaArchive;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only setup. FML never loads this class on a dedicated server. */
@Mod(value = TlaArchive.MOD_ID, dist = Dist.CLIENT)
public final class TlaArchiveClient {
    public TlaArchiveClient(IEventBus modEventBus) {
        modEventBus.addListener((RegisterMenuScreensEvent event) ->
                event.register(ModRegistries.CATALOG_MENU.get(), CatalogScreen::new));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientCatalog.clear());
    }
}
