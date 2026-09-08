package dev.nickyllo.betterbuild.forge;

import dev.nickyllo.betterbuild.core.design.ClaudeDesignProvider;
import dev.nickyllo.betterbuild.core.design.DesignProvider;
import dev.nickyllo.betterbuild.core.design.ProceduralDesignProvider;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Forge entry point for 1.20.1. Same wiring as the other loaders. */
@Mod(BetterBuildForge.MOD_ID)
public final class BetterBuildForge {

    public static final String MOD_ID = "betterbuild";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    private static final Duration DESIGN_TIMEOUT = Duration.ofSeconds(30);

    private static DesignProvider primary;
    private static DesignProvider fallback;

    public BetterBuildForge() {
        fallback = new ProceduralDesignProvider();
        primary = ClaudeDesignProvider.fromEnvironment(DESIGN_TIMEOUT)
                .map(p -> (DesignProvider) p)
                .orElseGet(() -> {
                    LOG.info("[{}] No Anthropic credentials found. Local repertoire only.", MOD_ID);
                    return fallback;
                });
        LOG.info("[{}] Ready. Designs come from: {}", MOD_ID, primary.name());
    }

    public static DesignProvider primaryProvider() {
        return primary;
    }

    public static DesignProvider fallbackProvider() {
        return fallback;
    }
}
