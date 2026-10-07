package dev.exo.dailyspinner.cooldown;

/**
 * Pure rolling-cooldown arithmetic. A player may take the daily spin once {@code cooldown}
 * milliseconds have elapsed since their previous daily spin.
 */
public final class CooldownCalculator {

    private CooldownCalculator() {
    }

    /**
     * @param lastDailySpin epoch millis of the last daily spin, or {@code null} if never spun / reset
     * @return milliseconds until the daily spin is available (0 when ready)
     */
    public static long remaining(Long lastDailySpin, long cooldownMillis, long now) {
        if (cooldownMillis <= 0 || lastDailySpin == null) {
            return 0L;
        }
        long elapsed = now - lastDailySpin;
        if (elapsed < 0) {
            // The system clock moved backwards. Never report more than one full cooldown.
            return cooldownMillis;
        }
        if (elapsed >= cooldownMillis) {
            return 0L;
        }
        return cooldownMillis - elapsed;
    }

    public static boolean isReady(Long lastDailySpin, long cooldownMillis, long now) {
        return remaining(lastDailySpin, cooldownMillis, now) == 0L;
    }
}
