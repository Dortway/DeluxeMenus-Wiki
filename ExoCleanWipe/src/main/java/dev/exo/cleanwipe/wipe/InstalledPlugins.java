package dev.exo.cleanwipe.wipe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Names of the plugins installed in {@code plugins/}, read from each jar's {@code plugin.yml} / {@code paper-plugin.yml}.
 *
 * <p>ExoCleanWipe's startup integrations run in its {@code onLoad}, which happens before the plugins it edits (it is
 * declared {@code loadbefore} them). At that moment those plugins are not necessarily registered with the plugin
 * manager yet, so asking the plugin manager would wrongly report them as missing. The jars on disk are the truth.
 */
public final class InstalledPlugins {

    private InstalledPlugins() {
    }

    public static Set<String> scan(Path pluginsDir) {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(pluginsDir)) {
            return names;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path jar : ds) {
                String name = nameOf(jar);
                if (name != null) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException ignored) {
            // an unreadable folder means nothing can be confirmed as installed
        }
        return names;
    }

    static String nameOf(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String descriptor : new String[]{"paper-plugin.yml", "plugin.yml"}) {
                ZipEntry e = zip.getEntry(descriptor);
                if (e == null) {
                    continue;
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("name:")) {
                            return line.substring(5).replace("\"", "").replace("'", "").trim();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // not a readable jar
        }
        return null;
    }
}
