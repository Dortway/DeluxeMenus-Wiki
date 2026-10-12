package dev.exo.cleanwipe.punish;

import dev.exo.cleanwipe.config.Settings;
import dev.exo.cleanwipe.identity.ProxyTrust;
import dev.exo.cleanwipe.identity.VpnService;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.Lock;
import dev.exo.cleanwipe.storage.Records.Punishment;
import dev.exo.cleanwipe.text.Messages;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;

/**
 * Decides on the async pre-login thread whether a connection may continue. Order: record identity, season-reset
 * lock, account lock (running or partial wipe), account punishment, address punishment, VPN check. When the
 * database cannot be read the connection is refused (fail closed) rather than letting a banned player in.
 */
public final class LoginGate implements Listener {

    private final CoreStore store;
    private final PunishmentService punishments;
    private final Messages messages;
    private final Settings settings;
    private final ProxyTrust trust;
    private final VpnService vpn;
    private final Consumer<String> staffAlert;
    private final Logger logger;

    public LoginGate(CoreStore store, PunishmentService punishments, Messages messages, Settings settings, ProxyTrust trust,
                     VpnService vpn, Consumer<String> staffAlert, Logger logger) {
        this.store = store;
        this.punishments = punishments;
        this.messages = messages;
        this.settings = settings;
        this.trust = trust;
        this.vpn = vpn;
        this.staffAlert = staffAlert;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Component deny = decide(event.getUniqueId(), event.getName(), trust.usable(event.getAddress()), System.currentTimeMillis());
        if (deny != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, deny);
        }
    }

    /**
     * Last line of defence for a lock created between pre-login and the end of the login (Paper's replacement for
     * the deprecated PlayerLoginEvent).
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onValidate(PlayerConnectionValidateLoginEvent event) {
        if (!event.isAllowed()) {
            return;
        }
        UUID id = null;
        if (event.getConnection() instanceof PlayerLoginConnection login && login.getAuthenticatedProfile() != null) {
            id = login.getAuthenticatedProfile().getId();
        } else if (event.getConnection() instanceof PlayerConfigurationConnection config && config.getProfile() != null) {
            id = config.getProfile().getId();
        }
        if (id == null) {
            return;
        }
        try {
            Optional<Lock> lock = store.lockOf(id);
            Optional<Lock> global = store.lockOf(null);
            if (lock.isPresent()) {
                event.kickMessage(messages.screen("lock-screen", Messages.values("ref", "job " + lock.get().jobId())));
            } else if (global.isPresent() && !settings.wipeAllAllowJoin.contains(id)) {
                event.kickMessage(messages.screen("wipeall-screen"));
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "lock check failed at login", e);
            event.kickMessage(messages.screen("vpn-error-screen"));
        }
    }

    /** @return the screen to show, or null to allow */
    public Component decide(UUID uuid, String name, String ip, long now) {
        boolean known;
        try {
            known = store.isKnown(uuid);
            store.recordLogin(uuid, name, settings.trackIps ? ip : null, now);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "could not record login of " + name, e);
            known = true;
        }
        try {
            Optional<Lock> global = store.lockOf(null);
            if (global.isPresent() && !settings.wipeAllAllowJoin.contains(uuid)) {
                return messages.screen("wipeall-screen");
            }
            // a ban shows its reason and length even while the wipe of that punishment is still running
            Optional<Punishment> account = punishments.accountBlock(uuid, now);
            if (account.isPresent()) {
                return punishments.banScreen(account.get(), now);
            }
            Optional<Lock> lock = store.lockOf(uuid);
            if (lock.isPresent()) {
                return messages.screen("lock-screen", Messages.values("ref", "job " + lock.get().jobId()));
            }
            Optional<Punishment> address = punishments.addressBlock(ip, now);
            if (address.isPresent()) {
                store.recordRestricted(address.get().id(), uuid, name, ip, now);
                staffAlert.accept(name + " was refused: their address is covered by " + punishments.reference(address.get().id())
                        + " (" + address.get().name() + ")");
                return punishments.ipBanScreen(address.get());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "punishment check failed for " + name + "; refusing the connection", e);
            return messages.screen("vpn-error-screen");
        }
        if (vpn != null && ip != null && !settings.vpnExempt.contains(uuid) && (settings.vpnCheckAll || !known)) {
            VpnService.Result r = vpn.check(ip);
            switch (r.verdict()) {
                case VPN -> {
                    staffAlert.accept(Messages.plain(messages.line(settings.messages.getOrDefault("vpn-flag", "<name> vpn <detail>"),
                            Messages.values("name", name, "detail", r.detail()))));
                    if (settings.vpnDeny) {
                        return messages.screen("vpn-screen", Messages.values("appeal", settings.appeal));
                    }
                }
                case ERROR -> {
                    logger.warning("vpn check for " + name + " failed: " + r.detail());
                    if (settings.vpnOnError.equals("deny") || (settings.vpnOnError.equals("deny-new") && !known)) {
                        return messages.screen("vpn-error-screen");
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }
}
