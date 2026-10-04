package dev.offeringaltar;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * The Collector's lesser kin, sent for diamond offerings: a bit tougher than a zombie, still steals one valuable
 * per hit (returned on death), but can't reel players in.
 */
public class TitheGatherer extends TitheCollector {
    public TitheGatherer(EntityType<? extends Monster> type, Level level) {
        super(type, level, 20);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.ARMOR, 2.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                .add(Attributes.STEP_HEIGHT, 1.0)
                .add(Attributes.SCALE, 1.1);
    }

    @Override
    protected boolean canPull() {
        return false;
    }

    @Override
    public float getVoicePitch() {
        // Higher than the Collector's growl
        return super.getVoicePitch() * 1.4f;
    }
}
