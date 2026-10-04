package com.exoblacksmith.craft;

/** Abstraction over item stacks so the planner can be tested without a server. */
public interface StackOps<S, K> {
    boolean isEmpty(S stack);

    int amount(S stack);

    int maxStack(S stack);

    /** A copy of {@code stack} with a new amount. */
    S withAmount(S stack, int amount);

    /** True if both stacks can merge into one stack. */
    boolean similar(S a, S b);

    /** True if {@code stack} is an authentic match for requirement {@code key}. */
    boolean matches(S stack, K key);
}
