package dev.exo.cleanwipe.wipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * What an integration would change for a target. {@code shared} lists other accounts whose data or access would be
 * affected (island members, co-op players). {@code blockers} are reasons the step will refuse to run.
 */
public final class Preview {

    public record Line(String label, String value) {
    }

    public record SharedAccount(UUID uuid, String name, String relation) {
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> blockers = new ArrayList<>();
    private final List<SharedAccount> shared = new ArrayList<>();
    private boolean requiresSharedDecision;

    public static Preview empty() {
        return new Preview();
    }

    public Preview line(String label, Object value) {
        lines.add(new Line(label, String.valueOf(value)));
        return this;
    }

    public Preview warn(String warning) {
        warnings.add(warning);
        return this;
    }

    public Preview block(String reason) {
        blockers.add(reason);
        return this;
    }

    public Preview shared(UUID uuid, String name, String relation) {
        shared.add(new SharedAccount(uuid, name, relation));
        return this;
    }

    public Preview requireSharedDecision() {
        requiresSharedDecision = true;
        return this;
    }

    public List<Line> lines() {
        return Collections.unmodifiableList(lines);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> blockers() {
        return Collections.unmodifiableList(blockers);
    }

    public List<SharedAccount> shared() {
        return Collections.unmodifiableList(shared);
    }

    public boolean requiresSharedDecision() {
        return requiresSharedDecision;
    }

    public boolean isEmpty() {
        return lines.isEmpty() && warnings.isEmpty() && blockers.isEmpty() && shared.isEmpty();
    }
}
