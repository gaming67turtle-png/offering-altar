package dev.offeringaltar.gametest;

import dev.offeringaltar.ClaimData;
import dev.offeringaltar.OfferingAltar;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** Multiplayer: a real dedicated server with a client connecting over the network. */
public class AltarServerGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("offering-altar-gametest");
    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        // System messages as the client renders them, i.e. after translation on the client side
        List<String> received = new CopyOnWriteArrayList<>();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> received.add(message.getString()));
        Properties props = new Properties();
        props.setProperty("level-seed", "offering-altar-server");
        try (TestDedicatedServerContext server = context.worldBuilder().setUseConsistentSettings(false).createServer(props);
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            server.runCommand("op Player0");
            server.runCommand("gamemode survival @a");
            server.runCommand("time set noon");
            context.waitTicks(20);

            boolean altarOnClient = context.computeOnClient(mc -> mc.level != null
                    && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(OfferingAltar.MOD_ID, "offering_altar")));
            check(altarOnClient, "client joined the dedicated server with the altar block registered");

            BlockPos base = server.computeOnServer(s -> player(s).blockPosition().atY(220));
            BlockPos altar = base.west(2);
            server.runOnServer(s -> {
                ServerLevel level = s.overworld();
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    }
                }
                level.setBlockAndUpdate(altar, OfferingAltar.ALTAR.defaultBlockState());
                ServerPlayer p = player(s);
                p.getInventory().setItem(0, new ItemStack(Items.SNOWBALL, 16));
                p.getInventory().setSelectedSlot(0);
            });
            server.runCommand("tp Player0 " + (base.getX() + 0.5) + " " + base.getY() + " " + (base.getZ() + 0.5));
            context.waitTicks(20);
            connection.waitForChunksRender();

            // Grab the mouse with a throwaway click at the sky, then offer with the use key.
            context.getInput().lookAt(0f, -90f);
            context.waitTicks(2);
            context.getInput().pressMouse(1);
            context.waitTicks(5);
            context.getInput().lookAt(altar);
            context.waitTicks(3);
            context.getInput().pressKey(o -> o.keyUse);
            context.waitTicks(20);

            int claims = server.computeOnServer(s -> ClaimData.of(s.overworld()).fromAltar(altar.getX(), altar.getY(), altar.getZ()).size());
            check(claims == 1, "offering over the network registered a claim on the dedicated server");
            int left = server.computeOnServer(s -> player(s).getInventory().getItem(0).getCount());
            check(left == 0, "16 snowballs consumed on the server (left " + left + ")");
            boolean chatShown = received.stream().anyMatch(line -> line.contains("west") && line.contains("Snowy Plains"));
            LOGGER.info("[offering-altar-gametest] [server] client chat mentions the direction: {}", chatShown);
            check(chatShown, "client received the translated promise message");

            BlockPos far = base.west(400);
            String biome = server.computeOnServer(s -> {
                ServerLevel level = s.overworld();
                level.getChunk(far.getX() >> 4, far.getZ() >> 4);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, far.getX(), far.getZ());
                return level.getBiome(far.atY(y)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
            });
            check(biome.equals("minecraft:snowy_plains"), "dedicated server generated snowy plains west +400 (got " + biome + ")");

            server.runCommand("offering_altar reload");
            context.waitTicks(5);

            server.runCommand("gamemode creative @a");
            server.runCommand("tp Player0 " + far.getX() + " 200 " + far.getZ() + " 90 30");
            connection.waitForChunksRender();
            context.waitTicks(60);
            context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
            context.takeScreenshot("server_01_snowy_plains");
        }
        LOGGER.info("[offering-altar-gametest] [server] finished, failures: {}", failures);
        if (!failures.isEmpty()) throw new AssertionError("Dedicated server gametest failed: " + failures);
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
