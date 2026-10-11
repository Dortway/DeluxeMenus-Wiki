package net.exoeconomy.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fixed-point helpers for Shards amounts. Amounts are BigDecimal everywhere inside ExoEconomy;
 * they are converted to double only at the ExcellentEconomy boundary, after being rounded down to
 * the currency scale (0 for whole-number currencies, otherwise the configured number of decimals).
 */
public final class Money {
    /** Upper bound for a single operation. Keeps doubles exact at 2 decimals and blocks overflow abuse. */
    public static final BigDecimal MAX_OPERATION = new BigDecimal("1000000000000");
    /** Upper bound for any multiplier. */
    public static final BigDecimal MAX_MULTIPLIER = new BigDecimal("100");

    private Money() {}

    /** Parses a plain non-negative decimal ("12", "0.25"); rejects exponents, NaN, signs and blanks. */
    public static BigDecimal parse(String text) {
        if (text == null || !text.trim().matches("\\d{1,13}(\\.\\d{1,8})?"))
            throw new IllegalArgumentException("Not a valid non-negative amount: " + text);
        BigDecimal value = new BigDecimal(text.trim());
        if (value.compareTo(MAX_OPERATION) > 0) throw new IllegalArgumentException("Amount too large: " + text);
        return value;
    }

    /** Reads a YAML scalar (Integer, Double or String) as an amount. */
    public static BigDecimal fromConfig(Object raw, String path) {
        if (raw instanceof Number number) {
            double d = number.doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException(path + " must be a finite number");
            return parse(BigDecimal.valueOf(d).toPlainString());
        }
        if (raw instanceof String s) return parse(s);
        throw new IllegalArgumentException(path + " must be a number");
    }

    public static BigDecimal ofDouble(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite balance: " + value);
        return BigDecimal.valueOf(value);
    }

    /** Rounds toward zero, so rounding can never create Shards. */
    public static BigDecimal floor(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.DOWN);
    }

    public static boolean isPositiveOperation(BigDecimal amount) {
        return amount != null && amount.signum() > 0 && amount.compareTo(MAX_OPERATION) <= 0;
    }

    /** True when the two values are equal once rounded half-up to {@code scale} decimals. */
    public static boolean sameAtScale(BigDecimal a, BigDecimal b, int scale) {
        return a.setScale(scale, RoundingMode.HALF_UP).compareTo(b.setScale(scale, RoundingMode.HALF_UP)) == 0;
    }

    public static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** Validates a multiplier read from configuration: finite, at least 1, at most {@link #MAX_MULTIPLIER}. */
    public static BigDecimal multiplier(Object raw, String path) {
        BigDecimal value = fromConfig(raw, path);
        if (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(MAX_MULTIPLIER) > 0)
            throw new IllegalArgumentException(path + " must be between 1 and 100");
        return value;
    }
}
