package dev.offeringaltar;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Comes to collect what the land is still owed. Ravager-level stats in a 1.5x humanoid body, steals one
 * valuable per hit (all returned on death) and reels in players who try to keep their distance.
 */
public class TitheCollector extends Monster {
    /** Items it will take from a player it hits; data-driven so packs can add their own valuables. */
    public static final TagKey<Item> VALUABLES = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath(OfferingAltar.MOD_ID, "tithe_valuables"));
    private static final int PULL_COOLDOWN = 160;
    private static final double PULL_MIN_DISTANCE = 5;
    private static final double PULL_MAX_DISTANCE = 18;

    private final List<ItemStack> collected = new ArrayList<>();
    private int pullCooldown = 60;

    public TitheCollector(EntityType<? extends Monster> type, Level level) {
        this(type, level, 50);
    }

    protected TitheCollector(EntityType<? extends Monster> type, Level level, int xpReward) {
        super(type, level);
        this.xpReward = xpReward;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 100.0)
                .add(Attributes.ATTACK_DAMAGE, 12.0)
                .add(Attributes.ATTACK_KNOCKBACK, 1.5)
                .add(Attributes.ARMOR, 8.0)
                .add(Attributes.ARMOR_TOUGHNESS, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.9)
                .add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, 0.9)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 40.0)
                .add(Attributes.STEP_HEIGHT, 1.0)
                .add(Attributes.SCALE, 1.5);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1, true));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 16f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        boolean hit = super.doHurtTarget(level, target);
        if (hit && target instanceof Player player && !player.hasInfiniteMaterials()) {
            takeTithe(level, player);
        }
        return hit;
    }

    private void takeTithe(ServerLevel level, Player player) {
        Inventory inventory = player.getInventory();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(h -> h.is(VALUABLES))) candidates.add(i);
        }
        if (candidates.isEmpty()) return;
        ItemStack taken = inventory.getItem(candidates.get(random.nextInt(candidates.size()))).split(1);
        collected.add(taken);
        level.playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 1f, 0.5f);
        player.sendOverlayMessage(Component.translatable("offering_altar.tithe.taken", getName(), taken.getHoverName())
                .withStyle(ChatFormatting.DARK_PURPLE));
    }

    /** Snapshot of what it is carrying, for tests and debugging. */
    public List<ItemStack> collected() {
        return List.copyOf(collected);
    }

    /** Only the full Collector reels players in; lesser kin just hit and steal. */
    protected boolean canPull() {
        return true;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (!canPull()) return;
        if (pullCooldown > 0) {
            pullCooldown--;
            return;
        }
        LivingEntity target = getTarget();
        if (!(target instanceof Player player) || !player.isAlive() || player.isSpectator() || player.isCreative()) return;
        double dist = distanceTo(player);
        if (dist < PULL_MIN_DISTANCE || dist > PULL_MAX_DISTANCE || !hasLineOfSight(player)) return;

        Vec3 toward = position().subtract(player.position()).normalize();
        player.setDeltaMovement(toward.x * 1.6, 0.55, toward.z * 1.6);
        player.syncVelocity = true;
        level.playSound(null, blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.2f, 0.6f);
        for (int i = 0; i <= 10; i++) {
            Vec3 p = player.position().add(position().subtract(player.position()).scale(i / 10.0));
            level.sendParticles(ParticleTypes.WITCH, p.x, p.y + 1.2, p.z, 3, 0.1, 0.1, 0.1, 0.0);
        }
        pullCooldown = PULL_COOLDOWN;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        for (ItemStack stack : collected) spawnAtLocation(level, stack);
        collected.clear();
    }

    /** Never vanish while carrying someone's valuables. */
    @Override
    public boolean removeWhenFarAway(double distanceSq) {
        return collected.isEmpty() && super.removeWhenFarAway(distanceSq);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store("Collected", ItemStack.CODEC.listOf(), List.copyOf(collected));
        output.putInt("PullCooldown", pullCooldown);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        collected.clear();
        input.read("Collected", ItemStack.CODEC.listOf()).ifPresent(collected::addAll);
        pullCooldown = input.getIntOr("PullCooldown", 60);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.RAVAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.RAVAGER_STEP, 0.6f, 0.8f);
    }

    @Override
    public float getVoicePitch() {
        return super.getVoicePitch() * 0.7f;
    }
}
