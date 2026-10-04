#!/usr/bin/env python3
"""Generates placeholder pixel-art textures for the ItemsAdder content pack.

These are simple, deterministic placeholders so /iazip works and items are recognisable in game.
Replace any PNG with real art at the same path. Requires Pillow:  pip install pillow
Run from the project root:  python3 tools/generate_textures.py
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "itemsadder", "contents", "exoblacksmith", "textures")


def hexrgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


def save(img, rel):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def from_mask(rows, palette):
    """rows: list of strings; each char maps to a palette colour ('.' = transparent)."""
    h, w = len(rows), len(rows[0])
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and ch in palette:
                img.putpixel((x, y), palette[ch])
    return img


# ---------------------------------------------------------------- runes: stone tablet + glyph
TABLET = [
    "................",
    "....oooooooo....",
    "...oSSSSSSSSo...",
    "..oSssssssssSo..",
    "..oSsssssssssSo.",
    "..oSssssssssSo..",
    "..oSssssssssSo..",
    "..oSssssssssSo..",
    "..oSssssssssSo..",
    "..oSssssssssSo..",
    "..oSssssssssSo..",
    "..oSssssssssSo..",
    "..oDsssssssssDo.",
    "...oDDDDDDDDDo..",
    "....oooooooooo..",
    "................",
]
TABLET = [r[:16].ljust(16, ".") for r in TABLET]

GLYPHS = {  # 7x7 glyph drawn in the tablet centre (x 4..10, y 4..10)
    "blast_rune":      ["...g...", "g..g..g", ".g.g.g.", "..ggg..", ".g.g.g.", "g..g..g", "...g..."],
    "totem_surge":     ["..ggg..", ".g.g.g.", "..ggg..", "ggggggg", "..ggg..", "..g.g..", ".g...g."],
    "hardened_shell":  ["ggggggg", "g.....g", "g.ggg.g", "g.g.g.g", "g.ggg.g", ".g...g.", "..ggg.."],
    "kinetic_reducer": ["g.....g", ".g...g.", "..g.g..", "...g...", "..g.g..", ".g...g.", "g.....g"],
    "phoenix_aura":    ["...g...", "..ggg..", ".g.g.g.", "g..g..g", "...g...", "..g.g..", ".g...g."],
    "void_stride":     ["g......", "gg.....", ".gg....", "..ggg..", "....gg.", ".....gg", "......g"],
    "feather_ward":    ["....gg.", "...g.g.", "..g.gg.", ".g.gg..", ".ggg...", "gg.....", "g......"],
    "anchor_guard":    ["...g...", "..ggg..", "...g...", "...g...", "g..g..g", ".g.g.g.", "..ggg.."],
    "tidal_breath":    [".......", "gg..gg.", "..gg..g", ".......", "gg..gg.", "..gg..g", "......."],
}
RUNE_COLOR = {
    "blast_rune": "#FF7A3D", "totem_surge": "#F2D45C", "hardened_shell": "#7FD1A0",
    "kinetic_reducer": "#9FE6FF", "phoenix_aura": "#FF4F2E", "void_stride": "#B06BFF",
    "feather_ward": "#F0F0F0", "anchor_guard": "#8C9BB0", "tidal_breath": "#3FA9FF",
}
STONE = hexrgb("#3B3F4A")
for rune, color in RUNE_COLOR.items():
    c = hexrgb(color)
    img = from_mask(TABLET, {"o": hexrgb("#16181D"), "S": shade(STONE, 1.35), "s": STONE, "D": shade(STONE, 0.7)})
    for y, row in enumerate(GLYPHS[rune]):
        for x, ch in enumerate(row):
            if ch == "g":
                img.putpixel((4 + x, 4 + y), c)
                # soft glow
                for dx, dy in ((1, 0), (0, 1)):
                    px, py = 4 + x + dx, 4 + y + dy
                    if img.getpixel((px, py)) == STONE:
                        img.putpixel((px, py), shade(c, 0.45))
    save(img, f"item/runes/{rune}.png")

# ---------------------------------------------------------------- upgrade materials
SHARD = [
    "................",
    "........h.......",
    ".......hLh......",
    "......hLLLh.....",
    "......LLLLD.....",
    ".....hLLLLDh....",
    ".....LLLLDDD....",
    "....hLLLLDDDh...",
    "....LLLLLDDDD...",
    "....LLLLDDDDD...",
    ".....LLLDDDD....",
    ".....hLLDDDh....",
    "......LLDDD.....",
    ".......LDD......",
    "........D.......",
    "................",
]
STAR = [
    "................",
    ".......hh.......",
    ".......LL.......",
    "......hLLh......",
    "......LLLD......",
    "hhLLLLLLLDDDDDhh",
    ".hLLLLLLLDDDDDh.",
    "...LLLLLLDDDD...",
    "....LLLLLDDD....",
    "....LLLL.DDD....",
    "...LLLL...DDD...",
    "...LLL.....DD...",
    "..LLh.......DD..",
    "..Lh.........D..",
    "................",
    "................",
]
for name, shape, col in (("legendary_rune_upgrade", SHARD, "#FFB347"), ("fabled_rune_upgrade", STAR, "#FF5C8A")):
    c = hexrgb(col)
    save(from_mask(shape, {"h": shade(c, 1.4), "L": c, "D": shade(c, 0.65)}), f"item/upgrades/{name}.png")

# ---------------------------------------------------------------- mask totems: totem silhouette tinted per mask
TOTEM = [
    "................",
    "......hhhh......",
    ".....hLLLLD.....",
    ".....LeLLeD.....",
    ".....LLLLLD.....",
    ".....LLmmLD.....",
    "......DDDD......",
    "..hhLLLLLLLLDD..",
    "..LLLLLLLLLLDD..",
    "..DD.LLLLLD.DD..",
    ".....LLLLLD.....",
    ".....LLggLD.....",
    ".....LLLLLD.....",
    "......LLLD......",
    "......DDDD......",
    "................",
]
MASK_COLOR = {
    "sedge": "#E8E8E8", "wisdom": "#F2A0B0", "canapy": "#F5F7FF", "trident": "#5FA86A",
    "creepy": "#4FC34F", "syder": "#5A4A44", "guard": "#7A5638", "golom": "#C9C2B6",
    "stray": "#A9C4CC", "drownie": "#3E8E8C",
}
for mask, col in MASK_COLOR.items():
    c = hexrgb(col)
    img = from_mask(TOTEM, {"h": shade(c, 1.25), "L": c, "D": shade(c, 0.6), "e": hexrgb("#1B1B1B"),
                            "m": shade(c, 0.45), "g": hexrgb("#FFD24A")})
    save(img, f"item/totems/{mask}_mask_totem.png")

# ---------------------------------------------------------------- armor item icons
ICONS = {
    "helmet": [
        "................", "................", "................", "....hhhhhhhh....",
        "...hLLLLLLLLD...", "...LLLLLLLLLD...", "...LLtttttLLD...", "...LL.....LLD...",
        "...LL.....LLD...", "...DD.....DDD...", "................", "................",
        "................", "................", "................", "................"],
    "chestplate": [
        "................", "..hhh......hhh..", "..LLLh....hLLD..", "..LLLLhhhhLLLD..",
        "..LLLLLLLLLLLD..", "...LLLLttLLLD...", "....LLLttLLD....", "....LLLttLLD....",
        "....LLLLLLLD....", "....LLLLLLLD....", "....LLLLLLLD....", "....LLLLLLLD....",
        "....DDDDDDDD....", "................", "................", "................"],
    "leggings": [
        "................", "................", "....hhhhhhhh....", "....LLttttLD....",
        "....LLLLLLLD....", "....LLL..LLD....", "....LLD..LLD....", "....LLD..LLD....",
        "....LLD..LLD....", "....LLD..LLD....", "....LLD..LLD....", "....DDD..DDD....",
        "................", "................", "................", "................"],
    "boots": [
        "................", "................", "................", "................",
        "................", "................", "...hhh....hhh...", "...LLD....LLD...",
        "...LLD....LLD...", "...LLD....LLD...", "..LLLD...LLLD...", "..ttLD...ttLD...",
        "..DDDD...DDDD...", "................", "................", "................"],
}
SETS = {"emberforged": ("#C8432B", "#FFB347"), "riftguard": ("#5B3C8C", "#C77DFF"), "stormstride": ("#8FB8C8", "#E8F7FF")}
for set_id, (base, trim) in SETS.items():
    b, t = hexrgb(base), hexrgb(trim)
    for piece, rows in ICONS.items():
        save(from_mask(rows, {"h": shade(b, 1.35), "L": b, "D": shade(b, 0.6), "t": t}), f"item/armor/{set_id}_{piece}.png")

# ---------------------------------------------------------------- armor layers (vanilla 64x32 humanoid armor layout)
def layer(base, trim, rects):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    for (x0, y0, x1, y1) in rects:
        for y in range(y0, y1):
            for x in range(x0, x1):
                edge = x in (x0, x1 - 1) or y in (y0, y1 - 1)
                checker = ((x // 2) + (y // 2)) % 2 == 0
                col = shade(base, 0.6) if edge else (base if checker else shade(base, 0.88))
                img.putpixel((x, y), col)
        # trim line through the middle of each region
        my = (y0 + y1) // 2
        for x in range(x0 + 1, x1 - 1):
            img.putpixel((x, my), trim)
    return img


HEAD = (0, 0, 32, 16)
BODY = (16, 16, 40, 32)
ARM = (40, 16, 56, 32)
LEG = (0, 16, 16, 32)
for set_id, (base, trim) in SETS.items():
    b, t = hexrgb(base), hexrgb(trim)
    save(layer(b, t, [HEAD, BODY, ARM, LEG]), f"armor/{set_id}_layer_1.png")  # helmet, chestplate, boots
    save(layer(b, t, [BODY, LEG]), f"armor/{set_id}_layer_2.png")             # leggings

# ---------------------------------------------------------------- rarity font images (9x9 gems)
GEM = [
    "....h....",
    "...hLh...",
    "..hLLLD..",
    ".hLLLLLD.",
    "hLLLLLLLD",
    ".LLLLLLD.",
    "..LLLLD..",
    "...LLD...",
    "....D....",
]
RARITY = {"common": "#B8C1CC", "uncommon": "#6BD66B", "rare": "#4AA8FF", "epic": "#B05CFF",
          "legendary": "#FFB347", "fabled": "#FF5C8A"}
for r, col in RARITY.items():
    c = hexrgb(col)
    save(from_mask(GEM, {"h": shade(c, 1.35), "L": c, "D": shade(c, 0.6)}), f"font/rarity_{r}.png")

count = sum(len(f) for _, _, f in os.walk(OUT))
print(f"wrote {count} textures to {OUT}")
