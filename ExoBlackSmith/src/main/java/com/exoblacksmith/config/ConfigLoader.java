package com.exoblacksmith.config;

import com.exoblacksmith.config.model.Appearance;
import com.exoblacksmith.config.model.ArmorPieceDef;
import com.exoblacksmith.config.model.ArmorSetDef;
import com.exoblacksmith.config.model.Category;
import com.exoblacksmith.config.model.DamageCategory;
import com.exoblacksmith.config.model.EquipSlot;
import com.exoblacksmith.config.model.HeadDef;
import com.exoblacksmith.config.model.Ingredient;
import com.exoblacksmith.config.model.ItemDef;
import com.exoblacksmith.config.model.ItemRef;
import com.exoblacksmith.config.model.MaskDef;
import com.exoblacksmith.config.model.MaskLevel;
import com.exoblacksmith.config.model.MaterialDef;
import com.exoblacksmith.config.model.PotionSpec;
import com.exoblacksmith.config.model.Rarity;
import com.exoblacksmith.config.model.RecipeDef;
import com.exoblacksmith.config.model.Reduction;
import com.exoblacksmith.config.model.RuneDef;
import com.exoblacksmith.config.model.RuneMechanic;
import com.exoblacksmith.item.ItemKind;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Parses and validates every config file into a {@link Registry}. Nothing is applied unless the whole
 * set validates; {@link Result#registry()} is {@code null} when there are errors.
 */
public final class ConfigLoader {

    public static final List<String> FILES = List.of("config.yml", "heads.yml", "masks.yml", "runes.yml",
            "armor.yml", "upgrades.yml", "recipes.yml", "menus.yml", "messages.yml");

    private static final String ID_PATTERN = "[a-z0-9_]+";

    /** Supplies a reader for a config file name, or {@code null} if it does not exist. */
    @FunctionalInterface
    public interface Source {
        Reader open(String file) throws java.io.IOException;
    }

    public record Result(Registry registry, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return registry != null;
        }
    }

    private final Problems problems = new Problems();
    private final Map<String, Section> roots = new HashMap<>();
    private Settings settings;
    private final Map<String, HeadDef> heads = new LinkedHashMap<>();
    private final Map<String, MaskDef> masks = new LinkedHashMap<>();
    private final Map<String, RuneDef> runes = new LinkedHashMap<>();
    private final Map<String, ArmorPieceDef> armor = new LinkedHashMap<>();
    private final Map<String, ArmorSetDef> sets = new LinkedHashMap<>();
    private final Map<String, MaterialDef> materials = new LinkedHashMap<>();
    private final Map<String, RecipeDef> recipes = new LinkedHashMap<>();

    private Source defaults;

    /**
     * @param source   the server's config files
     * @param defaults the bundled default files (used to fill message keys missing after an update)
     */
    public static Result load(Source source, Source defaults) {
        ConfigLoader loader = new ConfigLoader();
        loader.defaults = defaults;
        return loader.run(source);
    }

    private Result run(Source source) {
        for (String file : FILES) {
            YamlConfiguration yaml = new YamlConfiguration();
            try (Reader reader = source.open(file)) {
                if (reader == null) {
                    problems.error(file, "(file)", "file is missing");
                    continue;
                }
                yaml.load(reader);
            } catch (InvalidConfigurationException e) {
                problems.error(file, "(syntax)", firstLine(e.getMessage()));
                continue;
            } catch (java.io.IOException e) {
                problems.error(file, "(file)", "could not read: " + e.getMessage());
                continue;
            }
            roots.put(file, new Section(file, yaml, problems));
        }
        if (problems.hasErrors()) {
            return new Result(null, problems.errors(), problems.warnings());
        }
        settings = parseSettings(roots.get("config.yml"));
        parseHeads(roots.get("heads.yml"));
        parseMasks(roots.get("masks.yml"));
        parseRunes(roots.get("runes.yml"));
        parseArmor(roots.get("armor.yml"));
        parseUpgrades(roots.get("upgrades.yml"));
        checkDuplicateIds();
        parseRecipes(roots.get("recipes.yml"));
        MenuSettings menus = parseMenus(roots.get("menus.yml"));
        Messages messages = parseMessages(roots.get("messages.yml"));
        if (problems.hasErrors()) {
            return new Result(null, problems.errors(), problems.warnings());
        }
        Registry registry = new Registry(settings, messages, menus, heads, masks, runes, armor, sets, materials, recipes);
        RecipeValidator.validate(registry, problems);
        if (problems.hasErrors()) {
            return new Result(null, problems.errors(), problems.warnings());
        }
        return new Result(registry, problems.errors(), problems.warnings());
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "invalid YAML";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl) + " ...";
    }

    // ------------------------------------------------------------------ config.yml

    private Settings parseSettings(Section root) {
        Settings.Builder b = new Settings.Builder();
        b.unicodeSymbols = root.bool("presentation.unicode-symbols", true);
        b.smallCaps = root.bool("presentation.small-caps", false);
        Map<String, String[]> symbols = new HashMap<>();
        Section sym = root.child("presentation.symbols");
        if (sym != null) {
            for (String key : sym.keys()) {
                Section s = sym.child(key);
                if (s != null) {
                    symbols.put(key, new String[]{s.string("unicode"), s.string("plain")});
                }
            }
        }
        b.symbols = symbols;

        Map<String, Rarity> rarities = new LinkedHashMap<>();
        Section rs = root.child("rarities");
        if (rs != null) {
            int weight = 0;
            for (String key : rs.keys()) {
                Section r = rs.child(key);
                if (r == null) {
                    continue;
                }
                String color = r.string("color");
                if (!color.matches("#[0-9a-fA-F]{6}")) {
                    r.error("color", "expected #rrggbb hex color, got '" + color + "'");
                }
                rarities.put(key, new Rarity(key, r.string("name"), color, r.string("symbol", "◆"),
                        r.string("plain-symbol", "*"), r.string("itemsadder-glyph", ""), weight++));
            }
            for (String required : List.of("common", "uncommon", "rare", "epic", "legendary", "fabled")) {
                if (!rarities.containsKey(required)) {
                    rs.error(required, "required rarity is missing");
                }
            }
        }
        b.rarities = rarities;

        b.equipmentScanTicks = root.integer("performance.equipment-scan-interval-ticks", 10, 1, 200);
        b.clickCooldownMs = root.integer("menus.click-cooldown-ms", 250, 0, 5000);
        b.strictVanilla = root.bool("crafting.strict-vanilla-ingredients", true);
        b.refreshItemsOnJoin = root.bool("items.refresh-presentation-on-join", true);

        b.blockCrafting = root.bool("protection.block-vanilla-crafting", true);
        b.blockAnvil = root.bool("protection.block-anvil", true);
        b.blockSmithing = root.bool("protection.block-smithing", true);
        b.blockGrindstone = root.bool("protection.block-grindstone", true);
        b.blockEnchanting = root.bool("protection.block-enchanting", true);
        b.blockPlacement = root.bool("protection.block-head-placement", true);

        b.maxTotalReduction = root.number("damage.max-total-reduction", 0.8, 0, 0.95);
        b.combine = root.enumValue("damage.combine", Settings.ReductionCombine.class, Settings.ReductionCombine.MULTIPLICATIVE);
        b.runeStacking = root.enumValue("damage.rune-stacking", Settings.RuneStacking.class, Settings.RuneStacking.HIGHEST);
        b.maxBonusDamagePerHit = root.number("damage.max-bonus-damage-per-hit", 6, 0, 100);
        b.speedDisabledWhileFlying = root.bool("movement.disable-speed-bonus-while-flying", true);

        List<String> slots = root.strings("armor.set-bonus-required-slots");
        if (!slots.isEmpty()) {
            Set<EquipSlot> parsed = EnumSet.noneOf(EquipSlot.class);
            for (String s : slots) {
                EquipSlot slot = root.parseEnum("armor.set-bonus-required-slots", s, EquipSlot.class, null);
                if (slot == EquipSlot.ANY) {
                    root.error("armor.set-bonus-required-slots", "'any' is not a valid slot here");
                } else if (slot != null) {
                    parsed.add(slot);
                }
            }
            b.setBonusSlots = parsed;
        }

        b.abilityRequireSneak = root.bool("abilities.require-sneak", true);
        b.abilityRequireEmptyHand = root.bool("abilities.require-empty-main-hand", false);
        b.abilityDebounceMs = root.integer("abilities.debounce-ms", 300, 0, 5000);
        b.summonMaxActive = root.integer("abilities.summons.max-active-per-player", 4, 1, 32);
        b.summonRespectTeams = root.bool("abilities.summons.respect-scoreboard-teams", true);
        b.teleportCause = root.enumValue("abilities.teleport-cause", PlayerTeleportEvent.TeleportCause.class,
                PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        b.respectSpawnProtection = root.bool("abilities.respect-spawn-protection", true);

        b.itemsAdderEnabled = root.bool("integrations.itemsadder.enabled", true);

        b.dropWorlds = root.strings("drops.worlds");
        b.dropRequirePlayerKiller = root.bool("drops.require-player-killer", true);
        b.dropAllowSpawner = root.bool("drops.allow-spawner-mobs", true);
        b.dropAllowNatural = root.bool("drops.allow-natural-mobs", true);
        b.dropLootingBonus = root.number("drops.looting-bonus-per-level", 0.01, 0, 1);
        b.dropToInventory = root.bool("drops.give-directly-to-killer", false);

        b.exoEnabled = root.bool("integrations.exospawners.custom-event.enabled", false);
        b.exoEventClass = root.string("integrations.exospawners.custom-event.class", "");
        b.exoKillerMethod = root.string("integrations.exospawners.custom-event.killer-method", "");
        b.exoEntityMethod = root.string("integrations.exospawners.custom-event.entity-method", "");
        b.exoAmountMethod = root.string("integrations.exospawners.custom-event.amount-method", "");
        b.exoStackedMarkers = root.strings("integrations.exospawners.stacked-entity-markers");
        if (b.exoEnabled && (b.exoEventClass.isBlank() || b.exoKillerMethod.isBlank() || b.exoEntityMethod.isBlank())) {
            root.error("integrations.exospawners.custom-event", "enabled but class/killer-method/entity-method are not all set");
        }
        return new Settings(b);
    }

    // ------------------------------------------------------------------ shared parsers

    private Rarity rarity(Section s, String key) {
        String id = s.string(key);
        Rarity rarity = settings.rarities.get(id);
        if (rarity == null) {
            s.error(key, "unknown rarity '" + id + "', expected one of " + settings.rarities.keySet());
            return new Rarity(id, id, "#FFFFFF", "", "", "", 0);
        }
        return rarity;
    }

    private Appearance appearance(Section s, Material fallback) {
        Material material = s.material("material", fallback);
        String model = s.string("item-model", "");
        if (!model.isEmpty() && NamespacedKey.fromString(model) == null) {
            s.error("item-model", "invalid namespaced key '" + model + "'");
        }
        Float cmd = s.has("custom-model-data") ? (float) s.number("custom-model-data", 0, -1e7, 1e7) : null;
        String ia = s.string("itemsadder-id", "");
        if (!ia.isEmpty() && !ia.contains(":")) {
            s.error("itemsadder-id", "expected namespace:id, got '" + ia + "'");
        }
        String texture = s.string("texture", "");
        if (!texture.isEmpty()) {
            if (material != Material.PLAYER_HEAD) {
                s.error("texture", "textures require material PLAYER_HEAD");
            }
            try {
                String json = new String(Base64.getDecoder().decode(texture), java.nio.charset.StandardCharsets.UTF_8);
                if (!json.contains("textures.minecraft.net/texture/")) {
                    s.error("texture", "decoded texture does not reference textures.minecraft.net");
                }
            } catch (IllegalArgumentException e) {
                s.error("texture", "not valid base64");
            }
        }
        return new Appearance(material, model.isEmpty() ? null : model, cmd, ia.isEmpty() ? null : ia,
                texture.isEmpty() ? null : texture, s.bool("glint", false));
    }

    private List<Reduction> reductions(Section s, String key) {
        List<Reduction> out = new ArrayList<>();
        for (Section r : s.sectionList(key)) {
            DamageCategory cat = r.enumValue("category", DamageCategory.class, null);
            double value = r.requiredNumber("value", 0, 0.95);
            if (cat != null) {
                out.add(new Reduction(cat, value));
            }
        }
        return List.copyOf(out);
    }

    private boolean validId(Section parent, String id) {
        if (!id.matches(ID_PATTERN)) {
            parent.error(id, "ids must be lowercase letters, digits and underscores");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ heads.yml

    private void parseHeads(Section root) {
        Section all = root.child("heads");
        if (all == null) {
            return;
        }
        for (String id : all.keys()) {
            Section h = all.child(id);
            if (h == null || !validId(all, id)) {
                continue;
            }
            Appearance appearance = appearance(h, Material.PLAYER_HEAD);
            if (appearance.texture() == null) {
                h.error("texture", "value heads require a texture");
            }
            EntityType mob = h.entityType("mob");
            double chance = h.number("drop.chance", 0.0, 0, 1);
            int min = h.integer("drop.min", 1, 0, 64);
            int max = h.integer("drop.max", 1, 0, 64);
            if (max < min) {
                h.error("drop.max", "must be >= drop.min");
            }
            heads.put(id, new HeadDef(id, mob, h.string("name"), rarity(h, "rarity"), appearance, h.strings("lore"),
                    chance, min, max));
        }
    }

    // ------------------------------------------------------------------ masks.yml

    private void parseMasks(Section root) {
        Section all = root.child("masks");
        if (all == null) {
            return;
        }
        for (String id : all.keys()) {
            Section m = all.child(id);
            if (m == null || !validId(all, id)) {
                continue;
            }
            Appearance appearance = appearance(m, Material.PLAYER_HEAD);
            if (appearance.material() != Material.PLAYER_HEAD || appearance.texture() == null) {
                m.error("texture", "masks must be PLAYER_HEAD items with a texture");
            }
            String ingredient = m.string("ingredient-head");
            Section levels = m.child("levels");
            Map<Integer, MaskLevel> parsed = new LinkedHashMap<>();
            if (levels != null) {
                int expected = 1;
                for (String key : levels.keys()) {
                    int level;
                    try {
                        level = Integer.parseInt(key);
                    } catch (NumberFormatException e) {
                        levels.error(key, "level keys must be numbers");
                        continue;
                    }
                    if (level != expected) {
                        levels.error(key, "levels must be consecutive starting at 1 (expected " + expected + ")");
                    }
                    expected++;
                    Section l = levels.child(key);
                    if (l != null) {
                        parsed.put(level, maskLevel(level, l));
                    }
                }
                if (parsed.isEmpty()) {
                    levels.error("", "at least one level is required");
                }
            }
            masks.put(id, new MaskDef(id, m.string("name"), ingredient, appearance, m.strings("lore"), parsed));
        }
        for (MaskDef mask : masks.values()) {
            if (!heads.containsKey(mask.ingredientHead())) {
                all.error(mask.id() + ".ingredient-head", "unknown head '" + mask.ingredientHead() + "'");
            }
        }
    }

    private MaskLevel maskLevel(int level, Section l) {
        List<PotionSpec> effects = new ArrayList<>();
        for (Section e : l.sectionList("effects")) {
            var type = e.potion("type", e.string("type"));
            int amp = e.integer("amplifier", 0, 0, 9);
            if (type != null) {
                effects.add(new PotionSpec(type, amp));
            }
        }
        double hearts = l.number("bonus-hearts", 0, 0, 50);
        double trident = l.number("trident-bonus-damage", 0, 0, 50);

        MaskLevel.HealAbility heal = null;
        MaskLevel.ArrowTeleportAbility tp = null;
        MaskLevel.ExplosiveHitAbility explosive = null;
        MaskLevel.CobwebAbility cobweb = null;
        MaskLevel.SummonAbility summon = null;
        Section abilities = l.optionalChild("abilities");
        if (abilities != null) {
            Section h = abilities.optionalChild("heal");
            if (h != null) {
                boolean full = h.bool("full", false);
                double amount = full ? 0 : h.requiredNumber("hearts", 0.5, 1000);
                heal = new MaskLevel.HealAbility(amount, full, (long) h.requiredNumber("cooldown", 1, 86400));
            }
            Section t = abilities.optionalChild("arrow-teleport");
            if (t != null) {
                tp = new MaskLevel.ArrowTeleportAbility((long) t.requiredNumber("cooldown", 1, 86400),
                        (long) t.number("miss-cooldown", 10, 0, 86400), t.integer("max-flight-seconds", 8, 1, 60));
            }
            Section x = abilities.optionalChild("explosive-hit");
            if (x != null) {
                explosive = new MaskLevel.ExplosiveHitAbility(x.requiredNumber("bonus-damage", 0, 100),
                        (long) x.requiredNumber("cooldown", 0, 86400));
            }
            Section c = abilities.optionalChild("cobweb");
            if (c != null) {
                cobweb = new MaskLevel.CobwebAbility((long) c.requiredNumber("cooldown", 1, 86400),
                        c.enumValue("shape", MaskLevel.CobwebShape.class, MaskLevel.CobwebShape.PLUS),
                        c.integer("duration-seconds", 6, 1, 120), c.integer("range", 12, 1, 48));
            }
            Section s = abilities.optionalChild("summon");
            if (s != null) {
                EntityType type = s.entityType("entity");
                if (type != null && type != EntityType.WOLF && type != EntityType.IRON_GOLEM) {
                    s.error("entity", "only WOLF and IRON_GOLEM summons are supported");
                }
                summon = new MaskLevel.SummonAbility(type, s.integer("count", 1, 1, 8), s.integer("lifetime-seconds", 15, 1, 300),
                        (long) s.requiredNumber("cooldown", 1, 86400), s.integer("range", 16, 2, 64),
                        s.number("damage", 4, 0, 100));
            }
            for (String key : abilities.keys()) {
                if (!List.of("heal", "arrow-teleport", "explosive-hit", "cobweb", "summon").contains(key)) {
                    abilities.error(key, "unknown ability type");
                }
            }
        }
        int activated = (heal != null ? 1 : 0) + (cobweb != null ? 1 : 0) + (summon != null ? 1 : 0);
        if (activated > 1) {
            l.error("abilities", "a mask level may have at most one sneak+right-click ability (heal, cobweb or summon)");
        }
        return new MaskLevel(level, rarity(l, "rarity"), List.copyOf(effects), hearts, trident, heal, tp, explosive,
                cobweb, summon, l.strings("effect-lore"));
    }

    // ------------------------------------------------------------------ runes.yml

    private void parseRunes(Section root) {
        Section all = root.child("runes");
        if (all == null) {
            return;
        }
        for (String id : all.keys()) {
            Section r = all.child(id);
            if (r == null || !validId(all, id)) {
                continue;
            }
            RuneMechanic mechanic = r.enumValue("mechanic", RuneMechanic.class, null);
            EquipSlot slot = r.enumValue("slot", EquipSlot.class, EquipSlot.ANY);
            Section tiers = r.child("tiers");
            Map<Integer, RuneDef.Tier> parsed = new LinkedHashMap<>();
            if (tiers != null) {
                int expected = 1;
                for (String key : tiers.keys()) {
                    int tier;
                    try {
                        tier = Integer.parseInt(key);
                    } catch (NumberFormatException e) {
                        tiers.error(key, "tier keys must be numbers");
                        continue;
                    }
                    if (tier != expected++) {
                        tiers.error(key, "tiers must be consecutive starting at 1");
                    }
                    Section t = tiers.child(key);
                    if (t == null) {
                        continue;
                    }
                    double max = mechanic != null && mechanic.isReduction() ? 0.95 : 600;
                    parsed.put(tier, new RuneDef.Tier(tier, rarity(t, "rarity"), t.requiredNumber("value", 0, max)));
                }
            }
            Map<String, Double> params = new HashMap<>();
            Section p = r.optionalChild("params");
            if (p != null) {
                for (String key : p.keys()) {
                    params.put(key, p.requiredNumber(key, -1000, 100000));
                }
            }
            if (mechanic == RuneMechanic.TOTEM_SURGE && slot != EquipSlot.CHESTPLATE) {
                r.error("slot", "totem_surge runes must be chestplate-only");
            }
            runes.put(id, new RuneDef(id, r.string("name"), mechanic, slot, appearance(r, Material.PAPER), r.strings("lore"),
                    r.string("effect-line"), parsed, Map.copyOf(params)));
        }
    }

    // ------------------------------------------------------------------ armor.yml

    private void parseArmor(Section root) {
        Section setsSection = root.child("sets");
        if (setsSection != null) {
            for (String id : setsSection.keys()) {
                Section s = setsSection.child(id);
                if (s == null || !validId(setsSection, id)) {
                    continue;
                }
                Set<EquipSlot> required = settings.setBonusSlots;
                if (s.has("required-slots")) {
                    Set<EquipSlot> parsed = EnumSet.noneOf(EquipSlot.class);
                    for (String slot : s.strings("required-slots")) {
                        EquipSlot es = s.parseEnum("required-slots", slot, EquipSlot.class, null);
                        if (es != null && es != EquipSlot.ANY) {
                            parsed.add(es);
                        }
                    }
                    required = parsed;
                }
                ArmorSetDef.SetAbility ability = null;
                Section a = s.optionalChild("ability");
                if (a != null) {
                    ability = new ArmorSetDef.SetAbility(a.string("name"),
                            a.enumValue("type", ArmorSetDef.AbilityType.class, null),
                            (long) a.requiredNumber("cooldown", 1, 86400), a.requiredNumber("duration", 0.5, 120),
                            reductions(a, "reductions"), a.bool("extinguish", false), a.number("dash-force", 1.2, 0, 4),
                            a.number("dash-vertical", 0.35, 0, 2), a.strings("lore"));
                }
                sets.put(id, new ArmorSetDef(id, s.string("name"), Set.copyOf(required), reductions(s, "bonus.reductions"),
                        s.number("bonus.speed", 0, 0, 1), ability, s.strings("bonus.lore")));
            }
        }
        Section pieces = root.child("pieces");
        if (pieces == null) {
            return;
        }
        for (String id : pieces.keys()) {
            Section p = pieces.child(id);
            if (p == null || !validId(pieces, id)) {
                continue;
            }
            EquipSlot slot = p.enumValue("slot", EquipSlot.class, null);
            if (slot == EquipSlot.ANY) {
                p.error("slot", "armor pieces need a concrete slot");
            }
            String setId = p.string("set");
            if (!sets.containsKey(setId)) {
                p.error("set", "unknown set '" + setId + "'");
            }
            Appearance appearance = appearance(p, null);
            if (slot != null && slot.bukkit() != null && appearance.material() != null
                    && !appearance.material().name().endsWith("_" + slot.name())) {
                p.error("material", appearance.material() + " does not equip in the " + slot.display() + " slot");
            }
            armor.put(id, new ArmorPieceDef(id, p.string("name"), setId, slot, rarity(p, "rarity"), appearance,
                    p.strings("lore"), reductions(p, "reductions"), p.number("speed", 0, 0, 1)));
        }
    }

    // ------------------------------------------------------------------ upgrades.yml

    private void parseUpgrades(Section root) {
        Section mats = root.child("materials");
        if (mats != null) {
            for (String id : mats.keys()) {
                Section m = mats.child(id);
                if (m == null || !validId(mats, id)) {
                    continue;
                }
                materials.put(id, new MaterialDef(id, ItemKind.MATERIAL, m.string("name"), rarity(m, "rarity"),
                        appearance(m, Material.PAPER), m.strings("lore"), null));
            }
        }
        Section totems = root.child("totems");
        if (totems != null) {
            for (String id : totems.keys()) {
                Section t = totems.child(id);
                if (t == null || !validId(totems, id)) {
                    continue;
                }
                String maskId = t.string("mask");
                MaskDef mask = masks.get(maskId);
                if (mask == null) {
                    t.error("mask", "unknown mask '" + maskId + "'");
                } else if (mask.maxLevel() < 2) {
                    t.warn("mask", "mask '" + maskId + "' has no upgrades; this totem is unused");
                }
                Appearance appearance = appearance(t, Material.PAPER);
                if (appearance.material() == Material.TOTEM_OF_UNDYING) {
                    t.error("material", "use a non-functional base material (e.g. PAPER) with item-model "
                            + "minecraft:totem_of_undying; a real totem would be consumed on death");
                }
                materials.put(id, new MaterialDef(id, ItemKind.TOTEM, t.string("name"), rarity(t, "rarity"),
                        appearance, t.strings("lore"), maskId));
            }
        }
        for (String required : List.of("legendary_rune_upgrade", "fabled_rune_upgrade")) {
            if (!materials.containsKey(required)) {
                problems.error("upgrades.yml", "materials." + required, "required upgrade material is missing");
            }
        }
    }

    private void checkDuplicateIds() {
        Map<String, String> seen = new HashMap<>();
        checkIds(seen, heads.keySet(), "heads.yml");
        checkIds(seen, masks.keySet(), "masks.yml");
        checkIds(seen, runes.keySet(), "runes.yml");
        checkIds(seen, armor.keySet(), "armor.yml");
        checkIds(seen, materials.keySet(), "upgrades.yml");
    }

    private void checkIds(Map<String, String> seen, Set<String> ids, String file) {
        for (String id : ids) {
            String previous = seen.putIfAbsent(id, file);
            if (previous != null) {
                problems.error(file, id, "id is already used in " + previous + "; item ids must be globally unique");
            }
        }
    }

    // ------------------------------------------------------------------ recipes.yml

    private void parseRecipes(Section root) {
        Section all = root.child("recipes");
        if (all == null) {
            return;
        }
        int order = 0;
        for (String id : all.keys()) {
            Section r = all.child(id);
            if (r == null || !validId(all, id)) {
                continue;
            }
            Category category = r.enumValue("category", Category.class, null);
            Section out = r.child("output");
            ItemRef output = out == null ? null : itemRef(out, "item");
            int outputAmount = out == null ? 1 : out.integer("amount", 1, 1, 64);
            if (output != null && output.isVanilla()) {
                out.error("item", "recipe outputs must be ExoBlackSmith items");
            }
            if (output != null && !output.isVanilla()) {
                ItemDef def = itemDefs().get(output.exoId());
                if (def != null && def.kind().unique() && outputAmount != 1) {
                    out.error("amount", "unique items (armor, masks) must be crafted one at a time");
                }
            }
            List<String> pattern = r.strings("pattern");
            Section key = r.child("key");
            Ingredient[] grid = new Ingredient[9];
            if (pattern.size() != 3) {
                r.error("pattern", "expected exactly 3 rows");
            } else {
                for (int row = 0; row < 3; row++) {
                    String line = pattern.get(row);
                    if (line.length() != 3) {
                        r.error("pattern[" + row + "]", "each row must be exactly 3 characters (use space for empty)");
                        continue;
                    }
                    for (int col = 0; col < 3; col++) {
                        char c = line.charAt(col);
                        if (c == ' ') {
                            continue;
                        }
                        Section ing = key == null ? null : key.optionalChild(String.valueOf(c));
                        if (ing == null) {
                            r.error("key." + c, "symbol used in pattern is not defined");
                            continue;
                        }
                        ItemRef ref = itemRef(ing, "item");
                        int amount = ing.integer("amount", 1, 1, 64);
                        if (ref != null) {
                            grid[row * 3 + col] = new Ingredient(ref, amount);
                        }
                    }
                }
            }
            boolean empty = true;
            for (Ingredient ingredient : grid) {
                empty &= ingredient == null;
            }
            if (empty) {
                r.error("pattern", "recipe has no ingredients");
            }
            String permission = r.string("permission", category == null ? "exoblacksmith.craft"
                    : "exoblacksmith.craft." + category.key());
            if (category != null && output != null) {
                recipes.put(id, new RecipeDef(id, category, output, outputAmount, grid, permission, order++));
            }
        }
    }

    private Map<String, ItemDef> itemDefs() {
        Map<String, ItemDef> all = new HashMap<>();
        all.putAll(heads);
        all.putAll(masks);
        all.putAll(runes);
        all.putAll(armor);
        all.putAll(materials);
        return all;
    }

    private ItemRef itemRef(Section s, String key) {
        String raw = s.string(key).toLowerCase(Locale.ROOT);
        if (raw.startsWith("minecraft:")) {
            Material material = Material.matchMaterial(raw);
            if (material == null || !material.isItem() || material.isAir()) {
                s.error(key, "unknown vanilla item '" + raw + "'");
                return null;
            }
            if (s.has("tier") || s.has("level")) {
                s.error("tier", "vanilla items have no tier");
            }
            return ItemRef.vanilla(material);
        }
        ItemDef def = itemDefs().get(raw);
        if (def == null) {
            s.error(key, "unknown item '" + raw + "' (prefix vanilla items with minecraft:)");
            return null;
        }
        int level = s.has("tier") ? s.integer("tier", 1, 1, 100) : s.integer("level", 1, 1, 100);
        if (level > def.maxLevel()) {
            s.error("tier", raw + " only has " + def.maxLevel() + " tier(s)/level(s)");
            return null;
        }
        return ItemRef.exo(raw, level);
    }

    // ------------------------------------------------------------------ menus.yml / messages.yml

    private MenuSettings parseMenus(Section root) {
        Map<String, String> titles = new HashMap<>();
        Section t = root.child("titles");
        if (t != null) {
            for (String key : MenuSettings.REQUIRED_TITLES) {
                titles.put(key, t.string(key));
            }
        }
        Map<String, MenuSettings.Button> buttons = new HashMap<>();
        Section b = root.child("buttons");
        if (b != null) {
            for (String key : MenuSettings.REQUIRED_BUTTONS) {
                Section button = b.child(key);
                if (button != null) {
                    buttons.put(key, new MenuSettings.Button(button.material("material", null), button.string("name"),
                            button.strings("lore")));
                }
            }
        }
        return new MenuSettings(titles, buttons, root.material("filler", Material.GRAY_STAINED_GLASS_PANE),
                root.material("grid-frame", Material.BLACK_STAINED_GLASS_PANE));
    }

    static final List<String> REQUIRED_MESSAGES = List.of(
            "prefix", "no-permission", "player-only", "player-not-found", "unknown-item",
            "invalid-amount", "invalid-tier", "given", "received", "inventory-overflow",
            "reload-success", "reload-failed", "drop-granted", "usage-admin", "usage-give",
            "usage-givehead", "usage-drop", "inspect-none", "retired-item", "inspect",
            "craft-success", "craft-missing", "craft-no-space", "craft-recipe-changed", "craft-busy",
            "craft-no-permission", "transformation-blocked", "rune-applied", "rune-incompatible", "rune-no-slots",
            "rune-duplicate", "rune-not-armor", "rune-item-missing", "on-cooldown", "ability-ready",
            "heal-used", "heal-full-health", "cobweb-used", "cobweb-no-target", "summon-used",
            "summon-no-target", "summon-pvp-disabled", "teleport-armed", "teleport-success", "teleport-failed",
            "explosive-hit", "totem-surge", "set-ability-used", "set-ability-incomplete", "tidal-breath",
            "item-name", "lore-rarity", "lore-rarity-nolevel", "lore-level-label", "lore-tier-label",
            "lore-section-effects", "lore-reduction", "lore-speed", "lore-potion", "lore-hearts",
            "lore-trident", "lore-cooldown", "lore-equip", "lore-mask-slot", "lore-upgradable",
            "lore-mask-activation", "lore-mask-arrow-activation", "lore-rune-fits", "lore-rune-slot-empty", "lore-rune-slot-filled",
            "lore-set", "lore-set-ability", "category-all", "category-crystal-anchor", "category-explosion",
            "category-critical-melee", "category-mace-smash", "category-elytra-collision", "category-fire", "category-lava",
            "category-fall", "category-projectile", "menu-category-armor", "menu-category-masks", "menu-category-runes",
            "menu-category-upgrades", "menu-ingredients-header", "menu-ingredient-ok", "menu-ingredient-missing", "menu-status-craftable",
            "menu-status-missing", "menu-status-locked", "menu-status-no-space", "menu-click-view", "menu-recipe-required",
            "menu-rune-select", "menu-rune-full", "menu-rune-no-armor", "menu-rune-owned", "menu-rune-compatible",
            "menu-rune-incompatible", "menu-rune-duplicate", "menu-rune-no-slots", "menu-rune-not-armor", "menu-rune-armor-missing",
            "menu-rune-rune-missing", "menu-rune-choose");

    private Messages parseMessages(Section root) {
        Map<String, String> templates = new HashMap<>();
        for (String key : root.keys()) {
            Object value = root.rawValue(key);
            if (value instanceof List<?> list) {
                templates.put(key, String.join("\n", list.stream().map(String::valueOf).toList()));
            } else if (value != null && !(value instanceof org.bukkit.configuration.ConfigurationSection)) {
                templates.put(key, String.valueOf(value));
            }
        }
        Map<String, String> fallback = defaultMessages();
        for (String required : REQUIRED_MESSAGES) {
            if (!templates.containsKey(required)) {
                String value = fallback.get(required);
                if (value == null) {
                    root.error(required, "required message is missing");
                } else {
                    root.warn(required, "missing; using the bundled default");
                    templates.put(required, value);
                }
            }
        }
        return new Messages(templates);
    }

    private Map<String, String> defaultMessages() {
        Map<String, String> out = new HashMap<>();
        if (defaults == null) {
            return out;
        }
        try (Reader reader = defaults.open("messages.yml")) {
            if (reader == null) {
                return out;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
            for (String key : yaml.getKeys(false)) {
                if (yaml.isString(key)) {
                    out.put(key, yaml.getString(key));
                }
            }
        } catch (java.io.IOException ignored) {
            // no defaults available
        }
        return out;
    }

    /** Exposed for the drop handler: maps mob type to its value head, if configured. */
    public static Map<EntityType, HeadDef> headsByMob(Registry registry) {
        Map<EntityType, HeadDef> map = new HashMap<>();
        for (HeadDef head : registry.heads()) {
            if (head.mob() != null) {
                map.putIfAbsent(head.mob(), head);
            }
        }
        return map;
    }
}
