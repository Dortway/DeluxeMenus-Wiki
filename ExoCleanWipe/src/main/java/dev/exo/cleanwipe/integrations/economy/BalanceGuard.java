package dev.exo.cleanwipe.integrations.economy;

import dev.exo.cleanwipe.storage.CoreStore;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;

/**
 * While an account is locked by a wipe, nobody can move currency in or out of it (payments from friends, island
 * withdrawals, shop payouts...). ExoCleanWipe's own resets are let through.
 */
public final class BalanceGuard implements Listener {

    private final CoreStore store;
    private final Logger logger;

    public BalanceGuard(CoreStore store, Logger logger) {
        this.store = store;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChange(ChangeBalanceEvent event) {
        UUID id = event.getUser().getId();
        if (ExcellentEconomyIntegration.OWN_WRITES.contains(id)) {
            return;
        }
        try {
            if (store.lockOf(id).filter(l -> l.jobId() > 0).isPresent() || store.lockOf(null).isPresent()) {
                event.setCancelled(true);
                logger.info("blocked a " + event.getCurrency().getId() + " balance change of locked account " + id);
            }
        } catch (Exception e) {
            event.setCancelled(true);
        }
    }
}
