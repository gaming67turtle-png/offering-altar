package dev.offeringaltar;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-dimension list of promises made at altars. A claim is pure geometry (a wedge around the altar),
 * so it naturally only affects chunks whose biomes are generated after the offering was made.
 */
public final class ClaimData extends SavedData {
    public record Claim(int altarX, int altarY, int altarZ, float yaw, float halfAngle, int radius, String offering) {
        public static final Codec<Claim> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("altar_x").forGetter(Claim::altarX),
                Codec.INT.fieldOf("altar_y").forGetter(Claim::altarY),
                Codec.INT.fieldOf("altar_z").forGetter(Claim::altarZ),
                Codec.FLOAT.fieldOf("yaw").forGetter(Claim::yaw),
                Codec.FLOAT.fieldOf("half_angle").forGetter(Claim::halfAngle),
                Codec.INT.fieldOf("radius").forGetter(Claim::radius),
                Codec.STRING.fieldOf("offering").forGetter(Claim::offering)
        ).apply(i, Claim::new));

        public boolean contains(int x, int z) {
            double dx = x - altarX;
            double dz = z - altarZ;
            double distSq = dx * dx + dz * dz;
            if (distSq > (double) radius * radius) return false;
            if (distSq < 1) return true;
            // Minecraft yaw: 0 = +Z (south), 90 = -X (west)
            float pointYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            return Math.abs(Mth.wrapDegrees(pointYaw - yaw)) <= halfAngle;
        }

        /** Cheap reject for a whole chunk before testing individual columns. */
        public boolean mayTouchChunk(int chunkX, int chunkZ) {
            double dx = (chunkX << 4) + 8 - altarX;
            double dz = (chunkZ << 4) + 8 - altarZ;
            double reach = radius + 12;
            return dx * dx + dz * dz <= reach * reach;
        }
    }

    private static final Codec<ClaimData> CODEC = Claim.CODEC.listOf()
            .fieldOf("claims").codec()
            .xmap(ClaimData::new, d -> List.copyOf(d.claims));

    public static final SavedDataType<ClaimData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(OfferingAltar.MOD_ID, "claims"), ClaimData::new, CODEC, null);

    /** Worldgen runs off-thread, so it reads claims from here rather than through SavedDataStorage. */
    private static final Map<ResourceKey<Level>, ClaimData> LOADED = new ConcurrentHashMap<>();

    /** Oldest first; lookups walk backwards so the newest offering wins where wedges overlap. */
    private final List<Claim> claims = new CopyOnWriteArrayList<>();

    public ClaimData() {
    }

    private ClaimData(List<Claim> claims) {
        this.claims.addAll(claims);
    }

    public static void onLevelLoad(ServerLevel level) {
        LOADED.put(level.dimension(), level.getDataStorage().computeIfAbsent(TYPE));
    }

    public static void onLevelUnload(ServerLevel level) {
        LOADED.remove(level.dimension());
    }

    public static ClaimData of(ServerLevel level) {
        return LOADED.get(level.dimension());
    }

    public void add(Claim claim) {
        claims.add(claim);
        setDirty();
    }

    public boolean isEmpty() {
        return claims.isEmpty();
    }

    public Claim find(int x, int z) {
        for (int i = claims.size() - 1; i >= 0; i--) {
            Claim c = claims.get(i);
            if (c.contains(x, z)) return c;
        }
        return null;
    }

    /** Claims that could matter for this chunk, newest first. */
    public List<Claim> candidates(int chunkX, int chunkZ) {
        List<Claim> out = new ArrayList<>();
        for (int i = claims.size() - 1; i >= 0; i--) {
            Claim c = claims.get(i);
            if (c.mayTouchChunk(chunkX, chunkZ)) out.add(c);
        }
        return out;
    }

    public List<Claim> fromAltar(int x, int y, int z) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : claims) {
            if (c.altarX() == x && c.altarY() == y && c.altarZ() == z) out.add(c);
        }
        return out;
    }
}
