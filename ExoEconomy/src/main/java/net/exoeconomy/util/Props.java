package net.exoeconomy.util;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/** key=value text used for transaction details (readable in the database and admin tools). */
public final class Props {
    private Props() {}

    public static String of(String... pairs) {
        Properties p = new Properties();
        for (int i = 0; i + 1 < pairs.length; i += 2) p.setProperty(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        return write(p);
    }

    public static String write(Map<String, String> values) {
        Properties p = new Properties();
        values.forEach((k, v) -> p.setProperty(k, v == null ? "" : v));
        return write(p);
    }

    private static String write(Properties p) {
        StringWriter out = new StringWriter();
        try {
            // Sorted, comment-free output so records are stable and easy to read.
            for (String key : new TreeSet<>(p.stringPropertyNames())) {
                Properties one = new Properties();
                one.setProperty(key, p.getProperty(key));
                StringWriter line = new StringWriter();
                one.store(line, null);
                line.toString().lines().filter(l -> !l.startsWith("#")).forEach(l -> out.append(l).append('\n'));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    public static Map<String, String> read(String text) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(text == null ? "" : text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> out = new TreeMap<>();
        for (String k : p.stringPropertyNames()) out.put(k, p.getProperty(k));
        return out;
    }
}
