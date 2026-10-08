package dev.exodaily.paper;

import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.text.TextStyler;

/** The active configuration together with the text styler built from it. Swapped atomically on reload. */
public record Presentation(ConfigBundle config, TextStyler styler) {

    public static Presentation of(ConfigBundle config) {
        return new Presentation(config, new TextStyler(config.settings().style()));
    }
}
