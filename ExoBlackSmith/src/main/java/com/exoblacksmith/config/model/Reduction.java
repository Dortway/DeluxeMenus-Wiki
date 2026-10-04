package com.exoblacksmith.config.model;

/** A fractional damage reduction (0.15 = 15%) against one category. */
public record Reduction(DamageCategory category, double value) {
}
