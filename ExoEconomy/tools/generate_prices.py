#!/usr/bin/env python3
"""Generates src/main/resources/prices.yml, the default /sell catalogue.

Usage:
    python3 -I tools/generate_prices.py <minecraft-data>/data 26.1 src/main/resources/prices.yml

Input is the PrismarineJS `minecraft-data` npm package (items.json, blocks.json and
recipes.json for the given version). Every block item (a name that is both a block and an
item, which is what Bukkit's Material#isBlock() && Material#isItem() reports) gets an
explicit entry: a price, or `false` with a documented reason.

Pricing model
-------------
* RAW values are hand-set for things obtained from the world (mining, farming, mob drops).
  Abundant or easily farmed things are cheap; non-renewable or slow things cost more.
* Everything else is DERIVED from recipes: value = min over recipes of (sum of inputs / count).
  Crafting recipes come from minecraft-data; smelting and stonecutting are modelled below
  because minecraft-data has no data for them.
* A RAW value is lowered to its derived value when a recipe makes it more cheaply.
* All prices are rounded DOWN to 4 decimals. So for every modelled recipe,
  price(output) * count <= sum(price(inputs)): crafting never turns sellable inputs into
  more Shards. The plugin re-checks this at startup against the server's real recipe list
  (/exoeconomy audit), which also covers anything this model misses.
"""
import json
import sys
from decimal import Decimal, ROUND_DOWN
from fractions import Fraction

DATA, VERSION, OUT = sys.argv[1], sys.argv[2], sys.argv[3]

def load(kind):
    paths = json.load(open(f"{DATA}/dataPaths.json"))["pc"][VERSION]
    return json.load(open(f"{DATA}/{paths[kind]}/{kind}.json"))

items = {i["id"]: i["name"] for i in load("items")}
item_names = set(items.values())
block_names = {b["name"] for b in load("blocks")}
block_items = sorted(item_names & block_names)
recipes_raw = load("recipes")

COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
         "pale_oak", "crimson", "warped"]
COPPER_STAGES = ["", "exposed_", "weathered_", "oxidized_"]

# ---------------------------------------------------------------- disabled entries
DISABLED = {}
def disable(reason, *names):
    for n in names:
        DISABLED[n] = reason

disable("technical: not an item players can hold", "air")
disable("unobtainable in survival (creative/commands only)",
        "barrier", "bedrock", "light", "structure_block", "structure_void", "jigsaw",
        "command_block", "chain_command_block", "repeating_command_block", "test_block",
        "test_instance_block", "spawner", "trial_spawner", "vault", "budding_amethyst",
        "reinforced_deepslate", "end_portal_frame", "petrified_oak_slab", "farmland",
        "dirt_path", "frogspawn", "chorus_plant", "suspicious_sand", "suspicious_gravel")
disable("unobtainable in survival (infested blocks cannot be silk-touched)",
        *[n for n in block_items if n.startswith("infested_")])
disable("unique or identity-bearing item (one dragon egg per world; player heads carry profiles)",
        "dragon_egg", "player_head")

# ---------------------------------------------------------------- raw values
RAW = {}
def raw(value, *names):
    for n in names:
        RAW[n] = Fraction(Decimal(str(value)))

# Terrain: abundant, renewable or generator-made blocks stay very cheap.
raw(0.01, "cobblestone", "sand", "gravel", "basalt", "tuff", "andesite", "diorite", "granite",
    "smooth_basalt", "cobbled_deepslate", "pointed_dripstone", "grass_block", "podzol",
    "mycelium", "rooted_dirt", "leaf_litter")
raw(0.005, "dirt", "netherrack", "short_grass", "fern", "seagrass", "nether_sprouts",
    "crimson_roots", "warped_roots", "dead_bush", "bush", "short_dry_grass", "tall_dry_grass",
    "tall_grass", "large_fern", "kelp")
raw(0.012, "stone", "deepslate", "blackstone", "red_sand")
raw(0.006, "mud")
raw(0.02, "soul_sand", "soul_soil", "crimson_nylium", "warped_nylium", "end_stone", "calcite",
    "ice", "mangrove_roots", "warped_wart_block")
