package dev.offeringaltar.gametest;

import dev.offeringaltar.ClaimData;
import dev.offeringaltar.DangerSpawner;
import dev.offeringaltar.OfferingAltar;
import dev.offeringaltar.OfferingConfig;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.world.entity.monster.Monster;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Real client: place two altars, offer sand facing north and diamonds facing east by right-clicking,
 * then travel into the ungenerated land and check what grew there.
 */
public class AltarClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("offering-altar-gametest");
    private static final int GLFW_MOUSE_RIGHT = 1;
    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        BlockPos home;
        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> s.setSeed("offering-altar"))
                .create()) {
            save = sp.getWorldSave();
            TestServerContext server = sp.getServer();
            List<String> problems = server.computeOnServer(s -> OfferingAltar.config().validate(s.registryAccess()));
            check(problems.isEmpty(), "default config validates cleanly " + problems);
            sp.getConnection().waitForChunksRender();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            context.waitTicks(20);

            // A sky platform so terrain at spawn can't bury the altars or block the clicks.
            BlockPos base = server.computeOnServer(s -> player(s).blockPosition().atY(220));
            home = base;
            BlockPos north = base.north(2);
            BlockPos east = base.east(2);
            server.runOnServer(s -> {
                ServerLevel level = s.overworld();
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    }
                }
                level.setBlockAndUpdate(north, OfferingAltar.ALTAR.defaultBlockState());
                level.setBlockAndUpdate(east, OfferingAltar.ALTAR.defaultBlockState());
            });
            // Survival, so the offering is actually taken from the stack.
            server.runCommand("gamemode survival @a");
            server.runCommand("tp @p " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5));
            context.waitTicks(20);
            // The first click on an unfocused window only grabs the mouse; spend it on the sky.
            context.getInput().lookAt(0f, -90f);
            context.waitTicks(2);
            context.getInput().pressMouse(GLFW_MOUSE_RIGHT);
            context.waitTicks(5);

            offer(context, server, north, new ItemStack(Items.SAND, 64));
            int sandLeft = server.computeOnServer(s -> countItem(player(s), Items.SAND));
            check(sandLeft == 48, "16 sand consumed by the offering (left " + sandLeft + ")");
            offer(context, server, east, new ItemStack(Items.DIAMOND, 10));
            int northClaims = server.computeOnServer(s -> ClaimData.of(s.overworld()).fromAltar(north.getX(), north.getY(), north.getZ()).size());
            int eastClaims = server.computeOnServer(s -> ClaimData.of(s.overworld()).fromAltar(east.getX(), east.getY(), east.getZ()).size());
            check(northClaims == 1, "right-clicking with sand registered a claim at the north altar");
            check(eastClaims == 1, "right-clicking with diamonds registered a claim at the east altar");
            server.runCommand("gamemode creative @a");
            server.runCommand("tp @p " + (base.getX() - 2.5) + " " + (base.getY() + 1) + " " + (base.getZ() + 2.5) + " -150 35");
            server.runOnServer(s -> player(s).getInventory().clearContent());
            sp.getConnection().waitForChunksRender();
            context.waitTicks(100); // let the advancement toasts fade
            cleanScreen(context);
            context.takeScreenshot("altar_01_altars");

            // Sand, north wedge: every sample along the axis far beyond the loaded area must be desert.
            for (int d : new int[]{250, 350, 450}) {
                BlockPos at = base.north(d);
                String biome = server.computeOnServer(s -> biomeAt(s.overworld(), at));
                check(biome.equals("minecraft:desert"), "north +" + d + " is desert (got " + biome + ")");
            }
            // Outside the 90 degree wedge (and outside the east one) nothing is forced.
            BlockPos outside = base.offset(-350, 0, -60);
            LOGGER.info("[offering-altar-gametest] west, outside wedges: {}", (Object) server.computeOnServer(s -> biomeAt(s.overworld(), outside)));
            // Already generated land inside the wedge keeps its original biome.
            String nearBefore = server.computeOnServer(s -> biomeAt(s.overworld(), base.north(8)));
            LOGGER.info("[offering-altar-gametest] already generated land north +8 stays: {}", nearBefore);

            teleportSurface(server, base.getX(), base.getZ() - 400, 180, 25);
            sp.getConnection().waitForChunksRender();
            context.waitTicks(40);
            cleanScreen(context);
            context.takeScreenshot("altar_02_promised_desert");

            // Generating chunks that straddle the wedge edge used to crash worldgen; make sure it no longer does.
            teleportSurface(server, base.getX() + 330, base.getZ() - 330, 180, 30);
            sp.getConnection().waitForChunksRender();
            context.waitTicks(20);

            // Diamonds, east wedge: compare ore counts with an unclaimed chunk the same distance west.
            int claimed = server.computeOnServer(s -> diamondOres(s.overworld(), base.getX() + 400, base.getZ()));
            int unclaimed = server.computeOnServer(s -> diamondOres(s.overworld(), base.getX() - 400, base.getZ() + 400));
            LOGGER.info("[offering-altar-gametest] diamond ore: claimed chunk {} vs unclaimed {}", claimed, unclaimed);
            check(claimed > unclaimed + 8, "perilous land has far more diamond ore (" + claimed + " vs " + unclaimed + ")");
            String eastBiome = server.computeOnServer(s -> biomeAt(s.overworld(), base.east(400)));
            LOGGER.info("[offering-altar-gametest] east +400 keeps natural biome: {}", eastBiome);

            // Danger: a survival player standing on perilous land at night attracts tagged extra mobs.
            teleportSurface(server, base.getX() + 300, base.getZ(), 90, 0);
            server.runCommand("gamemode survival @a");
            server.runCommand("effect give @p minecraft:resistance infinite 4 true");
            server.runCommand("time set midnight");
            Set<UUID> dangerMobs = new HashSet<>();
            for (int i = 0; i < 12; i++) {
                context.waitTicks(100);
                dangerMobs.addAll(server.computeOnServer(s -> {
                    ServerPlayer p = player(s);
                    List<UUID> ids = new ArrayList<>();
                    for (Monster m : s.overworld().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(64),
                            m -> m.entityTags().contains(DangerSpawner.SPAWN_TAG))) {
                        ids.add(m.getUUID());
                    }
                    return ids;
                }));
            }
            check(!dangerMobs.isEmpty(), "perilous land spawned extra monsters at night (" + dangerMobs.size() + ")");
            boolean armoured = server.computeOnServer(s -> s.overworld().getEntitiesOfClass(Monster.class, player(s).getBoundingBox().inflate(64),
                    m -> m.entityTags().contains(DangerSpawner.SPAWN_TAG) && !m.entityTags().contains(DangerSpawner.GUARDIAN_TAG)).stream()
                    .allMatch(m -> m.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(h -> h.value() == Items.IRON_HELMET)));
            check(armoured, "tier 2 (diamond) danger mobs wear iron armour");
            server.runCommand("gamemode creative @a");
            server.runCommand("time set noon");

            // Reload: a broken file keeps the running config, a good one replaces it.
            Path configPath = OfferingConfig.path();
            String original = readOrNull(configPath);
            OfferingConfig before = OfferingAltar.config();
            writeQuietly(configPath, "{ this is not json");
            server.runCommand("offering_altar reload");
            context.waitTicks(2);
            check(OfferingAltar.config() == before, "reload with broken JSON keeps the previous config");
            writeQuietly(configPath, original);
            server.runCommand("offering_altar reload");
            context.waitTicks(2);
            check(OfferingAltar.config() != before && OfferingAltar.config().offerings.size() == before.offerings.size(),
                    "reload with valid JSON swaps in the new config");
        }

        // Reopen the save: promises must survive, and newly generated land must still follow them.
        BlockPos base = home;
        try (TestSingleplayerContext sp = save.open()) {
            TestServerContext server = sp.getServer();
            sp.getConnection().waitForChunksRender();
            int claims = server.computeOnServer(s -> ClaimData.of(s.overworld()).fromAltar(base.getX(), base.getY(), base.getZ() - 2).size()
                    + ClaimData.of(s.overworld()).fromAltar(base.getX() + 2, base.getY(), base.getZ()).size());
            check(claims == 2, "both promises survive saving and reopening the world (" + claims + ")");
            BlockPos fresh = base.offset(-200, 0, -460);
            String biome = server.computeOnServer(s -> biomeAt(s.overworld(), fresh));
            check(biome.equals("minecraft:desert"), "land generated after reopening still follows the sand promise (got " + biome + ")");
        }
        LOGGER.info("[offering-altar-gametest] finished, failures: {}", failures);
        if (!failures.isEmpty()) throw new AssertionError("Offering altar gametest failed: " + failures);
    }

    private void offer(ClientGameTestContext context, TestServerContext server, BlockPos altar, ItemStack stack) {
        server.runOnServer(s -> {
            ServerPlayer p = player(s);
            p.getInventory().setItem(0, stack);
            p.getInventory().setSelectedSlot(0);
        });
        context.waitTicks(5);
        context.getInput().lookAt(altar);
        context.waitTicks(3);
        String block = server.computeOnServer(s -> s.overworld().getBlockState(altar).toString());
        String aim = context.computeOnClient(mc -> mc.hitResult == null ? "none"
                : mc.hitResult.getType() + (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult b ? " " + b.getBlockPos() : ""));
        boolean grabbed = context.computeOnClient(mc -> mc.mouseHandler.isMouseGrabbed());
        LOGGER.info("[offering-altar-gametest] altar {} is {}, client aims at {}, mouse grabbed {}", altar, block, aim, grabbed);
        context.getInput().pressKey(o -> o.keyUse);
        context.waitTicks(10);
    }

    private static void teleportSurface(TestServerContext server, int x, int z, float yaw, float pitch) {
        int y = server.computeOnServer(s -> {
            ServerLevel level = s.overworld();
            level.getChunk(x >> 4, z >> 4);
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        });
        server.runCommand("tp @p " + (x + 0.5) + " " + (y + 2) + " " + (z + 0.5) + " " + yaw + " " + pitch);
    }

    private static String biomeAt(ServerLevel level, BlockPos pos) {
        level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        return level.getBiome(pos.atY(y)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
    }

    private static int diamondOres(ServerLevel level, int x, int z) {
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int y = chunk.getMinY(); y < chunk.getMinY() + chunk.getHeight(); y++) {
                    p.set(chunk.getPos().getMinBlockX() + bx, y, chunk.getPos().getMinBlockZ() + bz);
                    var state = chunk.getBlockState(p);
                    if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) count++;
                }
            }
        }
        return count;
    }

    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(h -> h.value() == item)) n += s.getCount();
        }
        return n;
    }

    private static void cleanScreen(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
        context.waitTicks(2);
    }

    private static String readOrNull(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeQuietly(Path path, String content) {
        try {
            if (content == null) Files.deleteIfExists(path);
            else Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
