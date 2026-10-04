package com.exoblacksmith.craft;

import java.util.List;
import java.util.Map;

/**
 * Result of planning a craft against a snapshot of an inventory.
 *
 * @param status       outcome
 * @param contents     the full storage contents to apply on success (null otherwise)
 * @param consumed     the stacks (with consumed amounts) removed, for bookkeeping such as retiring UIDs
 * @param missing      requirement key to missing amount, when {@code status == MISSING}
 */
public record CraftPlan<S, K>(Status status, List<S> contents, List<S> consumed, Map<K, Integer> missing) {
    public enum Status { OK, MISSING, NO_SPACE }

    public boolean ok() {
        return status == Status.OK;
    }
}
