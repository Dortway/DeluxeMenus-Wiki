package com.exoblacksmith.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.potion.PotionEffectType;

/**
 * Typed, error-reporting view of a YAML section. Every getter records a problem (with the full key path)
 * instead of throwing, so one load pass reports every mistake at once.
 */
public final class Section {
    private final String file;
    private final ConfigurationSection raw;
    private final Problems problems;

    public Section(String file, ConfigurationSection raw, Problems problems) {
        this.file = file;
        this.raw = raw;
        this.problems = problems;
    }

    public String file() {
        return file;
    }

    public Problems problems() {
        return problems;
    }

    public String path(String key) {
        String base = raw.getCurrentPath();
        return base == null || base.isEmpty() ? key : base + "." + key;
    }

    public boolean has(String key) {
        return raw.contains(key);
    }

    public Set<String> keys() {
        return raw.getKeys(false);
    }

    public Section child(String key) {
        ConfigurationSection section = raw.getConfigurationSection(key);
        if (section == null) {
            problems.error(file, path(key), "missing section");
            return null;
        }
        return new Section(file, section, problems);
    }

    public Section optionalChild(String key) {
        ConfigurationSection section = raw.getConfigurationSection(key);
        return section == null ? null : new Section(file, section, problems);
    }

    public void error(String key, String message) {
        problems.error(file, path(key), message);
    }

    public void warn(String key, String message) {
        problems.warn(file, path(key), message);
    }

    public String string(String key) {
        Object value = raw.get(key);
        if (value == null) {
            error(key, "missing value");
            return "";
        }
        if (value instanceof ConfigurationSection || value instanceof List<?>) {
            error(key, "expected text");
            return "";
        }
        return String.valueOf(value);
    }

    public String string(String key, String fallback) {
        return raw.contains(key) ? string(key) : fallback;
    }

    public List<String> strings(String key) {
        if (!raw.contains(key)) {
            return List.of();
        }
        if (!raw.isList(key)) {
            if (raw.isString(key)) {
                return List.of(raw.getString(key));
            }
            error(key, "expected a list of text lines");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : raw.getList(key, List.of())) {
            out.add(String.valueOf(o));
        }
        return List.copyOf(out);
    }

    public boolean bool(String key, boolean fallback) {
        if (!raw.contains(key)) {
            return fallback;
        }
        if (!raw.isBoolean(key)) {
            error(key, "expected true or false");
            return fallback;
        }
        return raw.getBoolean(key);
    }

    public double number(String key, double fallback, double min, double max) {
        if (!raw.contains(key)) {
            return fallback;
        }
        Object value = raw.get(key);
        if (!(value instanceof Number number)) {
            error(key, "expected a number, got '" + value + "'");
            return fallback;
        }
        double d = number.doubleValue();
        if (Double.isNaN(d) || d < min || d > max) {
            error(key, "must be between " + trim(min) + " and " + trim(max) + ", got " + trim(d));
            return fallback;
        }
        return d;
    }

    public double requiredNumber(String key, double min, double max) {
        if (!raw.contains(key)) {
            error(key, "missing value");
            return min;
        }
        return number(key, min, min, max);
    }

    public int integer(String key, int fallback, int min, int max) {
        if (!raw.contains(key)) {
            return fallback;
        }
        Object value = raw.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            error(key, "expected a whole number, got '" + value + "'");
            return fallback;
        }
        long l = ((Number) value).longValue();
        if (l < min || l > max) {
            error(key, "must be between " + min + " and " + max + ", got " + l);
            return fallback;
        }
        return (int) l;
    }

    public Material material(String key, Material fallback) {
        if (!raw.contains(key)) {
            if (fallback == null) {
                error(key, "missing material");
            }
            return fallback;
        }
        String name = string(key);
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            error(key, "unknown item material '" + name + "'");
            return fallback == null ? Material.BARRIER : fallback;
        }
        return material;
    }

    public EntityType entityType(String key) {
        String name = string(key);
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            error(key, "unknown entity type '" + name + "'");
            return null;
        }
    }

    public PotionEffectType potion(String key, String name) {
        NamespacedKey nk = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        PotionEffectType type = nk == null ? null : Registry.EFFECT.get(nk);
        if (type == null) {
            error(key, "unknown potion effect '" + name + "'");
        }
        return type;
    }

    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
        if (!raw.contains(key)) {
            if (fallback == null) {
                error(key, "missing value");
            }
            return fallback;
        }
        return parseEnum(key, string(key), type, fallback);
    }

    public <E extends Enum<E>> E parseEnum(String key, String value, Class<E> type, E fallback) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            List<String> options = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                options.add(constant.name().toLowerCase(Locale.ROOT));
            }
            error(key, "unknown value '" + value + "', expected one of " + options);
            return fallback;
        }
    }

    public List<Section> sectionList(String key) {
        List<Section> out = new ArrayList<>();
        if (!raw.contains(key)) {
            return out;
        }
        List<?> list = raw.getList(key);
        if (list == null) {
            error(key, "expected a list");
            return out;
        }
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            if (entry instanceof java.util.Map<?, ?> map) {
                org.bukkit.configuration.MemoryConfiguration mem = new org.bukkit.configuration.MemoryConfiguration();
                ConfigurationSection s = mem.createSection(path(key) + "[" + i + "]", map);
                out.add(new Section(file, s, problems));
            } else {
                error(key + "[" + i + "]", "expected a key/value entry");
            }
        }
        return out;
    }

    public Object rawValue(String key) {
        return raw.get(key);
    }

    private static String trim(double d) {
        return d == Math.floor(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}
