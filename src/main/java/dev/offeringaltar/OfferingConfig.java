package dev.offeringaltar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * config/offering_altar.json. Offerings are matched top to bottom, first match wins.
 * Items may be plain ids ("minecraft:sand") or item tags ("#minecraft:saplings").
 */
public final class OfferingConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    @SerializedName("radius_chunks")
    public int radiusChunks = 32;
    /** Full width of the claimed wedge in degrees, centred on where the player faces. */
    @SerializedName("wedge_angle")
    public float wedgeAngle = 90f;
    @SerializedName("allowed_dimensions")
    public List<String> allowedDimensions = new ArrayList<>(List.of("minecraft:overworld"));
    /** Mobs used by offerings with danger > 0 unless the offering lists its own. */
    @SerializedName("danger_mobs")
    public List<String> dangerMobs = new ArrayList<>(List.of(
            "minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper", "minecraft:witch"));
    public List<Offering> offerings = new ArrayList<>();

    public static final class Offering {
        public String id = "";
        public List<String> items = new ArrayList<>();
        public int cost = 1;
        /** Biome forced on the claimed land; empty keeps whatever would have generated. */
        public String biome = "";
        /** 0 = safe. Each point adds extra hostile spawns around players standing on the land. */
        public int danger = 0;
        @SerializedName("danger_mobs")
        public List<String> dangerMobs = new ArrayList<>();
        /** Optional champion that turns up as a group, e.g. offering_altar:tithe_collector. Empty = none. */
        public String guardian = "";
        @SerializedName("guardian_count")
        public int guardianCount = 3;
        /** Chance per 5-second check while a player stands on the land in the dark. */
        @SerializedName("guardian_chance")
        public float guardianChance = 0.05f;
        /** Minimum time between two groups for the same player. */
        @SerializedName("guardian_cooldown_seconds")
        public int guardianCooldownSeconds = 300;
        public List<OreBonus> ores = new ArrayList<>();

        public boolean matches(ItemStack stack) {
            Holder<Item> item = stack.typeHolder();
            for (String s : items) {
                if (s.startsWith("#")) {
                    Identifier tag = Identifier.tryParse(s.substring(1));
                    if (tag != null && item.is(TagKey.create(Registries.ITEM, tag))) return true;
                } else {
                    Identifier id = Identifier.tryParse(s);
                    if (id != null && item.is(id)) return true;
                }
            }
            return false;
        }
    }

    public static final class OreBonus {
        public String ore = "";
        @SerializedName("deepslate_ore")
        public String deepslateOre = "";
        /** Placement attempts per chunk; each successful attempt makes a small cluster. */
        public int attempts = 8;
        @SerializedName("min_y")
        public int minY = -64;
        @SerializedName("max_y")
        public int maxY = 64;

        static OreBonus of(String ore, String deepslate, int attempts, int minY, int maxY) {
            OreBonus b = new OreBonus();
            b.ore = ore;
            b.deepslateOre = deepslate;
            b.attempts = attempts;
            b.minY = minY;
            b.maxY = maxY;
            return b;
        }
    }

    public Offering find(ItemStack stack) {
        for (Offering o : offerings) {
            if (o.matches(stack)) return o;
        }
        return null;
    }

    public Offering byId(String id) {
        for (Offering o : offerings) {
            if (o.id.equals(id)) return o;
        }
        return null;
    }

    /**
     * Problems an admin should fix: unknown ids, bad tags, duplicate offering ids. Needs the server's
     * registries because biomes and tags only exist once a world is loaded.
     */
    public List<String> validate(RegistryAccess registries) {
        List<String> problems = new ArrayList<>();
        HolderLookup.RegistryLookup<Item> itemLookup = registries.lookupOrThrow(Registries.ITEM);
        HolderLookup.RegistryLookup<Biome> biomeLookup = registries.lookupOrThrow(Registries.BIOME);
        Set<String> seen = new HashSet<>();
        for (String dim : allowedDimensions) {
            if (Identifier.tryParse(dim) == null) problems.add("allowed_dimensions: '" + dim + "' is not a valid id");
        }
        for (String mob : dangerMobs) checkEntity(problems, "danger_mobs", mob);
        for (Offering o : offerings) {
            String where = "offering '" + o.id + "'";
            if (o.id.isEmpty()) problems.add("an offering has no id");
            else if (!seen.add(o.id)) problems.add(where + ": duplicate id, only the first one is used");
            if (o.items.isEmpty()) problems.add(where + ": no items");
            if (o.cost < 1) problems.add(where + ": cost must be at least 1");
            for (String s : o.items) {
                if (s.startsWith("#")) {
                    Identifier tag = Identifier.tryParse(s.substring(1));
                    if (tag == null || itemLookup.get(TagKey.create(Registries.ITEM, tag)).isEmpty()) {
                        problems.add(where + ": unknown item tag '" + s + "'");
                    }
                } else {
                    Identifier id = Identifier.tryParse(s);
                    if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) problems.add(where + ": unknown item '" + s + "'");
                }
            }
            if (!o.biome.isEmpty()) {
                Identifier biome = Identifier.tryParse(o.biome);
                if (biome == null || biomeLookup.get(ResourceKey.create(Registries.BIOME, biome)).isEmpty()) {
                    problems.add(where + ": unknown biome '" + o.biome + "'");
                }
            }
            for (String mob : o.dangerMobs) checkEntity(problems, where + " danger_mobs", mob);
            if (!o.guardian.isEmpty()) {
                checkEntity(problems, where + " guardian", o.guardian);
                if (o.guardianCount < 1) problems.add(where + ": guardian_count must be at least 1");
            }
            for (OreBonus ore : o.ores) {
                checkBlock(problems, where, ore.ore);
                if (!ore.deepslateOre.isEmpty()) checkBlock(problems, where, ore.deepslateOre);
                if (ore.maxY < ore.minY) problems.add(where + ": ore " + ore.ore + " has max_y below min_y");
            }
        }
        return problems;
    }

    private static void checkBlock(List<String> problems, String where, String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !BuiltInRegistries.BLOCK.containsKey(parsed)) problems.add(where + ": unknown block '" + id + "'");
    }

    private static void checkEntity(List<String> problems, String where, String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(parsed)) problems.add(where + ": unknown entity '" + id + "'");
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("offering_altar.json");
    }

    /** Hand-edited JSON may say null where a list or string belongs; treat that as empty. */
    private OfferingConfig normalize() {
        if (allowedDimensions == null) allowedDimensions = new ArrayList<>();
        if (dangerMobs == null) dangerMobs = new ArrayList<>();
        if (offerings == null) offerings = new ArrayList<>();
        offerings.removeIf(o -> o == null);
        for (Offering o : offerings) {
            if (o.id == null) o.id = "";
            if (o.items == null) o.items = new ArrayList<>();
            if (o.biome == null) o.biome = "";
            if (o.dangerMobs == null) o.dangerMobs = new ArrayList<>();
            if (o.guardian == null) o.guardian = "";
            if (o.ores == null) o.ores = new ArrayList<>();
            o.ores.removeIf(b -> b == null || b.ore == null);
            for (OreBonus b : o.ores) {
                if (b.deepslateOre == null) b.deepslateOre = "";
            }
        }
        return this;
    }

    /** Re-reads the file; null if it is missing or not valid JSON, so the caller can keep the old config. */
    public static OfferingConfig reload() {
        Path path = path();
        if (!Files.exists(path)) return null;
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            OfferingConfig cfg = GSON.fromJson(r, OfferingConfig.class);
            return cfg == null ? null : cfg.normalize();
        } catch (Exception e) {
            OfferingAltar.LOGGER.error("Could not read {}", path, e);
            return null;
        }
    }

    public static OfferingConfig load() {
        Path path = path();
        if (Files.exists(path)) {
            OfferingConfig cfg = reload();
            if (cfg != null) return cfg;
            // Don't overwrite a file the admin is still editing; just run on defaults this time.
            OfferingAltar.LOGGER.error("Using default offerings until {} is fixed", path);
            return defaults();
        }
        OfferingConfig cfg = defaults();
        try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(cfg, w);
        } catch (IOException e) {
            OfferingAltar.LOGGER.warn("Could not write default config to {}", path, e);
        }
        return cfg;
    }

    private static Offering biome(String id, int cost, String biome, String... items) {
        Offering o = new Offering();
        o.id = id;
        o.cost = cost;
        o.biome = biome;
        o.items = new ArrayList<>(List.of(items));
        return o;
    }

    static OfferingConfig defaults() {
        OfferingConfig c = new OfferingConfig();
        List<Offering> l = c.offerings;
        l.add(biome("desert", 16, "minecraft:desert", "minecraft:sand", "minecraft:sandstone"));

        Offering badlands = biome("badlands", 16, "minecraft:badlands", "minecraft:red_sand", "minecraft:terracotta");
        badlands.ores.add(OreBonus.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore", 10, -64, 120));
        badlands.danger = 1;
        l.add(badlands);

        l.add(biome("forest", 8, "minecraft:forest", "minecraft:oak_sapling"));
        l.add(biome("birch_forest", 8, "minecraft:birch_forest", "minecraft:birch_sapling"));
        l.add(biome("taiga", 8, "minecraft:taiga", "minecraft:spruce_sapling"));
        l.add(biome("jungle", 8, "minecraft:jungle", "minecraft:jungle_sapling"));
        l.add(biome("savanna", 8, "minecraft:savanna", "minecraft:acacia_sapling"));
        l.add(biome("dark_forest", 8, "minecraft:dark_forest", "minecraft:dark_oak_sapling"));
        l.add(biome("cherry_grove", 8, "minecraft:cherry_grove", "minecraft:cherry_sapling"));
        l.add(biome("pale_garden", 8, "minecraft:pale_garden", "minecraft:pale_oak_sapling"));
        l.add(biome("mangrove_swamp", 8, "minecraft:mangrove_swamp", "minecraft:mangrove_propagule"));
        l.add(biome("bamboo_jungle", 16, "minecraft:bamboo_jungle", "minecraft:bamboo"));
        l.add(biome("sunflower_plains", 4, "minecraft:sunflower_plains", "minecraft:sunflower"));
        l.add(biome("flower_forest", 16, "minecraft:flower_forest", "#minecraft:small_flowers"));
        l.add(biome("plains", 16, "minecraft:plains", "minecraft:wheat_seeds"));
        l.add(biome("meadow", 8, "minecraft:meadow", "minecraft:honeycomb"));
        l.add(biome("swamp", 8, "minecraft:swamp", "minecraft:lily_pad", "minecraft:slime_ball"));
        l.add(biome("mushroom_fields", 16, "minecraft:mushroom_fields", "minecraft:red_mushroom", "minecraft:brown_mushroom"));
        l.add(biome("snowy_plains", 16, "minecraft:snowy_plains", "minecraft:snowball", "minecraft:snow_block"));
        l.add(biome("ice_spikes", 8, "minecraft:ice_spikes", "minecraft:packed_ice", "minecraft:blue_ice"));

        Offering emerald = biome("emerald_peaks", 4, "minecraft:windswept_hills", "minecraft:emerald");
        emerald.ores.add(OreBonus.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", 12, -16, 200));
        emerald.danger = 1;
        l.add(emerald);

        // The better the offering, the higher the price: tier 1 above, tier 2 diamonds, tier 3 netherite.
        // Diamonds keep the natural terrain but fill it with ore and with armoured things that want it.
        Offering perilous = biome("perilous_veins", 3, "", "minecraft:diamond");
        perilous.danger = 2;
        perilous.guardian = OfferingAltar.MOD_ID + ":tithe_gatherer";
        perilous.guardianCount = 2;
        perilous.guardianCooldownSeconds = 240;
        perilous.ores.add(OreBonus.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", 24, -64, 16));
        perilous.ores.add(OreBonus.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore", 10, -64, 32));
        perilous.ores.add(OreBonus.of("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", 10, -64, 16));
        perilous.ores.add(OreBonus.of("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore", 8, -64, 64));
        perilous.ores.add(OreBonus.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore", 16, -64, 128));
        l.add(perilous);

        // Netherite: ancient debris in the overworld, and the Tithe Collectors come in threes.
        Offering vault = biome("ancient_vault", 2, "", "minecraft:netherite_scrap");
        vault.danger = 3;
        vault.guardian = OfferingAltar.MOD_ID + ":tithe_collector";
        vault.guardianCount = 3;
        vault.ores.add(OreBonus.of("minecraft:ancient_debris", "minecraft:ancient_debris", 5, -64, -32));
        vault.ores.add(OreBonus.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", 30, -64, 16));
        vault.ores.add(OreBonus.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore", 16, -64, 32));
        vault.ores.add(OreBonus.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", 8, -16, 128));
        l.add(vault);
        return c;
    }
}
