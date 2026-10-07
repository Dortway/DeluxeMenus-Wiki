package dev.exoquests.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;

/** Strict, safe YAML loading: no arbitrary types and no duplicate keys. */
public final class YamlFiles {

    private YamlFiles() {
    }

    private static Yaml loader() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(8 * 1024 * 1024);
        return new Yaml(new SafeConstructor(options));
    }

    public static ConfigNode load(Path file, ConfigErrors errors) {
        String name = file.getFileName().toString();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(name, reader, errors);
        } catch (IOException e) {
            errors.add(name, "cannot read file: " + e.getMessage());
            return ConfigNode.empty(name, errors);
        }
    }

    public static ConfigNode load(String name, InputStream in, ConfigErrors errors) {
        try (Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(name, reader, errors);
        } catch (IOException e) {
            errors.add(name, "cannot read: " + e.getMessage());
            return ConfigNode.empty(name, errors);
        }
    }

    public static ConfigNode parse(String name, String text, ConfigErrors errors) {
        return parse(name, new StringReader(text), errors);
    }

    @SuppressWarnings("unchecked")
    private static ConfigNode parse(String name, Reader reader, ConfigErrors errors) {
        try {
            Object root = loader().load(reader);
            if (root == null) {
                return ConfigNode.empty(name, errors);
            }
            if (!(root instanceof Map<?, ?> map)) {
                errors.add(name, "top level must be a mapping");
                return ConfigNode.empty(name, errors);
            }
            return new ConfigNode(name, (Map<String, Object>) map, errors);
        } catch (YAMLException e) {
            errors.add(name, "invalid YAML: " + e.getMessage().replace('\n', ' '));
            return ConfigNode.empty(name, errors);
        } catch (ClassCastException e) {
            errors.add(name, "invalid structure: " + e.getMessage());
            return ConfigNode.empty(name, errors);
        }
    }

    /** Writes a YAML document atomically (temp file + move) with a leading comment header. */
    public static void writeAtomically(Path file, String header, Map<String, Object> data) throws IOException {
        DumperOptions dump = new DumperOptions();
        dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dump.setIndent(2);
        dump.setWidth(160);
        dump.setSplitLines(false);
        Yaml yaml = new Yaml(new Representer(dump), dump);
        String body = yaml.dump(new LinkedHashMap<>(data));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, header + body, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
