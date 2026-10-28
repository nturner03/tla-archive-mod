package io.github.nturner03.tlaarchive;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(TlaArchive.MOD_ID)
public final class TlaArchive {
    public static final String MOD_ID = "tla_archive";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TlaArchive(IEventBus modEventBus) {
        LOGGER.info("The Last Archive loading");
    }
}
