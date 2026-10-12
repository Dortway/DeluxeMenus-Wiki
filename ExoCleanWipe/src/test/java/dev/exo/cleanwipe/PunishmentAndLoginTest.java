package dev.exo.cleanwipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.exo.cleanwipe.config.Settings;
import dev.exo.cleanwipe.identity.LinkReport;
import dev.exo.cleanwipe.identity.VpnService;
import dev.exo.cleanwipe.punish.LoginGate;
import dev.exo.cleanwipe.punish.PunishmentService;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.text.Messages;
import dev.exo.cleanwipe.util.Durations;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ban, expiry, unpunish ownership, IP punishment restrictions, locks and VPN policy at login. */
class PunishmentAndLoginTest {

    static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID ALT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SISTER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    CoreStore store;
    Settings settings;
    Messages messages;
    PunishmentService punishments;
    List<String> alerts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        store = new CoreStore(dir.resolve("core.db"));
        settings = settings("");
        messages = new Messages(settings.messages, settings.colors, settings.symbols);
        punishments = new PunishmentService(store, messages, "ew-", "discord.gg/dogo", true);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    Settings settings(String extraYaml) throws Exception {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        if (!extraYaml.isEmpty()) {
            YamlConfiguration extra = new YamlConfiguration();
            extra.loadFromString(extraYaml);
            for (String k : extra.getKeys(true)) {
                if (!extra.isConfigurationSection(k)) {
                    c.set(k, extra.get(k));
                }
            }
        }
        return new Settings(c, dir);
    }

    LoginGate gate(Settings s, VpnService vpn) {
        return new LoginGate(store, punishments, messages, s, null, vpn, alerts::add, Logger.getLogger("t"));
    }

    static String plain(Component c) {
        return c == null ? null : Messages.plain(c);
    }

    @Test
    void temporaryBanShowsAccurateScreenAndExpires() throws Exception {
        long now = 1_800_000_000_000L;
        long id = store.transaction(c -> punishments.createAccount(c, STEVE, "Steve", "duping <red>x</red>", null, "Admin",
                now, Durations.parse("30days", null), null));
        LoginGate g = gate(settings, null);
        String screen = plain(g.decide(STEVE, "Steve", "203.0.113.9", now + 30 * 60_000));
        assertNotNull(screen);
        assertTrue(screen.contains("reason › duping <red>x</red>"), screen);
        assertTrue(screen.contains("duration › 30 days"), screen);
        assertTrue(screen.contains("remaining › 29 days, 23 hours"), screen);
        assertTrue(screen.contains("reference › ew-" + String.format("%06d", id)), screen);
        assertTrue(screen.contains("appeal › discord.gg/dogo"), screen);
        assertNull(g.decide(STEVE, "Steve", "203.0.113.9", now + 31 * DAY), "expired ban lets the player in");
    }

    @Test
    void unpunishRemovesOnlyAccountPunishmentsNotIpPunishments() throws Exception {
        long now = System.currentTimeMillis();
        store.transaction(c -> punishments.createAccount(c, STEVE, "Steve", "duping", null, "Admin", now, Duration.ofDays(3), null));
        store.recordLogin(STEVE, "Steve", "203.0.113.9", now);
        punishments.createIpGroup(STEVE, "Steve", "evasion", null, "Admin", now, List.of("203.0.113.9"));
        assertEquals(1, punishments.removeAccount(STEVE, "Admin", now));
        // the separate ip punishment still bans the account and the address
        LoginGate g = gate(settings, null);
        assertTrue(plain(g.decide(STEVE, "Steve", "203.0.113.9", now + 1000)).contains("suspended"));
        assertTrue(plain(g.decide(ALT, "Alt", "203.0.113.9", now + 1000)).contains("connections from your network"));
        assertEquals(0, punishments.removeAccount(STEVE, "Admin", now), "nothing left to unpunish");
    }

