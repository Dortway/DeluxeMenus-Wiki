package net.exoeconomy.core.booster;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;

/** How several simultaneous multipliers are combined into one. */
public enum CombineMode {
    /** 1 + sum(m - 1): 1.5x and 2x give 2.5x. Grows slowest; the default. */
    ADDITIVE,
    /** product(m): 1.5x and 2x give 3x. */
    MULTIPLICATIVE,
    /** max(m): only the strongest booster counts. */
    HIGHEST;

    public BigDecimal combine(Collection<BigDecimal> multipliers) {
        BigDecimal result = BigDecimal.ONE;
        for (BigDecimal m : multipliers) {
            result = switch (this) {
                case ADDITIVE -> result.add(m.subtract(BigDecimal.ONE));
                case MULTIPLICATIVE -> result.multiply(m, MathContext.DECIMAL64);
                case HIGHEST -> result.max(m);
            };
        }
        return result;
    }
}
