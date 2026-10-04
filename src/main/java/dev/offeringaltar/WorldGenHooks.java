package dev.offeringaltar;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Called from worldgen worker threads; must not touch anything that isn't thread-safe. */
public final class WorldGenHooks {
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private WorldGenHooks() {
    }

    /** A bug here must never take world generation down with it; the chunk just generates as vanilla. */
    public static void safely(String what, ChunkAccess chunk, Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            if (WARNED.add("crash:" + what)) {
                OfferingAltar.LOGGER.error("Offering altar {} hook failed at chunk {}; leaving it vanilla", what, chunk.getPos(), e);
            }
        }
    }

    /** Runs right after vanilla filled the chunk's biomes, before terrain surface and features. */
    public static void overrideBiomes(ServerLevel level, ChunkAccess chunk) {
        ClaimData data = ClaimData.of(level);
        if (data == null || data.isEmpty()) return;
        ChunkPos pos = chunk.getPos();
        List<ClaimData.Claim> candidates = data.candidates(pos.x(), pos.z());
        if (candidates.isEmpty()) return;

        Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        @SuppressWarnings("unchecked")
        Holder<Biome>[] columns = new Holder[16];
        boolean any = false;
        // One decision per 4x4 biome cell, so wedge edges follow the true shape rather than chunk borders.
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                int x = pos.getMinBlockX() + qx * 4 + 2;
                int z = pos.getMinBlockZ() + qz * 4 + 2;
                ClaimData.Claim claim = firstContaining(candidates, x, z);
                if (claim == null) continue;
                OfferingConfig.Offering offering = OfferingAltar.config().byId(claim.offering());
                if (offering == null || offering.biome.isEmpty()) continue;
                Holder<Biome> biome = resolveBiome(biomes, offering.biome);
                if (biome == null) continue;
                columns[qx + qz * 4] = biome;
                any = true;
            }
        }
        if (!any) return;

        // Unforced cells keep vanilla's result. Read it from the section directly: the chunk isn't marked
        // as having biomes yet, so ChunkAccess.getNoiseBiome would throw here.
        chunk.fillBiomesFromNoise((qx, qy, qz) -> {
            Holder<Biome> forced = columns[(qx & 3) + (qz & 3) * 4];
            if (forced != null) return forced;
            return chunk.getSection(chunk.getSectionIndexFromSectionY(qy >> 2)).getNoiseBiome(qx & 3, qy & 3, qz & 3);
        });
    }

    /** Runs after vanilla features; sprinkles extra ore clusters into claimed stone. */
    public static void applyOreBonus(ServerLevel level, ChunkAccess chunk) {
        ClaimData data = ClaimData.of(level);
        if (data == null || data.isEmpty()) return;
        ChunkPos pos = chunk.getPos();
        ClaimData.Claim claim = data.find(pos.getMiddleBlockX(), pos.getMiddleBlockZ());
        if (claim == null) return;
        OfferingConfig.Offering offering = OfferingAltar.config().byId(claim.offering());
        if (offering == null || offering.ores.isEmpty()) return;

        RandomSource random = RandomSource.create(level.getSeed() ^ pos.pack() * 0x9E3779B97F4A7C15L);
        int chunkMinY = chunk.getMinY();
        int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (OfferingConfig.OreBonus bonus : offering.ores) {
            BlockState ore = resolveBlock(bonus.ore);
            BlockState deepslateOre = bonus.deepslateOre.isEmpty() ? ore : resolveBlock(bonus.deepslateOre);
            if (ore == null && deepslateOre == null) continue;
            int minY = Math.max(bonus.minY, chunkMinY);
            int maxY = Math.min(bonus.maxY, chunkMaxY);
            if (maxY < minY) continue;

            for (int i = 0; i < bonus.attempts; i++) {
                int x = pos.getMinBlockX() + random.nextInt(16);
                int z = pos.getMinBlockZ() + random.nextInt(16);
                int y = minY + random.nextInt(maxY - minY + 1);
                int size = 2 + random.nextInt(4);
                for (int n = 0; n < size; n++) {
                    cursor.set(x + random.nextInt(3) - 1, y + random.nextInt(3) - 1, z + random.nextInt(3) - 1);
                    if (cursor.getX() >> 4 != pos.x() || cursor.getZ() >> 4 != pos.z()) continue;
                    if (cursor.getY() < chunkMinY || cursor.getY() > chunkMaxY) continue;
                    BlockState existing = chunk.getBlockState(cursor);
                    if (ore != null && existing.is(BlockTags.STONE_ORE_REPLACEABLES)) {
                        chunk.setBlockState(cursor, ore);
                    } else if (deepslateOre != null && existing.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) {
                        chunk.setBlockState(cursor, deepslateOre);
                    }
                }
            }
        }
    }

    private static ClaimData.Claim firstContaining(List<ClaimData.Claim> newestFirst, int x, int z) {
        for (ClaimData.Claim c : newestFirst) {
            if (c.contains(x, z)) return c;
        }
        return null;
    }

    private static Holder<Biome> resolveBiome(Registry<Biome> biomes, String id) {
        Identifier parsed = Identifier.tryParse(id);
        Holder<Biome> holder = parsed == null ? null
                : biomes.get(ResourceKey.create(Registries.BIOME, parsed)).orElse(null);
        if (holder == null && WARNED.add("biome:" + id)) {
            OfferingAltar.LOGGER.warn("Offering refers to unknown biome '{}'", id);
        }
        return holder;
    }

    private static BlockState resolveBlock(String id) {
        Identifier parsed = Identifier.tryParse(id);
        Block block = parsed == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(parsed);
        if (block == Blocks.AIR) {
            if (WARNED.add("block:" + id)) OfferingAltar.LOGGER.warn("Offering refers to unknown block '{}'", id);
            return null;
        }
        return block.defaultBlockState();
    }
}
