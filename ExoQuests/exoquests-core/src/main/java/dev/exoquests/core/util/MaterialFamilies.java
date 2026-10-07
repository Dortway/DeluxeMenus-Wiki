package dev.exoquests.core.util;

/**
 * Decides whether a block's current material is the same "thing" a placement mark was recorded for.
 * A mark whose material no longer matches is considered stale (the block was replaced by something
 * else, e.g. a generator output) and is ignored. Known in-place transformations keep the mark valid:
 * stripping a log, bamboo shoot growing into bamboo.
 */
public final class MaterialFamilies {

    private MaterialFamilies() {
    }

    public static boolean sameFamily(String marked, String current) {
        if (marked.equals(current)) {
            return true;
        }
        return normalize(marked).equals(normalize(current));
    }

    static String normalize(String material) {
        String m = material;
        if (m.startsWith("STRIPPED_")) {
            m = m.substring("STRIPPED_".length());
        }
        if (m.endsWith("_WOOD")) {
            m = m.substring(0, m.length() - "_WOOD".length()) + "_LOG";
        }
        if (m.endsWith("_HYPHAE")) {
            m = m.substring(0, m.length() - "_HYPHAE".length()) + "_STEM";
        }
        if (m.equals("BAMBOO_SAPLING")) {
            m = "BAMBOO";
        }
        return m;
    }
}
