package dev.exoquests.core.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable, ordered set of shop entries. */
public final class ShopCatalog {

    private final Map<String, ShopEntry> entries;

    public ShopCatalog(List<ShopEntry> entries) {
        Map<String, ShopEntry> map = new LinkedHashMap<>();
        for (ShopEntry e : entries) {
            if (map.put(e.id(), e) != null) {
                throw new IllegalArgumentException("duplicate shop id " + e.id());
            }
        }
        this.entries = Collections.unmodifiableMap(map);
    }

    public Optional<ShopEntry> get(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    public List<ShopEntry> all() {
        return List.copyOf(entries.values());
    }

    public List<ShopEntry> visible() {
        return entries.values().stream().filter(ShopEntry::enabled).toList();
    }

    public ShopCatalog with(ShopEntry entry) {
        List<ShopEntry> list = new ArrayList<>(entries.values());
        list.removeIf(e -> e.id().equals(entry.id()));
        list.add(entry);
        return new ShopCatalog(list);
    }

    public ShopCatalog replace(ShopEntry entry) {
        List<ShopEntry> list = new ArrayList<>();
        for (ShopEntry e : entries.values()) {
            list.add(e.id().equals(entry.id()) ? entry : e);
        }
        return new ShopCatalog(list);
    }

    public ShopCatalog without(String id) {
        List<ShopEntry> list = new ArrayList<>(entries.values());
        list.removeIf(e -> e.id().equals(id));
        return new ShopCatalog(list);
    }
}
