package dev.offeringaltar.client;

import dev.offeringaltar.OfferingAltar;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class OfferingAltarClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Made accessible by Fabric API's transitive access wideners
        EntityRenderers.register(OfferingAltar.TITHE_COLLECTOR, ctx -> new TitheCollectorRenderer<>(ctx, "tithe_collector"));
        EntityRenderers.register(OfferingAltar.TITHE_GATHERER, ctx -> new TitheCollectorRenderer<>(ctx, "tithe_gatherer"));
    }
}
