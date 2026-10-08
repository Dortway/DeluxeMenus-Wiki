package dev.exodaily.paper;

/** Permission nodes. */
public final class Permissions {

    /** Open the rewards menu and claim the standard position. Default: everyone. */
    public static final String USE = "exodaily.use";
    /** Claim the two premium positions. Checked live on every claim. */
    public static final String PREMIUM = "exodaily.premium";
    /** All /exodaily administration commands. Default: op. */
    public static final String ADMIN = "exodaily.admin";

    private Permissions() {
    }
}
