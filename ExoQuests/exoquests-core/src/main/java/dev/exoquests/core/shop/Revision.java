package dev.exoquests.core.shop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Computes the reward revision hash from the reward contents (price and display are excluded). */
public final class Revision {

    private Revision() {
    }

    public static String of(RewardType type, ItemSpec item, List<String> commands) {
        StringBuilder sb = new StringBuilder(type.name()).append('\n');
        if (item != null) {
            sb.append(item.canonical()).append('\n');
        }
        for (String c : commands) {
            sb.append("cmd=").append(CommandTemplate.normalize(c)).append('\n');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
