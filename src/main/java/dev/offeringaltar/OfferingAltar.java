package dev.offeringaltar;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OfferingAltar implements ModInitializer {
    public static final String MOD_ID = "offering_altar";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Read from worldgen threads, replaced by /offering_altar reload
    private static volatile OfferingConfig config;

    public static Block ALTAR;
    public static EntityType<TitheCollector> TITHE_COLLECTOR;
    public static EntityType<TitheGatherer> TITHE_GATHERER;

    public static OfferingConfig config() {
        return config;
    }

    @Override
    public void onInitialize() {
        config = OfferingConfig.load();

        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "offering_altar");
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        ALTAR = Registry.register(BuiltInRegistries.BLOCK, blockKey, new OfferingAltarBlock(BlockBehaviour.Properties.of()
                .setId(blockKey)
                .mapColor(MapColor.STONE)
                .strength(3.5f, 6f)
                .requiresCorrectToolForDrops()
                .sound(SoundType.DEEPSLATE_BRICKS)
                .lightLevel(s -> 7)));
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Item altarItem = Registry.register(BuiltInRegistries.ITEM, itemKey,
                new BlockItem(ALTAR, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));

        ResourceKey<CreativeModeTab> functional = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("functional_blocks"));
        CreativeModeTabEvents.modifyOutputEvent(functional).register(output -> output.accept(altarItem));

        TITHE_GATHERER = registerMob("tithe_gatherer", EntityType.Builder.<TitheGatherer>of(TitheGatherer::new, MobCategory.MONSTER),
                TitheGatherer.createAttributes());
        TITHE_COLLECTOR = registerMob("tithe_collector", EntityType.Builder.<TitheCollector>of(TitheCollector::new, MobCategory.MONSTER).fireImmune(),
                TitheCollector.createAttributes());

        ServerLevelEvents.LOAD.register((server, level) -> ClaimData.onLevelLoad(level));
        ServerLevelEvents.UNLOAD.register((server, level) -> ClaimData.onLevelUnload(level));
        ServerTickEvents.END_LEVEL_TICK.register(DangerSpawner::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> reportProblems(config, server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> DangerSpawner.reset());
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
                Commands.literal("offering_altar")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("reload").executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            OfferingConfig fresh = OfferingConfig.reload();
                            if (fresh == null) {
                                source.sendFailure(Component.translatable("offering_altar.reload.failed"));
                                return 0;
                            }
                            config = fresh;
                            int problems = reportProblems(fresh, source.getServer());
                            source.sendSuccess(() -> Component.translatable("offering_altar.reload.done",
                                    fresh.offerings.size(), problems), true);
                            return 1;
                        }))));

        LOGGER.info("Offering Altar ready with {} offerings", config.offerings.size());
    }

    /** Humanoid-sized monster with default attributes and a spawn egg in the spawn egg tab. */
    private static <T extends TitheCollector> EntityType<T> registerMob(String name, EntityType.Builder<T> builder,
                                                                        AttributeSupplier.Builder attributes) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, name));
        EntityType<T> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder
                .sized(0.6f, 1.95f)
                .eyeHeight(1.74f)
                .clientTrackingRange(10)
                .notInPeaceful()
                .build(key));
        FabricDefaultAttributeRegistry.register(type, attributes);
        ResourceKey<Item> eggKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(MOD_ID, name + "_spawn_egg"));
        Item egg = Registry.register(BuiltInRegistries.ITEM, eggKey,
                new SpawnEggItem(new Item.Properties().setId(eggKey).spawnEgg(type)));
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("spawn_eggs"))).register(output -> output.accept(egg));
        return type;
    }

    private static int reportProblems(OfferingConfig cfg, MinecraftServer server) {
        List<String> problems = cfg.validate(server.registryAccess());
        for (String p : problems) LOGGER.warn("config/offering_altar.json: {}", p);
        return problems.size();
    }
}
