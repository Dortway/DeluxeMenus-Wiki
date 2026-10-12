package dev.exo.cleanwipe.identity;

import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.storage.Records.Account;
import dev.exo.cleanwipe.storage.Records.IpEntry;
import dev.exo.cleanwipe.storage.Records.NameEntry;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accounts that connected from the same addresses as a player. This is evidence for staff to weigh, not proof:
 * households, schools, dorms, mobile carriers (CGNAT) and public networks put unrelated people behind one address.
 * Nothing here is ever acted on automatically.
 */
public final class LinkReport {

    public record Linked(UUID uuid, String name, int sharedAddresses, long lastSeen, boolean onSharedNetworkOnly) {
    }

    public record AddressInfo(String ip, int accounts, boolean sharedNetwork, long lastSeen) {
    }

    public record Report(UUID uuid, String name, List<NameEntry> names, List<AddressInfo> addresses, List<Linked> linked) {
    }

    private LinkReport() {
    }

    public static Report build(CoreStore store, UUID uuid, int sharedThreshold) throws SQLException {
        String name = store.account(uuid).map(Account::name).orElse(uuid.toString());
        List<AddressInfo> addresses = new ArrayList<>();
        Map<UUID, int[]> sharedCount = new LinkedHashMap<>();
        Map<UUID, Boolean> onlyShared = new LinkedHashMap<>();
        for (IpEntry mine : store.ipsOf(uuid)) {
            List<IpEntry> others = store.accountsOnIp(mine.ip());
            boolean shared = others.size() > sharedThreshold;
            addresses.add(new AddressInfo(mine.ip(), others.size(), shared, mine.lastSeen()));
            for (IpEntry o : others) {
                if (o.uuid().equals(uuid)) {
                    continue;
                }
                sharedCount.computeIfAbsent(o.uuid(), k -> new int[1])[0]++;
                onlyShared.merge(o.uuid(), shared, (a, b) -> a && b);
            }
        }
        List<Linked> linked = new ArrayList<>();
        for (Map.Entry<UUID, int[]> e : sharedCount.entrySet()) {
            Account a = store.account(e.getKey()).orElse(new Account(e.getKey(), e.getKey().toString(), 0, 0));
            linked.add(new Linked(e.getKey(), a.name(), e.getValue()[0], a.lastSeen(), onlyShared.getOrDefault(e.getKey(), false)));
        }
        linked.sort(Comparator.comparing(Linked::onSharedNetworkOnly)
                .thenComparing(Comparator.comparingInt(Linked::sharedAddresses).reversed())
                .thenComparing(Comparator.comparingLong(Linked::lastSeen).reversed()));
        return new Report(uuid, name, store.nameHistory(uuid), addresses, linked);
    }
}