raw(0.15, "obsidian")
raw(0.2, "crying_obsidian")
raw(0.3, "gilded_blackstone")
raw(2.0, "sponge", "wet_sponge")
raw(0.05, "sculk", "cobweb", "magma_block")
raw(0.02, "sculk_vein")
raw(1.0, "sculk_catalyst", "sculk_shrieker", "creaking_heart")
raw(0.5, "sculk_sensor", "spore_blossom")
raw(0.1, "amethyst_cluster", "shroomlight", "chorus_flower")
raw(0.02, "small_amethyst_bud")
raw(0.04, "medium_amethyst_bud")
raw(0.06, "large_amethyst_bud")
# Ores obtained with Silk Touch sell at the value of one smelted or dropped item.
raw(0.4, "coal_ore", "deepslate_coal_ore")
raw(1.0, "iron_ore", "deepslate_iron_ore")
raw(0.3, "copper_ore", "deepslate_copper_ore")
raw(1.5, "gold_ore", "deepslate_gold_ore")
raw(0.4, "nether_gold_ore")
raw(0.6, "redstone_ore", "deepslate_redstone_ore")
raw(1.2, "lapis_ore", "deepslate_lapis_ore")
raw(5.0, "diamond_ore", "deepslate_diamond_ore")
raw(2.5, "emerald_ore", "deepslate_emerald_ore")
raw(0.3, "nether_quartz_ore")
raw(25.0, "ancient_debris")
# Wood and plants: renewable, priced for farming.
for w in WOODS:
    stem = "stem" if w in ("crimson", "warped") else "log"
    raw(0.08, f"{w}_{stem}", f"stripped_{w}_{stem}")
    if w not in ("crimson", "warped"):
        raw(0.01, f"{w}_leaves")
        raw(0.02, f"{w}_sapling" if w != "mangrove" else "mangrove_propagule")
raw(0.01, "azalea_leaves", "flowering_azalea_leaves", "vine", "glow_lichen", "moss_block",
    "pale_moss_block", "hanging_roots", "pale_hanging_moss", "twisting_vines", "weeping_vines",
    "pink_petals", "wildflowers", "lily_pad")
raw(0.05, "azalea", "firefly_bush")
raw(0.06, "flowering_azalea")
raw(0.03, "big_dripleaf", "small_dripleaf", "sea_pickle", "brown_mushroom", "red_mushroom",
    "crimson_fungus", "warped_fungus", "cactus_flower", "sunflower", "lilac", "rose_bush",
    "peony", "open_eyeblossom", "closed_eyeblossom")
raw(0.02, "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
    "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
    "lily_of_the_valley", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem",
    "cactus", "sugar_cane")
raw(0.2, "wither_rose")
raw(0.1, "torchflower", "pitcher_plant")
raw(0.003, "bamboo")
raw(0.15, "pumpkin", "carved_pumpkin")
raw(0.04, "wheat", "nether_wart")
raw(0.3, "ochre_froglight", "verdant_froglight", "pearlescent_froglight")
raw(1.0, "turtle_egg", "bee_nest")
raw(5.0, "sniffer_egg", "creeper_head", "zombie_head", "skeleton_skull", "piglin_head")
raw(15.0, "wither_skeleton_skull")
raw(50.0, "dragon_head", "heavy_core")
raw(0.1, "white_wool")
for c in ["tube", "brain", "bubble", "fire", "horn"]:
    raw(0.1, f"{c}_coral_block")
    raw(0.05, f"{c}_coral", f"{c}_coral_fan")
    raw(0.02, f"dead_{c}_coral_block", f"dead_{c}_coral", f"dead_{c}_coral_fan")
for stage in COPPER_STAGES:
    raw(2.0, f"{stage}copper_golem_statue")
raw(0.0025, "snowball")
raw(2.0, "bell")  # village loot / trading only
raw(0.3, "cake")  # needs milk buckets, which are not sold
raw(0.05, "resin_clump")

