package com.exoblacksmith.item;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over {@link ItemData#canonical()} with a per-server secret. Items whose data was
 * edited by hand (NBT editors, creative-mode clients) fail verification and are treated as plain items.
 * This cannot stop byte-for-byte copies of a legitimate item (a duplication glitch or a creative
 * pick-block); see the README limitations section.
 */
public final class Signer {
    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public Signer(byte[] secret) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("secret must be at least 16 bytes");
        }
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    public String sign(ItemData data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(data.canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public boolean verify(ItemData data, String signature) {
        if (signature == null) {
            return false;
        }
        byte[] expected = sign(data).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
