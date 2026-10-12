package dev.exo.cleanwipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.exo.cleanwipe.config.Settings;
import dev.exo.cleanwipe.command.WipeCommand;
import dev.exo.cleanwipe.integrations.economy.BalanceGuard;
import dev.exo.cleanwipe.integrations.economy.ExcellentEconomyIntegration;
import dev.exo.cleanwipe.punish.PunishmentService;
import dev.exo.cleanwipe.storage.AuditLog;
import dev.exo.cleanwipe.storage.CoreStore;
import dev.exo.cleanwipe.text.Messages;
import dev.exo.cleanwipe.wipe.ActionService;
import dev.exo.cleanwipe.wipe.IntegrationRegistry;
import dev.exo.cleanwipe.wipe.JobEngine;
import dev.exo.cleanwipe.wipe.WipeOptions;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;
import su.nightexpress.excellenteconomy.api.event.ChangeBalanceEvent;
import su.nightexpress.excellenteconomy.user.CoinsUser;

/** Permission checks, argument validation, injection attempts, tab completion and the balance guard. */
class CommandPermissionTest {

    static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path dir;

    CoreStore store;
    ActionService actions;
    WipeCommand cmd;
    Command wipe;
    MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() throws Exception {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        store = new CoreStore(dir.resolve("core.db"));
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        Settings settings = new Settings(c, dir);
        Messages messages = new Messages(settings.messages, settings.colors, true);
        actions = mock(ActionService.class);
        when(actions.resolve("Steve")).thenReturn(Optional.of(new ActionService.Resolved(STEVE, "Steve")));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("t"));
        PunishmentService punishments = new PunishmentService(store, messages, "ew-", "x", true);
        IntegrationRegistry reg = new IntegrationRegistry();
        cmd = new WipeCommand(plugin, settings, messages, store, actions, punishments,
                new JobEngine(store, reg, dir, Logger.getLogger("t")), reg, new AuditLog(dir.resolve("audit.log"), store, Logger.getLogger("t")));
        wipe = mock(Command.class);
        when(wipe.getName()).thenReturn("wipe");
    }

    @AfterEach
    void tearDown() throws Exception {
        bukkit.close();
        store.close();
    }

    private static CommandSender sender(String... perms) {
        CommandSender s = mock(CommandSender.class);
        Set<String> set = Set.of(perms);
        when(s.hasPermission(anyString())).thenAnswer(i -> set.contains(i.<String>getArgument(0)));
        when(s.getName()).thenReturn("Mod");
        return s;
    }

    private static List<String> said(CommandSender s) {
        ArgumentCaptor<Component> c = ArgumentCaptor.forClass(Component.class);
        verify(s, org.mockito.Mockito.atLeast(0)).sendMessage(c.capture());
        List<String> out = new ArrayList<>();
        c.getAllValues().forEach(x -> out.add(Messages.plain(x)));
        return out;
    }

    @Test
    void ordinaryPlayersHaveNoAccess() {
        CommandSender player = sender();
        cmd.onCommand(player, wipe, "wipe", new String[]{"Steve", "all"});
        cmd.onCommand(player, wipe, "wipe", new String[]{"punish", "Steve", "30days", "duping"});
        assertTrue(said(player).stream().allMatch(m -> m.contains("do not have permission")));
        verify(actions, never()).previewWipe(any(), any(), any(), any(), any(), any(), any());
        assertTrue(cmd.onTabComplete(player, wipe, "wipe", new String[]{""}).isEmpty());
        Command wipeall = mock(Command.class);
        when(wipeall.getName()).thenReturn("wipeall");
        cmd.onCommand(player, wipeall, "wipeall", new String[0]);
        verify(actions, never()).previewWipeAll(any());
    }

    @Test
    void eachActionNeedsItsOwnNode() {
        CommandSender mod = sender("exocleanwipe.use", "exocleanwipe.wipe");
        cmd.onCommand(mod, wipe, "wipe", new String[]{"punish", "Steve", "30days", "duping"});
        verify(actions, never()).previewWipe(any(), eq(ActionService.Kind.PUNISH), any(), any(), any(), any(), any());
        cmd.onCommand(mod, wipe, "wipe", new String[]{"Steve", "all"});
        verify(actions).previewWipe(eq(mod), eq(ActionService.Kind.WIPE), eq(new ActionService.Resolved(STEVE, "Steve")),
                eq(List.of()), eq(new WipeOptions(false, false, 0)), any(), eq(""));
        cmd.onCommand(mod, wipe, "wipe", new String[]{"Steve", "all", "+alt:Steve"});
        assertTrue(said(mod).stream().anyMatch(m -> m.contains("do not have permission")), "+alt needs exocleanwipe.wipe.linked");
        List<String> tabs = cmd.onTabComplete(mod, wipe, "wipe", new String[]{""});
        assertFalse(tabs.contains("punish"));
        assertFalse(tabs.contains("ippunish"));
    }

    @Test
    void punishValidatesDurationAndCleansReason() {
        CommandSender mod = sender("exocleanwipe.use", "exocleanwipe.punish");
        cmd.onCommand(mod, wipe, "wipe", new String[]{"punish", "Steve", "30parsecs", "duping"});
        assertTrue(said(mod).stream().anyMatch(m -> m.contains("unknown time unit")));
        cmd.onCommand(mod, wipe, "wipe", new String[]{"punish", "Steve", "30days"});
        assertTrue(said(mod).stream().anyMatch(m -> m.contains("usage")));
        cmd.onCommand(mod, wipe, "wipe", new String[]{"punish", "Steve", "30days", "&cduping", "<click:run_command:'/op", "me'>x"});
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(actions).previewWipe(eq(mod), eq(ActionService.Kind.PUNISH), any(), any(), any(), eq(Duration.ofDays(30)), reason.capture());
        assertEquals("duping <click:run_command:'/op me'>x", reason.getValue(), "colour codes stripped; tags kept as plain text");
    }

    @Test
    void invalidNamesAreRejectedBeforeAnyLookup() {
        CommandSender mod = sender("exocleanwipe.use", "exocleanwipe.wipe", "exocleanwipe.unpunish");
        cmd.onCommand(mod, wipe, "wipe", new String[]{"Ste;ve", "all"});
        cmd.onCommand(mod, wipe, "wipe", new String[]{"unpunish", "../../x"});
        verify(actions, never()).resolve(anyString());
        assertTrue(said(mod).stream().allMatch(m -> m.contains("not a valid player name")));
    }

    @Test
    void tabCompletionFollowsPermissions() {
        CommandSender viewer = sender("exocleanwipe.use", "exocleanwipe.status");
        List<String> tabs = cmd.onTabComplete(viewer, wipe, "wipe", new String[]{""});
        assertTrue(tabs.contains("status"));
        assertTrue(tabs.contains("confirm"));
        assertFalse(tabs.contains("punish"));
        assertFalse(tabs.contains("unlock"));
        assertTrue(cmd.onTabComplete(viewer, wipe, "wipe", new String[]{"punish", ""}).isEmpty());
        CommandSender punisher = sender("exocleanwipe.use", "exocleanwipe.punish");
        assertTrue(cmd.onTabComplete(punisher, wipe, "wipe", new String[]{"punish", "Steve", "3"}).contains("30days"));
    }

    @Test
    void lockedAccountsCannotReceiveOrSendCurrency() throws Exception {
        BalanceGuard guard = new BalanceGuard(store, Logger.getLogger("t"));
        ChangeBalanceEvent event = mock(ChangeBalanceEvent.class);
        CoinsUser user = mock(CoinsUser.class);
        when(user.getId()).thenReturn(STEVE);
        when(event.getUser()).thenReturn(user);
        when(event.getCurrency()).thenReturn(mock(ExcellentCurrency.class));
        guard.onChange(event);
        verify(event, never()).setCancelled(true);
        store.lock(STEVE, 5, "wipe job 5", 1);
        guard.onChange(event);
        verify(event).setCancelled(true);
        ExcellentEconomyIntegration.OWN_WRITES.add(STEVE);
        ChangeBalanceEvent own = mock(ChangeBalanceEvent.class);
        when(own.getUser()).thenReturn(user);
        guard.onChange(own);
        verify(own, never()).setCancelled(true);
        ExcellentEconomyIntegration.OWN_WRITES.remove(STEVE);
        store.unlock(STEVE);
        store.lock(STEVE, ActionService.FREEZE_JOB, "preview", 1);
        ChangeBalanceEvent frozen = mock(ChangeBalanceEvent.class);
        when(frozen.getUser()).thenReturn(user);
        guard.onChange(frozen);
        verify(frozen, never()).setCancelled(true);
    }
}
