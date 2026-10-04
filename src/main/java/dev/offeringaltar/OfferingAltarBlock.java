package dev.offeringaltar;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

public class OfferingAltarBlock extends Block {
    private static final String[] DIRECTIONS = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};

    public OfferingAltarBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;

        OfferingConfig config = OfferingAltar.config();
        if (!config.allowedDimensions.contains(server.dimension().identifier().toString())) {
            player.sendSystemMessage(Component.translatable("offering_altar.wrong_dimension").withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS_SERVER;
        }
        OfferingConfig.Offering offering = config.find(stack);
        if (offering == null) {
            player.sendSystemMessage(Component.translatable("offering_altar.rejected", stack.getHoverName()).withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS_SERVER;
        }
        int cost = Math.max(1, offering.cost);
        if (stack.getCount() < cost && !player.hasInfiniteMaterials()) {
            player.sendSystemMessage(Component.translatable("offering_altar.not_enough", cost, stack.getHoverName()).withStyle(ChatFormatting.GRAY));
            return InteractionResult.SUCCESS_SERVER;
        }
        ClaimData data = ClaimData.of(server);
        if (data == null) return InteractionResult.FAIL;

        float yaw = Math.round(Mth.wrapDegrees(player.getYRot()) / 45f) * 45f;
        int radius = Math.max(1, config.radiusChunks) * 16;
        data.add(new ClaimData.Claim(pos.getX(), pos.getY(), pos.getZ(), Mth.wrapDegrees(yaw),
                Mth.clamp(config.wedgeAngle, 1f, 360f) / 2f, radius, offering.id));
        stack.consume(cost, player);

        server.playSound(null, pos, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1f, 0.6f);
        server.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.6f, 1.4f);
        double dirX = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        double dirZ = Mth.cos(yaw * Mth.DEG_TO_RAD);
        for (int d = 1; d <= 16; d++) {
            server.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5 + dirX * d, pos.getY() + 1.2 + d * 0.05,
                    pos.getZ() + 0.5 + dirZ * d, 2, 0.1, 0.1, 0.1, 0.0);
        }
        server.sendParticles(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 40, 0.4, 0.3, 0.4, 0.5);

        player.sendSystemMessage(Component.translatable("offering_altar.claim",
                direction(yaw).withStyle(ChatFormatting.GOLD), offeringName(offering).withStyle(ChatFormatting.GREEN)));
        player.sendSystemMessage(Component.translatable("offering_altar.claim.hint", radius).withStyle(ChatFormatting.DARK_GRAY));
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        OfferingConfig config = OfferingAltar.config();

        if (player.isShiftKeyDown()) {
            player.sendSystemMessage(Component.translatable("offering_altar.accepts").withStyle(ChatFormatting.GOLD));
            for (OfferingConfig.Offering o : config.offerings) {
                if (o.items.isEmpty()) continue;
                player.sendSystemMessage(Component.translatable("offering_altar.accept_line",
                        itemName(o.items.getFirst()), Math.max(1, o.cost), offeringName(o).withStyle(ChatFormatting.GREEN)));
            }
            return InteractionResult.SUCCESS_SERVER;
        }

        ClaimData data = ClaimData.of(server);
        List<ClaimData.Claim> promises = data == null ? List.of() : data.fromAltar(pos.getX(), pos.getY(), pos.getZ());
        if (promises.isEmpty()) {
            player.sendSystemMessage(Component.translatable("offering_altar.none").withStyle(ChatFormatting.GRAY));
        } else {
            player.sendSystemMessage(Component.translatable("offering_altar.promises").withStyle(ChatFormatting.GOLD));
            for (ClaimData.Claim c : promises) {
                OfferingConfig.Offering o = config.byId(c.offering());
                MutableComponent name = o == null ? Component.literal(c.offering()) : offeringName(o);
                player.sendSystemMessage(Component.translatable("offering_altar.promise_line",
                        direction(c.yaw()).withStyle(ChatFormatting.GOLD), name.withStyle(ChatFormatting.GREEN)));
            }
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    static MutableComponent direction(float yaw) {
        int index = Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / 45f), 8);
        return Component.translatable("offering_altar.dir." + DIRECTIONS[index]);
    }

    static MutableComponent offeringName(OfferingConfig.Offering offering) {
        Identifier biome = offering.biome.isEmpty() ? null : Identifier.tryParse(offering.biome);
        if (biome != null) return Component.translatable("biome." + biome.getNamespace() + "." + biome.getPath());
        return Component.translatableWithFallback("offering_altar.offering." + offering.id, offering.id);
    }

    private static Component itemName(String entry) {
        if (entry.startsWith("#")) return Component.literal(entry);
        Identifier id = Identifier.tryParse(entry);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return Component.literal(entry);
        return new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getHoverName();
    }
}
