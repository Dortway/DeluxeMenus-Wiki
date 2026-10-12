package dev.exo.cleanwipe.integrations.essentials;

import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.User;
import dev.exo.cleanwipe.wipe.Backups;
import dev.exo.cleanwipe.wipe.LiveIntegration;
import dev.exo.cleanwipe.wipe.Preview;
import dev.exo.cleanwipe.wipe.StepContext;
import dev.exo.cleanwipe.wipe.StepResult;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * EssentialsX 2.22.1 through its own user objects (it caches users and writes {@code userdata/<uuid>.yml} itself).
 * Mail is cleared. Essentials money is only reset when Essentials is the Vault economy provider; on dogo
 * ExcellentEconomy provides Vault, so Essentials balances are not used and are left alone.
 */
public final class EssentialsIntegration implements LiveIntegration {

    private final boolean mail;
    private final String moneyMode;

    public EssentialsIntegration(boolean mail, String moneyMode) {
        this.mail = mail;
        this.moneyMode = moneyMode;
    }

    @Override
    public String id() {
        return "Essentials";
    }

    @Override
    public String covers() {
        return (mail ? "mail" : "") + (moneyResets() ? (mail ? ", " : "") + "essentials balance" : "");
    }

    @Override
    public boolean requiresOffline() {
        return false;
    }

    private IEssentials ess() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Essentials");
        return p instanceof IEssentials e && p.isEnabled() ? e : null;
    }

    /** Name of the plugin providing Vault's Economy service, or null when Vault has none. */
    static String vaultProvider() {
        for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
            if (service.getName().equals("net.milkbowl.vault.economy.Economy")) {
                RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(service);
                return reg == null ? null : reg.getPlugin().getName();
            }
        }
        return null;
    }

    private boolean moneyResets() {
        if (moneyMode.equalsIgnoreCase("true") || moneyMode.equalsIgnoreCase("always")) {
            return true;
        }
        if (moneyMode.equalsIgnoreCase("auto")) {
            return "Essentials".equals(vaultProvider());
        }
        return false;
    }

    @Override
    public Availability availability() {
        return ess() == null ? Availability.missing("Essentials is not enabled") : Availability.ok();
    }

    @Override
    public Preview preview(UUID target) throws Exception {
        Preview p = new Preview();
        IEssentials ess = ess();
        if (ess == null) {
            return p;
        }
        CompletableFuture<Preview> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask((Plugin) ess, () -> {
            try {
                User u = ess.getUser(target);
                if (u != null) {
                    if (mail && u.getMailAmount() > 0) {
                        p.line("essentials mail", u.getMailAmount());
                    }
                    if (moneyResets() && u.getMoney().signum() != 0) {
                        p.line("essentials balance", u.getMoney().toPlainString());
                    }
                }
                if (!moneyResets() && !moneyMode.equalsIgnoreCase("false")) {
                    String provider = vaultProvider();
                    p.warn("essentials money is not reset (vault economy is provided by " + (provider == null ? "nobody" : provider) + ")");
                }
                f.complete(p);
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get();
    }

    @Override
    public Preview previewAll() {
        return Preview.empty().line("essentials", "mail" + (moneyResets() ? " and balance" : "") + " of every user");
    }

    @Override
    public CompletableFuture<StepResult> wipe(StepContext ctx, UUID target) {
        IEssentials ess = ess();
        if (ess == null) {
            return CompletableFuture.completedFuture(StepResult.failed("Essentials is not enabled"));
        }
        try {
            User u = ess.getUser(target);
            if (u == null) {
                return CompletableFuture.completedFuture(StepResult.nothing("no essentials user"));
            }
            List<String> done = new ArrayList<>();
            Path dir = ctx.backupDirCreated();
            u.save();
            Path userFile = ((Plugin) ess).getDataFolder().toPath().resolve("userdata").resolve(target + ".yml");
            Backups.copyOnce(userFile, dir.resolve(target + ".yml"));
            if (mail && u.getMailAmount() > 0) {
                int n = u.getMailAmount();
                u.setMailList(new ArrayList<>());
                done.add("mail " + n);
            }
            if (moneyResets()) {
                BigDecimal start = ess.getSettings().getStartingBalance();
                if (u.getMoney().compareTo(start) != 0) {
                    String old = u.getMoney().toPlainString();
                    u.setMoney(start);
                    done.add("balance " + old);
                }
            }
            u.save();
            return CompletableFuture.completedFuture(done.isEmpty() ? StepResult.nothing("nothing stored") : StepResult.done(String.join(", ", done)));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(StepResult.failed(e));
        }
    }

    @Override
    public CompletableFuture<StepResult> wipeAll(StepContext ctx) {
        return CompletableFuture.completedFuture(StepResult.skipped("handled per player"));
    }

    @Override
    public CompletableFuture<StepResult> restore(StepContext ctx, UUID target, Path source) {
        Path f = source.resolve(target + ".yml");
        return CompletableFuture.completedFuture(Files.exists(f)
                ? StepResult.skipped("manual: stop the server and copy " + f + " to plugins/Essentials/userdata/")
                : StepResult.nothing("no essentials backup"));
    }
}
