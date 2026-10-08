package dev.exodaily.core.reward;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * JSON codec for the reward copy stored with each assignment. The stored snapshot, not the
 * live configuration, is what the player sees and receives.
 */
public final class RewardSnapshot {

    public static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private RewardSnapshot() {
    }

    private record Envelope(int version, RewardDefinition reward) {
    }

    public static String encode(RewardDefinition reward) {
        return GSON.toJson(new Envelope(FORMAT_VERSION, reward));
    }

    public static RewardDefinition decode(String json) {
        try {
            Envelope envelope = GSON.fromJson(json, Envelope.class);
            if (envelope == null || envelope.reward() == null) {
                throw new IllegalArgumentException("empty reward snapshot");
            }
            if (envelope.version() > FORMAT_VERSION) {
                throw new IllegalArgumentException("reward snapshot version " + envelope.version()
                        + " is newer than supported version " + FORMAT_VERSION);
            }
            return envelope.reward();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("malformed reward snapshot: " + e.getMessage(), e);
        }
    }
}
