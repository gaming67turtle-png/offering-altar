package dev.offeringaltar;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Extra hostile spawns around players standing on land claimed with a dangerous offering. */
public final class DangerSpawner {
    private static final int INTERVAL = 100;
    private static final int MAX_NEARBY_MONSTERS = 10;

    private DangerSpawner() {
    }

    public static final String SPAWN_TAG = "offering_altar.danger";
    public static final String GUARDIAN_TAG = "offering_altar.guardian";
    private static final Map<String, Long> GUARDIAN_COOLDOWN = new HashMap<>();

    /** Cooldowns are in game time of one world; forget them when that server stops. */
    public static void reset() {
        GUARDIAN_COOLDOWN.clear();
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL != 0) return;
        if (level.getDifficulty() == Difficulty.PEACEFUL) return;
        if (!level.getGameRules().get(GameRules.SPAWN_MONSTERS)) return;
        ClaimData data = ClaimData.of(level);
        if (data == null || data.isEmpty()) return;
        RandomSource random = level.getRandom();

        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator()) continue;
            BlockPos at = player.blockPosition();
            ClaimData.Claim claim = data.find(at.getX(), at.getZ());
            if (claim == null) continue;
            OfferingConfig.Offering offering = OfferingAltar.config().byId(claim.offering());
            if (offering == null) continue;
            if (!offering.guardian.isEmpty()) tryGuardians(level, player, offering, random);
            if (offering.danger > 0) trySingleMob(level, player, offering, random);
        }
    }

    /** Tiered extra spawns: more often with each danger point, armoured from tier 2, in diamond from tier 3. */
    private static void trySingleMob(ServerLevel level, ServerPlayer player, OfferingConfig.Offering offering, RandomSource random) {
        if (random.nextFloat() >= Math.min(0.9f, offering.danger * 0.15f)) return;
        AABB around = player.getBoundingBox().inflate(32);
        if (level.getEntitiesOfClass(Monster.class, around).size() >= MAX_NEARBY_MONSTERS) return;

        List<String> mobs = offering.dangerMobs.isEmpty() ? OfferingAltar.config().dangerMobs : offering.dangerMobs;
        if (mobs.isEmpty()) return;
        EntityType<?> type = entityType(mobs.get(random.nextInt(mobs.size())));
        if (type == null) return;
        BlockPos spot = findSpot(level, player.blockPosition(), random);
        if (spot == null) return;
        Entity spawned = type.spawn(level, spot, EntitySpawnReason.EVENT);
        if (spawned == null) return;
        spawned.addTag(SPAWN_TAG);
        if (spawned instanceof Mob mob && offering.danger >= 2) {
            boolean diamond = offering.danger >= 3;
            equip(mob, EquipmentSlot.HEAD, diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET);
            equip(mob, EquipmentSlot.CHEST, diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE);
        }
    }

    private static void equip(Mob mob, EquipmentSlot slot, net.minecraft.world.item.Item item) {
        mob.setItemSlot(slot, new ItemStack(item));
        // Armour is part of the threat, not a reward to farm
        mob.setDropChance(slot, 0f);
    }

    /** A whole group at once, at most one group per player per cooldown and never while one is still around. */
    private static void tryGuardians(ServerLevel level, ServerPlayer player, OfferingConfig.Offering offering, RandomSource random) {
        long now = level.getGameTime();
        // Per player and per guardian kind, so meeting Collectors doesn't also hold back Gatherers
        String cooldownKey = player.getUUID() + "|" + offering.guardian;
        Long readyAt = GUARDIAN_COOLDOWN.get(cooldownKey);
        if (readyAt != null && now < readyAt) return;
        if (random.nextFloat() >= offering.guardianChance) return;
        EntityType<?> type = entityType(offering.guardian);
        if (type == null) return;
        AABB around = player.getBoundingBox().inflate(96);
        if (!level.getEntities(type, around, e -> e.entityTags().contains(GUARDIAN_TAG)).isEmpty()) return;

        BlockPos anchor = findSpot(level, player.blockPosition(), random);
        if (anchor == null) return;
        int spawnedCount = 0;
        for (int i = 0; i < Math.max(1, offering.guardianCount); i++) {
            BlockPos spot = i == 0 ? anchor : findNear(level, anchor, random);
            Entity spawned = type.spawn(level, spot == null ? anchor : spot, EntitySpawnReason.EVENT);
            if (spawned == null) continue;
            spawned.addTag(SPAWN_TAG);
            spawned.addTag(GUARDIAN_TAG);
            spawnedCount++;
        }
        if (spawnedCount == 0) return;
        GUARDIAN_COOLDOWN.put(cooldownKey, now + offering.guardianCooldownSeconds * 20L);
        level.playSound(null, anchor, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3f, 0.6f);
        player.sendSystemMessage(Component.translatable("offering_altar.tithe.arrived", spawnedCount, type.getDescription())
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    private static BlockPos findNear(ServerLevel level, BlockPos anchor, RandomSource random) {
        for (int attempt = 0; attempt < 8; attempt++) {
            BlockPos base = anchor.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3);
            for (int dy = -2; dy <= 2; dy++) {
                BlockPos feet = base.above(dy);
                if (isOpen(level, feet)) return feet;
            }
        }
        return null;
    }

    private static EntityType<?> entityType(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(parsed)) return null;
        return BuiltInRegistries.ENTITY_TYPE.getValue(parsed);
    }

    private static BlockPos findSpot(ServerLevel level, BlockPos around, RandomSource random) {
        for (int attempt = 0; attempt < 6; attempt++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            int dist = 12 + random.nextInt(13);
            int x = around.getX() + Mth.floor(Mth.cos(angle) * dist);
            int z = around.getZ() + Mth.floor(Mth.sin(angle) * dist);
            // Only where entities tick, otherwise the mob would just sit frozen in a border chunk.
            if (!level.isPositionEntityTicking(new BlockPos(x, around.getY(), z))) continue;

            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            // Underground players get company at their own depth, surface players on the surface.
            int startY = around.getY() < surface - 8 ? around.getY() : surface;
            for (int dy = 0; dy <= 6; dy++) {
                for (int sign : new int[]{1, -1}) {
                    BlockPos feet = new BlockPos(x, startY + dy * sign, z);
                    if (isOpen(level, feet) && level.getMaxLocalRawBrightness(feet) <= 7) return feet;
                }
            }
        }
        return null;
    }

    private static boolean isOpen(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty();
    }
}
