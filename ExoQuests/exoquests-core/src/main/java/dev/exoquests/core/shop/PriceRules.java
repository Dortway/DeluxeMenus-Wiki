package dev.exoquests.core.shop;

import java.util.OptionalInt;

/** Shop prices are whole numbers between {@link #MIN} and {@link #MAX} inclusive. */
public final class PriceRules {

    public static final int MIN = 10;
    public static final int MAX = 1_000;

    private PriceRules() {
    }

    public static boolean valid(long price) {
        return price >= MIN && price <= MAX;
    }

    /** Parses user input such as {@code "120"}; rejects decimals, signs, separators and out-of-range values. */
    public static OptionalInt parse(String input) {
        if (input == null || !input.matches("[0-9]{1,7}")) {
            return OptionalInt.empty();
        }
        int value = Integer.parseInt(input);
        return valid(value) ? OptionalInt.of(value) : OptionalInt.empty();
    }
}
