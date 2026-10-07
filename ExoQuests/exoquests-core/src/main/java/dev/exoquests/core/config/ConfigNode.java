package dev.exoquests.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only view over a parsed YAML mapping that records type and range problems into
 * {@link ConfigErrors} instead of throwing, returning the supplied fallback for invalid values.
 */
public final class ConfigNode {

    private final String path;
    private final Map<String, Object> values;
    private final ConfigErrors errors;

    ConfigNode(String path, Map<String, Object> values, ConfigErrors errors) {
        this.path = path;
        this.values = values;
        this.errors = errors;
    }

    public static ConfigNode empty(String path, ConfigErrors errors) {
        return new ConfigNode(path, Collections.emptyMap(), errors);
    }

    public static ConfigNode of(String path, Map<String, Object> values, ConfigErrors errors) {
        return new ConfigNode(path, values, errors);
    }

    public String path() {
        return path;
    }

    public ConfigErrors errors() {
        return errors;
    }

    public String child(String key) {
        return path + "." + key;
    }

    public boolean has(String key) {
        return values.containsKey(key) && values.get(key) != null;
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public Map<String, Object> raw() {
        return values;
    }

    public Object get(String key) {
        return values.get(key);
    }

    @SuppressWarnings("unchecked")
    public ConfigNode section(String key) {
        Object v = values.get(key);
        if (v == null) {
            return new ConfigNode(child(key), Collections.emptyMap(), errors);
        }
        if (v instanceof Map<?, ?> map) {
            for (Object k : map.keySet()) {
                if (!(k instanceof String)) {
                    errors.add(child(key), "keys must be text, found " + k);
                    return new ConfigNode(child(key), Collections.emptyMap(), errors);
                }
            }
            return new ConfigNode(child(key), (Map<String, Object>) map, errors);
        }
        errors.add(child(key), "must be a section");
        return new ConfigNode(child(key), Collections.emptyMap(), errors);
    }

    /** Child sections in file order; non-section children are reported. */
    public Map<String, ConfigNode> sections() {
        Map<String, ConfigNode> out = new LinkedHashMap<>();
        for (String key : values.keySet()) {
            Object v = values.get(key);
            if (v instanceof Map<?, ?>) {
                out.put(key, section(key));
            } else {
                errors.add(child(key), "must be a section");
            }
        }
        return out;
    }

    public String string(String key, String fallback) {
        Object v = values.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        errors.add(child(key), "must be text");
        return fallback;
    }

    public String requiredString(String key) {
        if (!has(key)) {
            errors.add(child(key), "is required");
            return "";
        }
        String s = string(key, "");
        if (s.isBlank()) {
            errors.add(child(key), "must not be empty");
        }
        return s;
    }

    public boolean bool(String key, boolean fallback) {
        Object v = values.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        errors.add(child(key), "must be true or false");
        return fallback;
    }

    /** Reads a whole number in {@code [min, max]}; decimals, text and out-of-range values are errors. */
    public long integer(String key, long fallback, long min, long max) {
        Object v = values.get(key);
        if (v == null) {
            return fallback;
        }
        long value;
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            value = ((Number) v).longValue();
        } else if (v instanceof java.math.BigInteger) {
            errors.add(child(key), "number is too large");
            return fallback;
        } else {
            errors.add(child(key), "must be a whole number, found '" + v + "'");
            return fallback;
        }
        if (value < min || value > max) {
            errors.add(child(key), "must be between " + min + " and " + max + ", found " + value);
            return fallback;
        }
        return value;
    }

    public long requiredInteger(String key, long min, long max) {
        if (!has(key)) {
            errors.add(child(key), "is required");
            return min;
        }
        return integer(key, min, min, max);
    }

    public double decimal(String key, double fallback, double min, double max) {
        Object v = values.get(key);
        if (v == null) {
            return fallback;
        }
        if (!(v instanceof Number n)) {
            errors.add(child(key), "must be a number");
            return fallback;
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || d < min || d > max) {
            errors.add(child(key), "must be between " + min + " and " + max);
            return fallback;
        }
        return d;
    }

    public List<String> stringList(String key) {
        Object v = values.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return List.of(s);
        }
        if (!(v instanceof List<?> list)) {
            errors.add(child(key), "must be a list");
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof String s) {
                out.add(s);
            } else if (o instanceof Number || o instanceof Boolean) {
                out.add(String.valueOf(o));
            } else {
                errors.add(child(key) + "[" + i + "]", "must be text");
            }
        }
        return out;
    }

    public List<Long> integerList(String key, long min, long max) {
        Object v = values.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            errors.add(child(key), "must be a list of whole numbers");
            return List.of();
        }
        List<Long> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o instanceof Integer || o instanceof Long) {
                long l = ((Number) o).longValue();
                if (l < min || l > max) {
                    errors.add(child(key) + "[" + i + "]", "must be between " + min + " and " + max);
                } else {
                    out.add(l);
                }
            } else {
                errors.add(child(key) + "[" + i + "]", "must be a whole number");
            }
        }
        return out;
    }
}