# Non-block ingredients and drops (needed to derive crafted block prices; also sellable).
EXTRA_SELLABLE = {}
def item(value, *names):
    for n in names:
        RAW[n] = Fraction(Decimal(str(value)))
        EXTRA_SELLABLE[n] = True

item(0.4, "coal")
item(1.0, "raw_iron")
item(0.3, "raw_copper")
item(1.5, "raw_gold")
item(0.1, "redstone")
item(0.15, "lapis_lazuli")
item(5.0, "diamond")
item(2.5, "emerald")
item(0.08, "quartz")
item(0.05, "amethyst_shard", "glowstone_dust", "ink_sac", "bone")
item(0.0075, "clay_ball")
item(0.1, "prismarine_shard", "slime_ball", "honeycomb", "leather", "honey_bottle")
item(0.15, "prismarine_crystals")
item(0.02, "string", "rotten_flesh", "feather", "sweet_berries", "glow_berries", "egg",
    "chorus_fruit")
item(0.1, "gunpowder", "glow_ink_sac")
item(0.04, "spider_eye")
item(0.5, "ender_pearl", "blaze_rod", "breeze_rod")
item(0.3, "phantom_membrane")
item(1.0, "ghast_tear")
item(0.2, "magma_cream")
item(3.0, "shulker_shell")
item(2.0, "nautilus_shell")
item(20.0, "heart_of_the_sea")
item(100.0, "nether_star")
item(0.5, "echo_shard")
item(0.05, "beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon", "rabbit_hide")
item(0.3, "rabbit_foot")
item(0.03, "carrot", "potato", "beetroot", "apple", "cocoa_beans", "melon_slice")
item(0.005, "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "poisonous_potato")
item(0.02, "torchflower_seeds", "pitcher_pod")
item(1.0, "armadillo_scute", "turtle_scute")
item(0.02, "flint")
for extra in ["iron_ingot", "copper_ingot", "gold_ingot", "netherite_scrap", "netherite_ingot",
              "iron_nugget", "gold_nugget", "copper_nugget", "charcoal", "dried_kelp", "brick",
              "nether_brick", "bone_meal", "blaze_powder", "resin_brick"]:
    EXTRA_SELLABLE[extra] = True  # sellable; price derived below

# ---------------------------------------------------------------- recipes
# Each recipe: (output, count, [inputs]) with inputs repeated per use.
RECIPES = []
for out_id, rs in recipes_raw.items():
    out = items.get(int(out_id))
    for r in rs:
        if "inShape" in r:
            ins = [items[x] for row in r["inShape"] for x in row if x is not None]
        else:
            ins = [items[x] for x in r.get("ingredients", []) if x is not None]
        if ins:
            RECIPES.append((out, r["result"]["count"], ins))

def recipe(out, count, *ins):
    RECIPES.append((out, count, list(ins)))

# Smelting (furnace / blast furnace / smoker): one input -> one output; fuel ignored, so the
# output is valued at most at its input.
SMELT = {
    "stone": ["cobblestone"], "smooth_stone": ["stone"], "glass": ["sand", "red_sand"],
    "terracotta": ["clay"], "cracked_stone_bricks": ["stone_bricks"],
    "cracked_deepslate_bricks": ["deepslate_bricks"], "cracked_deepslate_tiles": ["deepslate_tiles"],
    "cracked_nether_bricks": ["nether_bricks"],
    "cracked_polished_blackstone_bricks": ["polished_blackstone_bricks"],
    "smooth_sandstone": ["sandstone"], "smooth_red_sandstone": ["red_sandstone"],
    "smooth_quartz": ["quartz_block"], "smooth_basalt": ["basalt"], "deepslate": ["cobbled_deepslate"],
    "sponge": ["wet_sponge"],
    "iron_ingot": ["raw_iron", "iron_ore", "deepslate_iron_ore"],
    "copper_ingot": ["raw_copper", "copper_ore", "deepslate_copper_ore"],
    "gold_ingot": ["raw_gold", "gold_ore", "deepslate_gold_ore", "nether_gold_ore"],
    "coal": ["coal_ore", "deepslate_coal_ore"], "diamond": ["diamond_ore", "deepslate_diamond_ore"],
    "emerald": ["emerald_ore", "deepslate_emerald_ore"],
    "lapis_lazuli": ["lapis_ore", "deepslate_lapis_ore"],
    "redstone": ["redstone_ore", "deepslate_redstone_ore"], "quartz": ["nether_quartz_ore"],
    "netherite_scrap": ["ancient_debris"], "brick": ["clay_ball"], "nether_brick": ["netherrack"],
    "dried_kelp": ["kelp"], "green_dye": ["cactus"], "lime_dye": ["sea_pickle"],
    "popped_chorus_fruit": ["chorus_fruit"], "resin_brick": ["resin_clump"],
    "charcoal": [f"{w}_log" for w in WOODS if w not in ("crimson", "warped")],
}
for c in COLORS:
    SMELT[f"{c}_glazed_terracotta"] = [f"{c}_terracotta"]
for out, ins in SMELT.items():
    for i in ins:
        recipe(out, 1, i)

# Concrete powder hardens in water (not a recipe) and is valued at most as its powder.
for c in COLORS:
    recipe(f"{c}_concrete", 1, f"{c}_concrete_powder")
# Axe stripping (not a recipe): stripped variants are never worth more than the original.
for w in WOODS:
    stem, wood = ("stem", "hyphae") if w in ("crimson", "warped") else ("log", "wood")
    recipe(f"stripped_{w}_{stem}", 1, f"{w}_{stem}")
    recipe(f"stripped_{w}_{wood}", 1, f"{w}_{wood}")
recipe("stripped_bamboo_block", 1, "bamboo_block")
recipe("carved_pumpkin", 1, "pumpkin")
# Dyeing recipes use item tags; minecraft-data stores one arbitrary tag member, so model them.
for c in COLORS:
    recipe(f"{c}_wool", 1, "white_wool", f"{c}_dye")
    recipe(f"{c}_carpet", 1, "white_carpet", f"{c}_dye")
    recipe(f"{c}_shulker_box", 1, "shulker_box", f"{c}_dye")
recipe("shulker_box", 1, "shulker_shell", "shulker_shell", "chest")
# Anvils degrade with use for free.
recipe("chipped_anvil", 1, "anvil")
recipe("damaged_anvil", 1, "chipped_anvil")
# Waxing with honeycomb happens in the world as well as in the crafting grid.
# Oxidation is free and happens over time, so every stage is capped at the unoxidized value.

# Stonecutting: 1 base -> 1 of each listed variant; slabs 2, stairs 1, walls 1.
STONECUT_FAMILIES = {
    "stone": ["stone_bricks", "chiseled_stone_bricks", "stone_slab", "stone_stairs",
              "stone_brick_slab", "stone_brick_stairs", "stone_brick_wall"],
    "stone_bricks": ["chiseled_stone_bricks", "stone_brick_slab", "stone_brick_stairs", "stone_brick_wall"],
    "smooth_stone": ["smooth_stone_slab"],
    "cobblestone": ["cobblestone_slab", "cobblestone_stairs", "cobblestone_wall"],
    "mossy_cobblestone": ["mossy_cobblestone_slab", "mossy_cobblestone_stairs", "mossy_cobblestone_wall"],
    "mossy_stone_bricks": ["mossy_stone_brick_slab", "mossy_stone_brick_stairs", "mossy_stone_brick_wall"],
    "sandstone": ["cut_sandstone", "chiseled_sandstone", "sandstone_slab", "sandstone_stairs",
                  "sandstone_wall", "cut_sandstone_slab"],
    "cut_sandstone": ["cut_sandstone_slab"],
    "red_sandstone": ["cut_red_sandstone", "chiseled_red_sandstone", "red_sandstone_slab",
                      "red_sandstone_stairs", "red_sandstone_wall", "cut_red_sandstone_slab"],
    "cut_red_sandstone": ["cut_red_sandstone_slab"],
    "smooth_sandstone": ["smooth_sandstone_slab", "smooth_sandstone_stairs"],
    "smooth_red_sandstone": ["smooth_red_sandstone_slab", "smooth_red_sandstone_stairs"],
    "smooth_quartz": ["smooth_quartz_slab", "smooth_quartz_stairs"],
    "quartz_block": ["chiseled_quartz_block", "quartz_pillar", "quartz_bricks", "quartz_slab", "quartz_stairs"],
    "bricks": ["brick_slab", "brick_stairs", "brick_wall"],
    "mud_bricks": ["mud_brick_slab", "mud_brick_stairs", "mud_brick_wall"],
    "nether_bricks": ["chiseled_nether_bricks", "nether_brick_slab", "nether_brick_stairs", "nether_brick_wall"],
    "red_nether_bricks": ["red_nether_brick_slab", "red_nether_brick_stairs", "red_nether_brick_wall"],
    "blackstone": ["polished_blackstone", "polished_blackstone_bricks", "chiseled_polished_blackstone",
                   "blackstone_slab", "blackstone_stairs", "blackstone_wall", "polished_blackstone_slab",
                   "polished_blackstone_stairs", "polished_blackstone_wall", "polished_blackstone_brick_slab",
                   "polished_blackstone_brick_stairs", "polished_blackstone_brick_wall"],
    "polished_blackstone": ["polished_blackstone_bricks", "chiseled_polished_blackstone",
                            "polished_blackstone_slab", "polished_blackstone_stairs", "polished_blackstone_wall",
                            "polished_blackstone_brick_slab", "polished_blackstone_brick_stairs",
                            "polished_blackstone_brick_wall"],
    "polished_blackstone_bricks": ["polished_blackstone_brick_slab", "polished_blackstone_brick_stairs",
                                   "polished_blackstone_brick_wall"],
    "cobbled_deepslate": ["polished_deepslate", "deepslate_bricks", "deepslate_tiles", "chiseled_deepslate",
                          "cobbled_deepslate_slab", "cobbled_deepslate_stairs", "cobbled_deepslate_wall",
                          "polished_deepslate_slab", "polished_deepslate_stairs", "polished_deepslate_wall",
                          "deepslate_brick_slab", "deepslate_brick_stairs", "deepslate_brick_wall",
                          "deepslate_tile_slab", "deepslate_tile_stairs", "deepslate_tile_wall"],
    "tuff": ["polished_tuff", "tuff_bricks", "chiseled_tuff", "chiseled_tuff_bricks", "tuff_slab",
             "tuff_stairs", "tuff_wall", "polished_tuff_slab", "polished_tuff_stairs", "polished_tuff_wall",
             "tuff_brick_slab", "tuff_brick_stairs", "tuff_brick_wall"],
    "andesite": ["polished_andesite", "andesite_slab", "andesite_stairs", "andesite_wall",
                 "polished_andesite_slab", "polished_andesite_stairs"],
    "diorite": ["polished_diorite", "diorite_slab", "diorite_stairs", "diorite_wall",
                "polished_diorite_slab", "polished_diorite_stairs"],
    "granite": ["polished_granite", "granite_slab", "granite_stairs", "granite_wall",
                "polished_granite_slab", "polished_granite_stairs"],
    "prismarine": ["prismarine_slab", "prismarine_stairs", "prismarine_wall"],
    "prismarine_bricks": ["prismarine_brick_slab", "prismarine_brick_stairs"],
    "dark_prismarine": ["dark_prismarine_slab", "dark_prismarine_stairs"],
    "purpur_block": ["purpur_pillar", "purpur_slab", "purpur_stairs"],
    "end_stone": ["end_stone_bricks", "end_stone_brick_slab", "end_stone_brick_stairs", "end_stone_brick_wall"],
    "end_stone_bricks": ["end_stone_brick_slab", "end_stone_brick_stairs", "end_stone_brick_wall"],
    "basalt": ["polished_basalt"],
    "resin_bricks": ["chiseled_resin_bricks", "resin_brick_slab", "resin_brick_stairs", "resin_brick_wall"],
    "bamboo_planks": [],
}
for prefix in ["", "waxed_"]:
    for stage in COPPER_STAGES:
        base = f"{prefix}{stage}copper" if stage else f"{prefix}copper_block"
        cut = f"{prefix}{stage}cut_copper"
        STONECUT_FAMILIES[base] = [(cut, 4), (f"{cut}_slab", 8), (f"{cut}_stairs", 4),
                                   (f"{prefix}{stage}chiseled_copper", 4)]
        STONECUT_FAMILIES[cut] = [f"{cut}_slab", f"{cut}_stairs", f"{prefix}{stage}chiseled_copper"]
        if stage:  # oxidation happens for free, so later stages never exceed earlier ones
            prev = COPPER_STAGES[COPPER_STAGES.index(stage) - 1]
            for kind in ["copper", "cut_copper", "cut_copper_slab", "cut_copper_stairs", "chiseled_copper",
                         "copper_bulb", "copper_door", "copper_trapdoor", "copper_grate", "copper_bars",
                         "copper_chain", "copper_chest", "copper_lantern", "copper_golem_statue",
                         "lightning_rod"]:
                src = f"{prefix}{prev}{kind}"
                if kind == "copper" and not prev:
                    src = f"{prefix}copper_block"
                recipe(f"{prefix}{stage}{kind}", 1, src)
for base, outs in STONECUT_FAMILIES.items():
    for o in outs:
        name, count = (o, None) if isinstance(o, str) else o
        if count is None:
            count = 2 if name.endswith("_slab") else 1
        recipe(name, count, base)

# ---------------------------------------------------------------- solve
INF = None
value = {n: v for n, v in RAW.items()}
for _ in range(200):
    changed = False
    for out, count, ins in RECIPES:
        if any(i not in value for i in ins):
            continue
        cost = sum(value[i] for i in ins) / count
        if out not in value or cost < value[out]:
            value[out] = cost
            changed = True
    if not changed:
        break
else:
    raise SystemExit("prices did not converge (a recipe cycle multiplies items)")

Q = Decimal("0.0001")
rounded = {n: (Decimal(v.numerator) / Decimal(v.denominator)).quantize(Q, rounding=ROUND_DOWN)
           for n, v in value.items()}
# Rounding inputs down can leave an output above the sum of its rounded inputs; lower such
# outputs until every recipe holds again (values only decrease, so this terminates).
for _ in range(200):
    changed = False
    for out, count, ins in RECIPES:
        if out in rounded and all(i in rounded for i in ins):
            limit = (sum(rounded[i] for i in ins) / count).quantize(Q, rounding=ROUND_DOWN)
            if rounded[out] > limit:
                rounded[out] = limit
                changed = True
    if not changed:
        break
else:
    raise SystemExit("rounding repair did not converge")

def price(n):
    return rounded[n]

# Verify the no-arbitrage invariant on the rounded prices that will be shipped.
violations = []
for out, count, ins in RECIPES:
    if out in value and all(i in value for i in ins) and price(out) > 0:
        if price(out) * count > sum(price(i) for i in ins):
            violations.append((out, count, ins))
if violations:
    for v in violations[:20]:
        print("ARBITRAGE", v, file=sys.stderr)
    raise SystemExit(f"{len(violations)} recipe(s) would sell for more than their inputs")

# ---------------------------------------------------------------- categorise & write
def category(n):
    if n in DISABLED:
        return "zz_disabled"
    if n.endswith("_ore") or n in ("ancient_debris",) or n.startswith("raw_") or n in (
            "coal_block", "iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block",
            "redstone_block", "netherite_block", "copper_block", "amethyst_block"):
        return "ores_and_storage"
    if any(n.startswith(w + "_") or n.startswith("stripped_" + w) for w in WOODS) or n.startswith("bamboo"):
        return "wood"
    if n in ("wheat", "hay_block", "melon", "pumpkin", "carved_pumpkin", "jack_o_lantern", "sugar_cane",
             "cactus", "bamboo", "nether_wart", "nether_wart_block", "kelp", "dried_kelp_block",
             "cocoa", "sweet_berry_bush", "cake", "honey_block", "honeycomb_block", "pitcher_plant",
             "torchflower", "cactus_flower"):
        return "farming"
    if any(n.startswith(c + "_") for c in COLORS) or n in ("terracotta", "glass", "glass_pane",
                                                           "tinted_glass", "candle", "shulker_box"):
        return "colored_and_glass"
    if "copper" in n or "lightning_rod" in n:
        return "copper"
    return "building_and_natural"

TITLES = {
    "ores_and_storage": "Ores, raw blocks and storage blocks (storage blocks are exactly 9x their item)",
    "wood": "Wood (renewable; planks, stairs and slabs derived from logs)",
    "farming": "Farming blocks (renewable; priced for sustainable farms)",
    "colored_and_glass": "Wool, carpets, terracotta, concrete, glass, beds, banners, candles",
    "copper": "Copper (oxidized stages never exceed unoxidized; cut copper is 1/4 of a block)",
    "building_and_natural": "Building blocks, natural blocks, plants, redstone and utility blocks",
    "zz_disabled": "Disabled: technical, unobtainable or identity-bearing materials",
}

entries = {}
unpriced = []
for n in block_items:
    if n in DISABLED:
        entries[n] = (None, DISABLED[n])
    elif n in value:
        p = price(n)
        if p <= 0:
            entries[n] = (None, "value rounds to zero; set a price manually if you want it sellable")
        else:
            entries[n] = (p, None)
    else:
        entries[n] = (None, "no default price (an ingredient has no value); set one manually")
        unpriced.append(n)

def fmt(p):
    s = format(p.normalize(), "f")
    return s

lines = [
    "# ExoEconomy /sell price catalogue",
    f"# Generated by tools/generate_prices.py from minecraft-data {VERSION} recipes and registries.",
    "#",
    "# Every vanilla block item has an explicit entry. Format:",
    "#   MATERIAL: <price>          sellable at <price> Shards per item",
    "#   MATERIAL: false            not sellable (the comment says why)",
    "#   MATERIAL: {price: 1.5, enabled: false}   keeps a price but turns it off",
    "#",
    "# Prices are in Shards per single item and are rounded down to the currency precision only",
    "# once, on the sale total. Defaults are derived so that no crafting, smelting or stonecutting",
    "# recipe produces more sell value than its inputs. /exoeconomy audit re-checks that against",
    "# the server's actual recipes after you edit prices.",
    "#",
    "# Only plain vanilla items are priced: renamed, enchanted, plugin-tagged, damaged or",
    "# filled items are rejected before this table is consulted (see config.yml selling.*).",
    f"catalogue-version: {VERSION}",
    "",
    "blocks:",
]
for cat in sorted(TITLES):
    names = [n for n in block_items if category(n) == cat]
    if not names:
        continue
    lines.append(f"  # ---- {TITLES[cat]}")
    for n in names:
        p, reason = entries[n]
        key = n.upper()
        if p is None:
            lines.append(f"  {key}: false # {reason}")
        else:
            lines.append(f"  {key}: {fmt(p)}")
lines += ["", "# Non-block items (crop and mob drops, ingots, gems). Remove an entry to make it unsellable.",
          "items:"]
for n in sorted(EXTRA_SELLABLE):
    if n in value and price(n) > 0 and n not in block_names:
        lines.append(f"  {n.upper()}: {fmt(price(n))}")
open(OUT, "w").write("\n".join(lines) + "\n")

priced = sum(1 for p, _ in entries.values() if p is not None)
print(f"block items: {len(block_items)}, priced: {priced}, disabled: {len(block_items) - priced}")
print("unpriced (no derivable value):", unpriced)
print(f"recipes checked: {len(RECIPES)}, arbitrage violations: 0")
