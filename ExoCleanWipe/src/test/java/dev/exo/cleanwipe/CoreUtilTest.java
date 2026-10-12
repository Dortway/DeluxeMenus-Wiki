package dev.exo.cleanwipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exo.cleanwipe.identity.ProxyTrust;
import dev.exo.cleanwipe.identity.VpnService;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.text.Messages;
import dev.exo.cleanwipe.util.Durations;
import dev.exo.cleanwipe.util.Inputs;
import dev.exo.cleanwipe.wipe.Confirmations;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreUtilTest {

    @TempDir
    Path dir;

    @Test
    void readableDurations() {
        Duration max = Duration.ofDays(3650);
        assertEquals(Duration.ofDays(30), Durations.parse("30days", max));
        assertEquals(Duration.ofHours(12), Durations.parse("12hours", max));
        assertEquals(Duration.ofMinutes(45), Durations.parse("45minutes", max));
        assertEquals(Duration.ofHours(36), Durations.parse("1d12h", max));
        assertEquals(Duration.ofDays(14), Durations.parse("2w", max));
        assertEquals(Durations.PERMANENT, Durations.parse("permanent", max));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("30", max));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("30parsecs", max));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("-5days", max));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("0days", max));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("99999days", max));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("30days;op", max));
        assertEquals("30 days", Durations.format(Duration.ofDays(30)));
        assertEquals("29 days, 23 hours", Durations.format(Duration.ofDays(30).minusMinutes(30)));
        assertEquals("1 day, 2 hours", Durations.format(Duration.ofHours(26).plusMinutes(5)));
        assertEquals("permanent", Durations.format(Durations.PERMANENT));
    }

    @Test
    void namesAndReasonsAreValidated() {
        assertTrue(Inputs.isValidName("Steve_123"));
        assertFalse(Inputs.isValidName("Steve;op"));
        assertFalse(Inputs.isValidName("<red>x"));
        assertFalse(Inputs.isValidName("a".repeat(17)));
        assertFalse(Inputs.isValidName(""));
        assertEquals("duping items", Inputs.cleanReason("&cduping§l  items\n", 120));
        assertEquals("x", Inputs.cleanReason("&#ff0000x\u0000", 120));
        assertEquals(10, Inputs.cleanReason("a".repeat(50), 10).length());
        assertEquals(12, Inputs.parseReference("ew-000012"));
        assertEquals(-1, Inputs.parseReference("12; drop"));
        assertEquals("203.0.x.x", Inputs.maskIp("203.0.113.9"));
    }

    private Messages messages() {
        return new Messages(Map.of(
                "ban-screen", "<red><sym:stop> exocleanwipe</red>\n<gray>reason <sym:arrow></gray> <white><reason></white>",
                "prefix", "<accent>x</accent> ",
                "t", "<gray>hi <name></gray>"),
                Map.of("red", "#ff5555", "gray", "#aaaaaa", "white", "#ffffff", "accent", "#55cfff"), true);
    }

    @Test
    void userTextCannotInjectFormattingOrClicks() {
        Messages m = messages();
        String evil = "<red>fake</red><click:run_command:'/op me'>click</click><newline>reference › ew-999999";
        Component c = m.screen("ban-screen", Messages.values("reason", evil));
        String plain = Messages.plain(c);
        assertTrue(plain.contains(evil), plain);
        assertTrue(plain.startsWith("⛔ exocleanwipe"));
        assertFalse(hasClick(c), "no click event may come from user text");
    }

    private static boolean hasClick(Component c) {
        if (c.clickEvent() != null) {
            return true;
        }
        for (Component child : c.children()) {
            if (hasClick(child)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void asciiFallbackSymbols() {
        Messages m = new Messages(Map.of("t", "<sym:stop> <sym:arrow>"), Map.of(), false);
        assertEquals("[x] >", Messages.plain(m.screen("t")));
        assertThrows(IllegalArgumentException.class, () -> new Messages(Map.of(), Map.of("red", "nope"), true));
        ClickEvent unused = null;
        assertNull(unused);
    }

    @Test
    void confirmationCodesAreBoundToOwnerAndExpire() {
        Confirmations<String> c = new Confirmations<>();
        UUID staff = UUID.randomUUID();
        var e = c.issue(staff, 60_000, "payload", 1000);
        assertTrue(Inputs.isValidToken(e.token()));
        assertEquals(Confirmations.Check.WRONG_OWNER, c.check(e.token(), UUID.randomUUID(), 2000));
        assertEquals(Confirmations.Check.WRONG_OWNER, c.check(e.token(), null, 2000), "console cannot use a player's code");
        assertEquals(Confirmations.Check.UNKNOWN, c.check(e.token(), staff, 61_001), "expired");
        assertTrue(c.consume(e.token(), staff, 2000).isPresent());
        assertTrue(c.consume(e.token(), staff, 2000).isEmpty(), "single use");
    }

    private static ProxyTrust.Inputs in(boolean online, boolean bungee, boolean velocity, boolean secret) {
        return new ProxyTrust.Inputs(online, bungee, velocity, secret, true, "auto", false);
    }

    @Test
    void proxyForwardingValidation() throws Exception {
        // dogo today: no proxy, online-mode=true
        ProxyTrust direct = ProxyTrust.evaluate(in(true, false, false, false), Set.of());
        assertEquals(ProxyTrust.Mode.DIRECT, direct.mode());
        assertTrue(direct.warnings().isEmpty());
        assertEquals("203.0.113.9", direct.usable(InetAddress.getByName("203.0.113.9")));

        ProxyTrust velocity = ProxyTrust.evaluate(in(false, false, true, true), Set.of("10.0.0.5"));
        assertEquals(ProxyTrust.Mode.VELOCITY, velocity.mode());
        assertNull(velocity.usable(InetAddress.getByName("10.0.0.5")), "the proxy's own address is never a player address");
        assertNull(velocity.usable(InetAddress.getByName("127.0.0.1")), "unforwarded local address behind a proxy");

        assertEquals(ProxyTrust.Mode.UNTRUSTED, ProxyTrust.evaluate(in(false, false, true, false), Set.of()).mode(), "velocity without secret");
        assertEquals(ProxyTrust.Mode.UNTRUSTED, ProxyTrust.evaluate(in(false, true, false, false), Set.of()).mode(), "legacy bungee not confirmed");
        assertEquals(ProxyTrust.Mode.UNTRUSTED, ProxyTrust.evaluate(in(false, true, true, true), Set.of()).mode(), "both enabled");
        ProxyTrust untrusted = ProxyTrust.evaluate(in(false, true, false, false), Set.of());
        assertNull(untrusted.usable(InetAddress.getByName("203.0.113.9")));
        assertFalse(untrusted.ipFeaturesEnabled());
        assertEquals(ProxyTrust.Mode.BUNGEECORD, ProxyTrust.evaluate(
                new ProxyTrust.Inputs(false, true, false, false, true, "auto", true), Set.of()).mode());
        assertEquals(ProxyTrust.Mode.UNTRUSTED, ProxyTrust.evaluate(
                new ProxyTrust.Inputs(true, false, false, false, true, "velocity", false), Set.of()).mode(), "forced mode disagrees");

        assertTrue(ProxyTrust.isPrivate("127.0.0.1"));
        assertTrue(ProxyTrust.isPrivate("192.168.1.10"));
        assertTrue(ProxyTrust.isPrivate("100.64.3.4"));
        assertTrue(ProxyTrust.isPrivate("fd00::1"));
        assertFalse(ProxyTrust.isPrivate("203.0.113.9"));
    }

    @Test
    void vpnLookupsAreCachedTimedOutAndNeverLeakTheKey() throws Exception {
        CoreStore store = new CoreStore(dir.resolve("core.db"));
        AtomicInteger calls = new AtomicInteger();
        VpnService.Provider fast = new VpnService.Provider() {
            public String name() {
                return "fake";
            }

            public CompletableFuture<VpnService.Result> lookup(String ip) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(new VpnService.Result(VpnService.Verdict.VPN, "vpn"));
            }
        };
        VpnService s = new VpnService(fast, store, 3_600_000, 500, Logger.getLogger("t"));
        assertEquals(VpnService.Verdict.VPN, s.check("198.51.100.1").verdict());
        assertEquals(VpnService.Verdict.VPN, s.check("198.51.100.1").verdict());
        assertEquals(1, calls.get(), "second lookup served from cache");
        VpnService fresh = new VpnService(fast, store, 3_600_000, 500, Logger.getLogger("t"));
        fresh.check("198.51.100.1");
        assertEquals(1, calls.get(), "database cache survives a restart");

        VpnService.Provider slow = new VpnService.Provider() {
            public String name() {
                return "slow";
            }

            public CompletableFuture<VpnService.Result> lookup(String ip) {
                return new CompletableFuture<>();
            }
        };
        long t0 = System.nanoTime();
        VpnService.Result r = new VpnService(slow, store, 3_600_000, 200, Logger.getLogger("t")).check("198.51.100.2");
        assertEquals(VpnService.Verdict.ERROR, r.verdict());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "bounded by the timeout");

        // proxycheck response parsing
        assertEquals(VpnService.Verdict.VPN, parse("{\"status\":\"ok\",\"1.2.3.4\":{\"proxy\":\"yes\",\"type\":\"VPN\",\"risk\":66}}").verdict());
        assertEquals(VpnService.Verdict.CLEAN, parse("{\"status\":\"ok\",\"1.2.3.4\":{\"proxy\":\"no\",\"type\":\"Residential\"}}").verdict());
        VpnService.Result denied = parse("{\"status\":\"denied\",\"message\":\"key SECRETKEY123 is invalid\"}");
        assertEquals(VpnService.Verdict.ERROR, denied.verdict());
        assertFalse(denied.detail().contains("SECRETKEY123"));
        store.close();
    }

    private static VpnService.Result parse(String body) throws Exception {
        var m = VpnService.ProxyCheck.class.getDeclaredMethod("parse", String.class, int.class, String.class);
        m.setAccessible(true);
        return (VpnService.Result) m.invoke(null, "1.2.3.4", 200, body);
    }
}