    @Test
    void ipPunishRestrictsAccountsOnThoseAddressesAndUnipPunishReleasesThem() throws Exception {
        long now = System.currentTimeMillis();
        store.recordLogin(STEVE, "Steve", "203.0.113.9", now);
        store.recordLogin(STEVE, "Steve", "192.168.1.20", now);
        store.recordLogin(SISTER, "Sister", "203.0.113.9", now);
        PunishmentService.IpPlan plan = punishments.planIp(STEVE, null);
        assertEquals(List.of("203.0.113.9"), plan.banned());
        assertEquals(List.of("192.168.1.20"), plan.skipped(), "private addresses are never banned");
        assertEquals(SISTER, plan.otherAccounts().get(0).uuid(), "staff see who else would be refused");
        long id = punishments.createIpGroup(STEVE, "Steve", "ban evasion", null, "Admin", now, plan.banned());
        LoginGate g = gate(settings, null);
        assertTrue(plain(g.decide(STEVE, "Steve", "198.51.100.7", now)).contains("permanent"), "account ban follows the player");
        assertNotNull(g.decide(ALT, "NewAlt", "203.0.113.9", now));
        assertEquals(1, alerts.size());
        assertNull(g.decide(ALT, "NewAlt", "198.51.100.8", now), "the alt from another address is not banned");
        PunishmentService.IpRemoval r = punishments.removeIpGroup(STEVE, "Admin", now);
        assertEquals(1, r.punishments());
        assertEquals(1, r.addresses());
        assertEquals(1, r.released());
        assertNull(g.decide(ALT, "NewAlt", "203.0.113.9", now));
        assertNull(g.decide(STEVE, "Steve", "203.0.113.9", now));
        assertEquals(0, store.punishment(id).map(p -> p.active() ? 1 : 0).orElse(-1));
    }

    @Test
    void lockedAccountsAndSeasonResetCannotJoin() throws Exception {
        long now = System.currentTimeMillis();
        store.lock(STEVE, 7, "wipe job 7", now);
        LoginGate g = gate(settings, null);
        assertTrue(plain(g.decide(STEVE, "Steve", "203.0.113.9", now)).contains("temporarily locked"));
        assertNull(g.decide(ALT, "Alt", "203.0.113.10", now));
        store.lock(null, 8, "season reset", now);
        assertTrue(plain(g.decide(ALT, "Alt", "203.0.113.10", now)).contains("new season"));
        store.unlockJob(8);
        store.unlock(STEVE);
        assertNull(g.decide(STEVE, "Steve", "203.0.113.9", now));
    }

    private static VpnService vpnReturning(CoreStore store, VpnService.Verdict v) {
        return new VpnService(new VpnService.Provider() {
            public String name() {
                return "fake";
            }

            public CompletableFuture<VpnService.Result> lookup(String ip) {
                return CompletableFuture.completedFuture(new VpnService.Result(v, "vpn"));
            }
        }, store, 1, 500, Logger.getLogger("t"));
    }

    @Test
    void vpnPolicies() throws Exception {
        long now = System.currentTimeMillis();
        Settings flag = settings("vpn:\n  enabled: true\n  action: flag\n  check: all\n");
        assertNull(gate(flag, vpnReturning(store, VpnService.Verdict.VPN)).decide(STEVE, "Steve", "198.51.100.1", now));
        assertEquals(1, alerts.size(), "flag mode alerts staff and allows");
        Settings deny = settings("vpn:\n  enabled: true\n  action: deny\n  check: all\n");
        assertTrue(plain(gate(deny, vpnReturning(store, VpnService.Verdict.VPN)).decide(STEVE, "Steve", "198.51.100.2", now)).contains("vpn"));
        Settings outageDenyNew = settings("vpn:\n  enabled: true\n  on-error: deny-new\n  check: all\n");
        LoginGate g = gate(outageDenyNew, vpnReturning(store, VpnService.Verdict.ERROR));
        assertNotNull(g.decide(ALT, "Brand", "198.51.100.3", now), "unknown account refused during outage");
        assertNull(g.decide(STEVE, "Steve", "198.51.100.3", now), "known account allowed during outage");
    }

    @Test
    void linkReportMarksSharedNetworks() throws Exception {
        long now = System.currentTimeMillis();
        store.recordLogin(STEVE, "Steve", "203.0.113.9", now);
        store.recordLogin(ALT, "Alt", "203.0.113.9", now);
        store.recordLogin(STEVE, "Steve", "198.51.100.50", now);
        for (int i = 0; i < 8; i++) {
            store.recordLogin(UUID.randomUUID(), "School" + i, "198.51.100.50", now);
        }
        store.recordLogin(STEVE, "SteveRenamed", "203.0.113.9", now);
        LinkReport.Report r = LinkReport.build(store, STEVE, 5);
        assertEquals(2, r.names().size(), "name history kept");
        assertEquals(ALT, r.linked().get(0).uuid(), "direct link ranks first");
        assertTrue(r.linked().stream().filter(l -> l.name().startsWith("School")).allMatch(LinkReport.Linked::onSharedNetworkOnly));
    }
}
