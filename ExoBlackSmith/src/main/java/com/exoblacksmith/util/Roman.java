package com.exoblacksmith.util;

/** Lowercase roman numerals for tier/level display (1..10, falls back to digits). */
public final class Roman {
    private static final String[] NUMERALS = {"", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};

    private Roman() {
    }

    public static String of(int value) {
        return value > 0 && value < NUMERALS.length ? NUMERALS[value] : Integer.toString(value);
    }
}
