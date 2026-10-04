package dev.offeringaltar.mixin;

import dev.offeringaltar.WorldGenHooks;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
    @Inject(method = "generateBiomes", at = @At("RETURN"), cancellable = true)
    private static void offeringAltar$applyClaimedBiomes(WorldGenContext context, ChunkStep step,
                                                         StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                         CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        cir.setReturnValue(cir.getReturnValue().thenApply(generated -> {
            WorldGenHooks.safely("biomes", generated, () -> WorldGenHooks.overrideBiomes(context.level(), generated));
            return generated;
        }));
    }

    @Inject(method = "generateFeatures", at = @At("RETURN"), cancellable = true)
    private static void offeringAltar$applyOreBonus(WorldGenContext context, ChunkStep step,
                                                    StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        cir.setReturnValue(cir.getReturnValue().thenApply(generated -> {
            WorldGenHooks.safely("ores", generated, () -> WorldGenHooks.applyOreBonus(context.level(), generated));
            return generated;
        }));
    }
}
