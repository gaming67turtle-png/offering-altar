package dev.offeringaltar.gametest;

import dev.offeringaltar.ClaimData;
import dev.offeringaltar.DangerSpawner;
import dev.offeringaltar.OfferingAltar;
import dev.offeringaltar.TitheCollector;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Netherite offering → a pack of three Tithe Collectors that hit like ravagers, steal, pull, and pay back. */
public class TitheCollectorGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("offering-altar-gametest");
    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> s.setSeed("tithe-collector"))
                .create()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            server.runCommand("time set noon");
            server.runCommand("difficulty normal");
            context.waitTicks(20);

            BlockPos base = server.computeOnServer(s -> player(s).blockPosition().atY(220));
            BlockPos altar = base.south(2);
            BlockPos diamondAltar = base.north(2);
            server.runOnServer(s -> {
                ServerLevel level = s.overworld();
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    }
                }
                level.setBlockAndUpdate(altar, OfferingAltar.ALTAR.defaultBlockState());
                level.setBlockAndUpdate(diamondAltar, OfferingAltar.ALTAR.defaultBlockState());
                ServerPlayer p = player(s);
                p.getInventory().setItem(0, new ItemStack(Items.NETHERITE_SCRAP, 4));
                p.getInventory().setSelectedSlot(0);
            });
            server.runCommand("gamemode survival @a");
            server.runCommand("tp @p " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5));
            context.waitTicks(20);
            context.getInput().lookAt(0f, -90f);
            context.waitTicks(2);
            context.getInput().pressMouse(1);
            context.waitTicks(5);
            context.getInput().lookAt(altar);
            context.waitTicks(3);
            context.getInput().pressKey(o -> o.keyUse);
            context.waitTicks(10);

            int claims = server.computeOnServer(s -> ClaimData.of(s.overworld()).fromAltar(altar.getX(), altar.getY(), altar.getZ()).size());
            check(claims == 1, "netherite scrap offering registered the ancient vault to the south");
            int scrapLeft = server.computeOnServer(s -> player(s).getInventory().getItem(0).getCount());
            check(scrapLeft == 2, "2 netherite scrap consumed (left " + scrapLeft + ")");

            server.runOnServer(s -> player(s).getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3)));
            context.waitTicks(3);
            context.getInput().lookAt(diamondAltar);
            context.waitTicks(3);
            context.getInput().pressKey(o -> o.keyUse);
            context.waitTicks(10);
            int diamondClaims = server.computeOnServer(s -> ClaimData.of(s.overworld())
                    .fromAltar(diamondAltar.getX(), diamondAltar.getY(), diamondAltar.getZ()).size());
            check(diamondClaims == 1, "diamond offering registered the perilous veins to the north");

            // Altar drops itself when mined with a pickaxe (checks the 26.3-format loot table)
            server.runOnServer(s -> {
                ServerPlayer p = player(s);
                p.getInventory().setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
                p.getInventory().setSelectedSlot(1);
                p.gameMode.destroyBlock(altar);
            });
            context.waitTicks(5);
            int altarDrops = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(altar).inflate(3),
                    e -> e.getItem().is(h -> h.value() == OfferingAltar.ALTAR.asItem())).size());
            check(altarDrops == 1, "mined altar drops itself");

            // Make the pack turn up on the first check so the test doesn't wait minutes.
            OfferingAltar.config().byId("ancient_vault").guardianChance = 1f;
            server.runOnServer(s -> {
                ServerPlayer p = player(s);
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
            });
            server.runCommand("attribute @p minecraft:max_health base set 1024");
            server.runCommand("effect give @p minecraft:instant_health 1 20 true");
            server.runCommand("time set midnight");
            teleportSurface(server, base.getX(), base.getZ() + 300);
            sp.getConnection().waitForChunksRender();

            int pack = 0;
            for (int i = 0; i < 20 && pack < 3; i++) {
                context.waitTicks(20);
                pack = server.computeOnServer(s -> collectors(s).size());
            }
            check(pack == 3, "three Tithe Collectors arrived together (" + pack + ")");

            String stats = server.computeOnServer(s -> {
                if (collectors(s).isEmpty()) return "none";
                TitheCollector c = collectors(s).getFirst();
                return c.getMaxHealth() + "/" + c.getAttributeValue(Attributes.ATTACK_DAMAGE) + "/" + c.getAttributeValue(Attributes.SCALE)
                        + "/" + c.getAttributeValue(Attributes.ARMOR);
            });
            LOGGER.info("[offering-altar-gametest] collector health/attack/scale/armor: {}", stats);
            check(stats.equals("100.0/12.0/1.5/8.0"), "ravager-level stats " + stats);

            // Screenshot the pack from a safe vantage point, then let them come for the diamonds.
            server.runCommand("gamemode creative @a");
            BlockPos packAt = server.computeOnServer(s -> collectors(s).getFirst().blockPosition());
            server.runCommand("tp @p " + (packAt.getX() + 0.5) + " " + (packAt.getY() + 2) + " " + (packAt.getZ() + 7.5) + " 180 15");
            server.runCommand("effect give @p minecraft:night_vision 30 0 true");
            context.waitTicks(30);
            context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            context.takeScreenshot("tithe_collectors_pack");
            server.runCommand("effect clear @p minecraft:night_vision");
            server.runCommand("gamemode survival @a");

            int diamondsLeft = 5;
            for (int i = 0; i < 30 && diamondsLeft == 5; i++) {
                context.waitTicks(20);
                server.runCommand("effect give @p minecraft:instant_health 1 20 true");
                diamondsLeft = server.computeOnServer(s -> player(s).getInventory().countItem(Items.DIAMOND));
            }
            int stolen = server.computeOnServer(s -> collectors(s).stream().mapToInt(c -> c.collected().stream().mapToInt(ItemStack::getCount).sum()).sum());
            check(diamondsLeft < 5 && stolen == 5 - diamondsLeft, "a hit took diamonds and the collector carries them (" + stolen + " taken)");
            boolean alive = server.computeOnServer(s -> player(s).isAlive());
            check(alive, "player survived the test (sanity)");

            // Pull: one collector, player standing 10 blocks away in its line of sight.
            // On a flat sky strip so nothing blocks line of sight; measured on the client, which owns player movement.
            server.runOnServer(s -> collectors(s).forEach(c -> c.discard()));
            BlockPos strip = base.offset(0, 20, 40);
            server.runOnServer(s -> {
                for (int dx = -4; dx <= 16; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        s.overworld().setBlockAndUpdate(strip.offset(dx, -1, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    }
                }
            });
            server.runCommand("tp @p " + (strip.getX() + 0.5) + " " + strip.getY() + " " + (strip.getZ() + 0.5) + " -90 0");
            context.waitTicks(10);
            server.runCommand("summon offering_altar:tithe_collector " + (strip.getX() + 12.5) + " " + strip.getY() + " " + (strip.getZ() + 0.5)
                    + " {PullCooldown:0}");
            context.waitTicks(2);
            server.runOnServer(s -> s.overworld().getEntitiesOfClass(TitheCollector.class, player(s).getBoundingBox().inflate(32))
                    .forEach(c -> c.setTarget(player(s))));
            double startX = context.computeOnClient(mc -> mc.player.getX());
            double furthest = startX;
            for (int i = 0; i < 40; i++) {
                context.waitTicks(1);
                furthest = Math.max(furthest, context.computeOnClient(mc -> mc.player.getX()));
            }
            check(furthest - startX > 2.5, "collector yanked the player toward itself (moved " + String.format("%.1f", furthest - startX) + " blocks)");

            // Death returns everything it took (stolen ones were discarded above, so steal again first).
            server.runOnServer(s -> {
                TitheCollector c = s.overworld().getEntitiesOfClass(TitheCollector.class, player(s).getBoundingBox().inflate(32)).getFirst();
                player(s).getInventory().clearContent();
                player(s).getInventory().setItem(0, new ItemStack(Items.EMERALD, 3));
                c.teleportTo(player(s).getX() + 1, player(s).getY(), player(s).getZ());
                c.doHurtTarget(s.overworld(), player(s));
            });
            int carried = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(TitheCollector.class, player(s).getBoundingBox().inflate(32))
                    .getFirst().collected().size());
            BlockPos deathAt = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(TitheCollector.class, player(s).getBoundingBox().inflate(32))
                    .getFirst().blockPosition());
            server.runCommand("kill @e[type=offering_altar:tithe_collector]");
            context.waitTicks(5);
            int emeraldsDropped = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(deathAt).inflate(6),
                    e -> e.getItem().is(h -> h.value() == Items.EMERALD)).stream().mapToInt(e -> e.getItem().getCount()).sum());
            check(carried == 1 && emeraldsDropped == 1, "stolen emerald dropped on death (carried " + carried + ", dropped " + emeraldsDropped + ")");

            // Diamonds (tier 2): two Tithe Gatherers, weaker kin that steal but don't pull.
            OfferingAltar.config().byId("perilous_veins").guardianChance = 1f;
            server.runCommand("kill @e[type=item]");
            server.runCommand("gamemode survival @a");
            server.runCommand("time set midnight");
            server.runCommand("effect give @p minecraft:instant_health 1 20 true");
            teleportSurface(server, base.getX(), base.getZ() - 300);
            sp.getConnection().waitForChunksRender();
            int gatherers = 0;
            for (int i = 0; i < 20 && gatherers < 2; i++) {
                context.waitTicks(20);
                gatherers = server.computeOnServer(s -> guardians(s, OfferingAltar.TITHE_GATHERER).size());
            }
            check(gatherers == 2, "two Tithe Gatherers arrived on diamond land (" + gatherers + ")");
            int collectorsThere = server.computeOnServer(s -> collectors(s).size());
            check(collectorsThere == 0, "no Tithe Collectors on diamond land");
            String gatherStats = server.computeOnServer(s -> {
                List<TitheCollector> g = guardians(s, OfferingAltar.TITHE_GATHERER);
                if (g.isEmpty()) return "none";
                TitheCollector c = g.getFirst();
                return c.getMaxHealth() + "/" + c.getAttributeValue(Attributes.ATTACK_DAMAGE) + "/" + c.getAttributeValue(Attributes.SCALE)
                        + "/" + c.getAttributeValue(Attributes.ARMOR);
            });
            check(gatherStats.equals("30.0/5.0/1.1/2.0"), "gatherer stats health/attack/scale/armor " + gatherStats);

            if (gatherers > 0) {
                server.runCommand("gamemode creative @a");
                BlockPos gatherAt = server.computeOnServer(s -> guardians(s, OfferingAltar.TITHE_GATHERER).getFirst().blockPosition());
                server.runCommand("tp @p " + (gatherAt.getX() + 0.5) + " " + (gatherAt.getY() + 2) + " " + (gatherAt.getZ() + 6.5) + " 180 15");
                server.runCommand("effect give @p minecraft:night_vision 30 0 true");
                context.waitTicks(30);
                context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
                context.takeScreenshot("tithe_gatherers_pair");
                server.runCommand("effect clear @p minecraft:night_vision");
                server.runCommand("gamemode survival @a");

                server.runOnServer(s -> {
                    TitheCollector g = guardians(s, OfferingAltar.TITHE_GATHERER).getFirst();
                    player(s).getInventory().clearContent();
                    player(s).getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 4));
                    g.teleportTo(player(s).getX() + 1, player(s).getY(), player(s).getZ());
                    g.doHurtTarget(s.overworld(), player(s));
                });
                int gatherStole = server.computeOnServer(s -> guardians(s, OfferingAltar.TITHE_GATHERER).stream()
                        .mapToInt(c -> c.collected().size()).sum());
                check(gatherStole == 1, "gatherer also steals a valuable on hit");
            }

            // No pull: same sky strip setup as the Collector, the player must stay put.
            server.runOnServer(s -> s.overworld().getEntitiesOfClass(TitheCollector.class, player(s).getBoundingBox().inflate(96))
                    .forEach(c -> c.discard()));
            server.runCommand("tp @p " + (strip.getX() + 0.5) + " " + strip.getY() + " " + (strip.getZ() + 0.5) + " -90 0");
            context.waitTicks(10);
            server.runCommand("summon offering_altar:tithe_gatherer " + (strip.getX() + 12.5) + " " + strip.getY() + " " + (strip.getZ() + 0.5)
                    + " {PullCooldown:0}");
            context.waitTicks(2);
            server.runOnServer(s -> s.overworld().getEntitiesOfClass(TitheCollector.class, player(s).getBoundingBox().inflate(32))
                    .forEach(c -> c.setTarget(player(s))));
            double gStartX = context.computeOnClient(mc -> mc.player.getX());
            double gFurthest = gStartX;
            for (int i = 0; i < 40; i++) {
                context.waitTicks(1);
                gFurthest = Math.max(gFurthest, context.computeOnClient(mc -> mc.player.getX()));
            }
            check(gFurthest - gStartX < 1.0, "gatherer does not pull (player moved " + String.format("%.1f", gFurthest - gStartX) + " blocks)");
        }
        LOGGER.info("[offering-altar-gametest] [collector] finished, failures: {}", failures);
        if (!failures.isEmpty()) throw new AssertionError("Tithe collector gametest failed: " + failures);
    }

    private static List<TitheCollector> collectors(MinecraftServer s) {
        return guardians(s, OfferingAltar.TITHE_COLLECTOR);
    }

    private static List<TitheCollector> guardians(MinecraftServer s, net.minecraft.world.entity.EntityType<?> type) {
        return s.overworld().getEntitiesOfClass(TitheCollector.class, player(s).getBoundingBox().inflate(96),
                c -> c.getType() == type && c.entityTags().contains(DangerSpawner.GUARDIAN_TAG));
    }

    private static void teleportSurface(TestServerContext server, int x, int z) {
        int y = server.computeOnServer(s -> {
            ServerLevel level = s.overworld();
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        });
        server.runCommand("tp @p " + (x + 0.5) + " " + y + " " + (z + 0.5));
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private void check(boolean ok, String what) {
        if (ok) {
            LOGGER.info("[offering-altar-gametest] PASS {}", what);
        } else {
            LOGGER.error("[offering-altar-gametest] FAIL {}", what);
            failures.add(what);
        }
    }
}
