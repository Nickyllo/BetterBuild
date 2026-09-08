package dev.nickyllo.betterbuild.fabric;

import dev.nickyllo.betterbuild.core.design.ClaudeDesignProvider;
import dev.nickyllo.betterbuild.core.design.DesignProvider;
import dev.nickyllo.betterbuild.core.design.ProceduralDesignProvider;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Fabric entry point. Everything it does is wiring: the behaviour lives in the core.
 *
 * <p>Design providers are resolved once at startup. If no credentials are configured
 * the mod still loads and the Architect still works — he just builds from the local
 * repertoire instead of inventing new things.
 */
public final class BetterBuildFabric implements ModInitializer {

    public static final String MOD_ID = "betterbuild";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** Kept short: the Architect is walking to the site while this runs. */
    private static final Duration DESIGN_TIMEOUT = Duration.ofSeconds(30);

    private static DesignProvider primary;
    private static DesignProvider fallback;

    @Override
    public void onInitialize() {
        fallback = new ProceduralDesignProvider();
        primary = ClaudeDesignProvider.fromEnvironment(DESIGN_TIMEOUT)
                .map(p -> (DesignProvider) p)
                .orElseGet(() -> {
                    LOG.info("[{}] No Anthropic credentials found. The Architect will build "
                            + "from the local repertoire only.", MOD_ID);
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
