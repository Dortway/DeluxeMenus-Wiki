package dev.exo.cleanwipe.punish;

import dev.exo.cleanwipe.identity.ProxyTrust;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.IpEntry;
import dev.exo.cleanwipe.storage.Records.Punishment;
import dev.exo.cleanwipe.storage.Records.PunishmentKind;
import dev.exo.cleanwipe.text.Messages;
import dev.exo.cleanwipe.util.Durations;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * Punishments owned by ExoCleanWipe. They live only in ExoCleanWipe's database and are enforced by its own login
 * check, so the unpunish commands can never touch bans made by other plugins or by vanilla /ban, and other plugins'
 * records are never modified.
 */
public final class PunishmentService {

    private final CoreStore store;
    private final Messages messages;
    private final String referencePrefix;
    private final String appeal;
    private final boolean neverBanPrivate;

    public PunishmentService(CoreStore store, Messages messages, String referencePrefix, String appeal, boolean neverBanPrivate) {
        this.store = store;
        this.messages = messages;
        this.referencePrefix = referencePrefix;
        this.appeal = appeal;
        this.neverBanPrivate = neverBanPrivate;
    }

    public String reference(long id) {
        return referencePrefix + String.format("%06d", id);
    }

    /** Temporary (or permanent) account punishment; returns its id. Runs inside the caller's transaction. */
    public long createAccount(Connection c, UUID uuid, String name, String reason, UUID actor, String actorName, long now,
                              Duration duration, Long jobId) throws SQLException {
        Long expires = duration.equals(Durations.PERMANENT) ? null : now + duration.toMillis();
        return store.insertPunishment(c, PunishmentKind.ACCOUNT, uuid, name, reason, actor, actorName, now, expires, jobId);
    }

    public record IpPlan(List<String> banned, List<String> skipped, List<IpEntry> otherAccounts) {
    }

    /** Which of the player's known addresses would be banned, and which other accounts used them. */
    public IpPlan planIp(UUID uuid, String currentIp) throws SQLException {
        Set<String> ips = new LinkedHashSet<>();
        if (currentIp != null) {
            ips.add(currentIp);
        }
        for (IpEntry e : store.ipsOf(uuid)) {
            ips.add(e.ip());
        }
        List<String> banned = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<IpEntry> others = new ArrayList<>();
        for (String ip : ips) {
            if (neverBanPrivate && ProxyTrust.isPrivate(ip)) {
                skipped.add(ip);
                continue;
            }
            banned.add(ip);
            for (IpEntry o : store.accountsOnIp(ip)) {
                if (!o.uuid().equals(uuid)) {
                    others.add(o);
                }
            }
        }
        return new IpPlan(banned, skipped, others);
    }

    /** Permanent account ban plus IP bans, grouped as one IP_GROUP punishment. */
    public long createIpGroup(UUID uuid, String name, String reason, UUID actor, String actorName, long now, List<String> ips)
            throws SQLException {
        return store.transaction(c -> {
            long id = store.insertPunishment(c, PunishmentKind.IP_GROUP, uuid, name, reason, actor, actorName, now, null, null);
            for (String ip : ips) {
                store.addPunishedIp(c, id, ip);
            }
            return id;
        });
    }

    /** Removes ACCOUNT punishments of a player. IP_GROUP punishments are left alone. */
    public int removeAccount(UUID uuid, String by, long now) throws SQLException {
        int n = 0;
        for (Punishment p : store.activePunishments(uuid)) {
            if (p.kind() == PunishmentKind.ACCOUNT && store.deactivate(p.id(), by, "unpunish", now)) {
                n++;
            }
        }
        return n;
    }

    public record IpRemoval(int punishments, int addresses, int released) {
    }

    /** Removes the player's IP_GROUP punishments, their addresses and the accounts they restricted. */
    public IpRemoval removeIpGroup(UUID uuid, String by, long now) throws SQLException {
        int punishments = 0;
        int addresses = 0;
        int released = 0;
        for (Punishment p : store.activePunishments(uuid)) {
            if (p.kind() == PunishmentKind.IP_GROUP && store.deactivate(p.id(), by, "unippunish", now)) {
                punishments++;
                addresses += store.punishedIps(p.id()).size();
                released += store.releaseRestricted(p.id());
            }
        }
        return new IpRemoval(punishments, addresses, released);
    }

    /** The punishment that keeps this account out right now, if any. */
    public Optional<Punishment> accountBlock(UUID uuid, long now) throws SQLException {
        for (Punishment p : store.activePunishments(uuid)) {
            if (p.inForce(now)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> addressBlock(String ip, long now) throws SQLException {
        if (ip == null) {
            return Optional.empty();
        }
        return store.activeIpPunishment(ip).filter(p -> p.inForce(now));
    }

    public Component banScreen(Punishment p, long now) {
        String duration = p.permanent() ? "permanent" : Durations.format(Duration.ofMillis(p.expiresAt() - p.createdAt()), 2);
        String remaining = p.permanent() ? "permanent" : Durations.format(Duration.ofMillis(Math.max(0, p.expiresAt() - now)), 2);
        return messages.screen("ban-screen", Messages.values("reason", p.reason(), "duration", duration, "remaining", remaining,
                "ref", reference(p.id()), "appeal", appeal));
    }

    public Component ipBanScreen(Punishment p) {
        return messages.screen("ip-ban-screen", Messages.values("reason", p.reason(), "ref", reference(p.id()), "appeal", appeal));
    }
}
