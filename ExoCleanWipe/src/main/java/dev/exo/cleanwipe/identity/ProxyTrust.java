package dev.exo.cleanwipe.identity;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the addresses the server sees are the players' real addresses.
 *
 * <ul>
 *   <li>DIRECT (no proxy forwarding): the socket address is the player's address.</li>
 *   <li>VELOCITY modern forwarding with a secret: Paper verifies the forwarded data with that secret, so it is
 *       trusted.</li>
 *   <li>Legacy BungeeCord forwarding cannot be verified by the backend; it is only trusted when staff confirm the
 *       backend port is firewalled ({@code allow-legacy-bungeecord-forwarding}).</li>
 *   <li>Anything inconsistent (both enabled, Velocity without a secret, offline mode without a proxy) disables IP
 *       features: nothing is recorded, linked or banned by address.</li>
 * </ul>
 * Configured proxy addresses are never treated as player addresses, whatever the mode.
 */
public final class ProxyTrust {

    public enum Mode { DIRECT, VELOCITY, BUNGEECORD, UNTRUSTED }

    public record Inputs(boolean onlineMode, boolean bungeecord, boolean velocityEnabled, boolean velocitySecretSet,
                         boolean velocityOnlineMode, String forcedMode, boolean allowLegacyBungee) {
    }

    private final Mode mode;
    private final List<String> warnings;
    private final Set<String> proxyAddresses;

    private ProxyTrust(Mode mode, List<String> warnings, Set<String> proxyAddresses) {
        this.mode = mode;
        this.warnings = warnings;
        this.proxyAddresses = proxyAddresses;
    }

    public static ProxyTrust evaluate(Inputs in, Set<String> proxyAddresses) {
        List<String> w = new ArrayList<>();
        Mode detected;
        if (in.bungeecord() && in.velocityEnabled()) {
            w.add("spigot.yml bungeecord and paper velocity forwarding are both enabled; ip features are disabled until only one is on");
            detected = Mode.UNTRUSTED;
        } else if (in.velocityEnabled()) {
            if (!in.velocitySecretSet()) {
                w.add("velocity forwarding is enabled without a secret; forwarded addresses cannot be verified, ip features disabled");
                detected = Mode.UNTRUSTED;
            } else {
                detected = Mode.VELOCITY;
                if (!in.velocityOnlineMode()) {
                    w.add("velocity online-mode is false: player uuids are not authenticated by mojang");
                }
            }
        } else if (in.bungeecord()) {
            if (in.allowLegacyBungee()) {
                detected = Mode.BUNGEECORD;
                w.add("trusting legacy bungeecord forwarding because allow-legacy-bungeecord-forwarding is true; make sure the backend port is firewalled");
            } else {
                w.add("legacy bungeecord forwarding cannot be verified; ip features disabled (set proxy.allow-legacy-bungeecord-forwarding after firewalling)");
                detected = Mode.UNTRUSTED;
            }
        } else {
            detected = Mode.DIRECT;
            if (!in.onlineMode()) {
                w.add("online-mode is false and no proxy forwarding is configured: names and uuids are not authenticated");
            }
        }
        String forced = in.forcedMode() == null ? "auto" : in.forcedMode().toLowerCase(Locale.ROOT);
        if (!forced.equals("auto")) {
            Mode want = switch (forced) {
                case "direct" -> Mode.DIRECT;
                case "velocity" -> Mode.VELOCITY;
                case "bungeecord" -> Mode.BUNGEECORD;
                default -> null;
            };
            if (want == null) {
                w.add("proxy.mode '" + forced + "' is unknown; using the detected mode");
            } else if (want != detected) {
                w.add("proxy.mode is " + forced + " but the server configuration says " + detected.name().toLowerCase(Locale.ROOT)
                        + "; ip features disabled until they agree");
                detected = Mode.UNTRUSTED;
            }
        }
        return new ProxyTrust(detected, w, Set.copyOf(proxyAddresses));
    }

    public Mode mode() {
        return mode;
    }

    public List<String> warnings() {
        return warnings;
    }

    /** True when IP addresses may be stored, linked and banned. */
    public boolean ipFeaturesEnabled() {
        return mode != Mode.UNTRUSTED;
    }

    /**
     * The address to record for a login, or null when it must not be used (untrusted mode, or the address of one of
     * our own proxies, which would otherwise make every player look linked).
     */
    public String usable(InetAddress address) {
        if (address == null || !ipFeaturesEnabled()) {
            return null;
        }
        String ip = address.getHostAddress();
        int zone = ip.indexOf('%');
        if (zone > 0) {
            ip = ip.substring(0, zone);
        }
        if (proxyAddresses.contains(ip)) {
            return null;
        }
        if (mode != Mode.DIRECT && (address.isLoopbackAddress() || address.isSiteLocalAddress())) {
            // behind a proxy a local address means forwarding did not happen for this connection
            return null;
        }
        return ip;
    }

    /** Addresses that must never be IP-banned (loopback, private ranges, link-local, CGNAT, unspecified). */
    public static boolean isPrivate(String ip) {
        try {
            InetAddress a = InetAddress.ofLiteral(ip);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
                return true;
            }
            byte[] b = a.getAddress();
            if (b.length == 4) {
                int first = b[0] & 0xFF;
                int second = b[1] & 0xFF;
                return first == 100 && second >= 64 && second <= 127; // 100.64.0.0/10 carrier-grade NAT
            }
            return (b[0] & 0xFE) == 0xFC; // fc00::/7 unique local
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
