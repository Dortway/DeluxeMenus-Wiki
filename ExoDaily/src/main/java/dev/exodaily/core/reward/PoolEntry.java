package dev.exodaily.core.reward;

/** A weighted reference from a pool to a reward. */
public record PoolEntry(String rewardId, int weight) {
}
