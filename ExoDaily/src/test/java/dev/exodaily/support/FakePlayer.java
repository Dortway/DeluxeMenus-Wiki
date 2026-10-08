package dev.exodaily.support;

import dev.exodaily.core.claim.ClaimParticipant;
import dev.exodaily.core.reward.RewardDefinition;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scriptable claim participant. The inventory is modelled as a number of free item units; a
 * reward fits if its amount does not exceed them. Delivery is all-or-nothing like the real one.
 */
public final class FakePlayer implements ClaimParticipant {

    public volatile boolean online = true;
    public volatile boolean premium;
    public volatile int freeSpace = 10_000;
    public volatile boolean invalidItems;
    public volatile boolean deliveryUncertain;
    /** Runs after the space check (before the DELIVERING mark). */
    public volatile Runnable afterCheck = () -> { };
    /** Runs after items were given (before the DELIVERED mark). */
    public volatile Runnable afterDeliver = () -> { };
    /** Runs at the start of delivery (after the DELIVERING mark). */
    public volatile Runnable beforeDeliver = () -> { };
    public final List<String> received = new CopyOnWriteArrayList<>();

    public FakePlayer premium(boolean premium) {
        this.premium = premium;
        return this;
    }

    @Override
    public boolean isOnline() {
        return online;
    }

    @Override
    public boolean hasPremium() {
        return premium;
    }

    @Override
    public DeliveryCheck check(RewardDefinition reward) {
        DeliveryCheck result = invalidItems ? DeliveryCheck.INVALID_ITEM
                : reward.amount() <= freeSpace ? DeliveryCheck.OK : DeliveryCheck.NO_SPACE;
        afterCheck.run();
        return result;
    }

    @Override
    public synchronized DeliveryResult deliver(RewardDefinition reward) {
        beforeDeliver.run();
        if (deliveryUncertain) {
            return DeliveryResult.UNCERTAIN;
        }
        if (invalidItems) {
            return DeliveryResult.NOT_DELIVERED_INVALID;
        }
        if (reward.amount() > freeSpace) {
            return DeliveryResult.NOT_DELIVERED_NO_SPACE;
        }
        freeSpace -= reward.amount();
        received.add(reward.id());
        afterDeliver.run();
        return DeliveryResult.DELIVERED;
    }
}
