package com.exoblacksmith.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One installed rune on an armor piece. */
public record RuneSlot(String runeId, int tier) {

    public static final int MAX_SLOTS = 3;

    public RuneSlot {
        if (runeId == null || !runeId.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("invalid rune id: " + runeId);
        }
        if (tier < 1) {
            throw new IllegalArgumentException("invalid rune tier: " + tier);
        }
    }

    public static String encode(List<RuneSlot> runes) {
        StringBuilder sb = new StringBuilder();
        for (RuneSlot rune : runes) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(rune.runeId()).append(':').append(rune.tier());
        }
        return sb.toString();
    }

    /** Parses the canonical encoding; throws IllegalArgumentException on any malformed entry. */
    public static List<RuneSlot> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        List<RuneSlot> out = new ArrayList<>();
        for (String part : encoded.split(",", -1)) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("malformed rune entry: " + part);
            }
            out.add(new RuneSlot(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1))));
        }
        if (out.size() > MAX_SLOTS) {
            throw new IllegalArgumentException("too many runes: " + out.size());
        }
        return Collections.unmodifiableList(out);
    }
}
