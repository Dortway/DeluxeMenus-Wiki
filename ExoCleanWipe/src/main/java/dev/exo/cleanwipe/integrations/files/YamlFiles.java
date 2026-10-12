package dev.exo.cleanwipe.integrations.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Plain YAML editing for other plugins' data files. Items saved by Bukkit are ordinary maps (with an {@code ==}
 * key), so they are carried through unchanged without being deserialised into ItemStacks; nothing about other
 * players' entries is reinterpreted.
 */
final class YamlFiles {

    private YamlFiles() {
    }

    private static Yaml yaml() {
        LoaderOptions lo = new LoaderOptions();
        lo.setCodePointLimit(64 * 1024 * 1024);
        lo.setMaxAliasesForCollections(1000);
        DumperOptions d = new DumperOptions();
        d.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        d.setIndent(2);
        d.setWidth(4096);
        d.setSplitLines(false);
        return new Yaml(new SafeConstructor(lo), new Representer(d), d, lo);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        Object o = yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        if (o == null) {
            return new LinkedHashMap<>();
        }
        if (!(o instanceof Map<?, ?>)) {
            throw new IOException(file + " is not a YAML mapping");
        }
        return (Map<String, Object>) o;
    }

    static String dump(Map<String, Object> root) {
        return yaml().dump(root);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> section(Map<String, Object> root, String key) {
        Object o = root.get(key);
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }
}
