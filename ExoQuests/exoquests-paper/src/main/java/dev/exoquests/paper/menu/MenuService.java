package dev.exoquests.paper.menu;

import static dev.exoquests.paper.text.TextService.n;
import static dev.exoquests.paper.text.TextService.p;

import dev.exoquests.core.config.MenuLayouts;
import dev.exoquests.core.shop.PurchaseResult;
import dev.exoquests.core.shop.ShopEntry;
import dev.exoquests.paper.ExoQuestsPlugin;
import dev.exoquests.paper.text.TextService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Opens menus and owns per-player menu state (click cooldowns and purchase confirmations).
 * All methods run on the main thread.
 */
public final class MenuService {

    private final ExoQuestsPlugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, ConfirmSession> confirmations = new HashMap<>();
    private int generation;

    public MenuService(ExoQuestsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ opening

    public void openQuests(Player player) {
        plugin.progress().join(player.getUniqueId());
        if (!plugin.tracking().worldAllowed(player.getWorld())) {
            plugin.text().send(player, "error.disabled-world");
        }
        withBalance(player, balance -> {
            QuestsMenu menu = new QuestsMenu(plugin, player, balance);
            player.openInventory(menu.getInventory());
            plugin.text().play(player, "open");
        });
    }

    public void openShop(Player player, int page) {
        if (!player.hasPermission("exoquests.shop")) {
            plugin.text().send(player, "error.no-permission");
            return;
        }
        withBalance(player, balance -> {
            ShopMenu menu = new ShopMenu(plugin, player, page, balance);
            player.openInventory(menu.getInventory());
        });
    }

    /** Fetches the balance off-thread, then continues on the main thread if the player is still online. */
    private void withBalance(Player player, Consumer<Long> then) {
        UUID uuid = player.getUniqueId();
        plugin.points().balance(uuid).whenCompleteAsync((balance, error) -> {
            if (plugin.isShuttingDown()) {
                return;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "could not read balance of " + uuid, error);
                plugin.text().send(online, "error.database");
                return;
            }
            then.accept(balance);
        }, plugin.mainExecutor());
    }

    ItemStack previewFor(Player player, ShopEntry entry, MenuLayouts.ShopMenu layout, long balance) {
        return RewardPreview.build(plugin, entry, layout, player, balance);
    }

    // ------------------------------------------------------------------ purchasing

    void selectReward(Player player, ShopEntry entry, int page, long balance) {
        TextService text = plugin.text();
        if (entry.permission() != null && !player.hasPermission(entry.permission())) {
            text.play(player, "denied");
            text.send(player, "shop.no-permission");
            return;
        }
        if (balance < entry.price()) {
            text.play(player, "denied");
            text.send(player, "shop.insufficient", n("price", entry.price()), n("balance", balance));
            return;
        }
        text.play(player, "click");
        ConfirmSession session = new ConfirmSession(entry.id(), entry.price(), entry.revision(), page,
                System.currentTimeMillis(), generation);
        confirmations.put(player.getUniqueId(), session);
        ConfirmMenu menu = new ConfirmMenu(plugin, player, session, entry, balance);
        player.openInventory(menu.getInventory());
    }

    void confirm(Player player, ConfirmMenu menu) {
        UUID uuid = player.getUniqueId();
        ConfirmSession session = menu.session();
        long timeout = plugin.configs().current().settings().shop().confirmTimeoutSeconds() * 1_000L;
        boolean valid = confirmations.get(uuid) == session && !session.consumed && session.generation == generation
                && System.currentTimeMillis() - session.createdAt <= timeout;
        if (!valid) {
            confirmations.remove(uuid, session);
            plugin.text().play(player, "denied");
            plugin.text().send(player, "shop.expired");
            player.closeInventory();
            return;
        }
        // Single use: mark consumed before anything else so a repeated click packet cannot reuse it.
        session.consumed = true;
        confirmations.remove(uuid);
        player.closeInventory();
        plugin.purchases().purchase(uuid, session.itemId, session.price, session.revision)
                .thenAcceptAsync(result -> {
                    if (!plugin.isShuttingDown()) {
                        report(uuid, result, session);
                    }
                }, plugin.mainExecutor());
    }

    void cancelConfirm(Player player, ConfirmMenu menu) {
        confirmations.remove(player.getUniqueId(), menu.session());
        openShop(player, menu.session().page);
    }

    void onConfirmClosed(UUID uuid, ConfirmMenu menu) {
        confirmations.remove(uuid, menu.session());
    }

    private void report(UUID uuid, PurchaseResult result, ConfirmSession session) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        TextService text = plugin.text();
        String item = plugin.shopEditor().plainName(session.itemId);
        long balance = Math.max(0, result.balance());
        switch (result.status()) {
            case SUCCESS -> {
                text.play(player, "purchase");
                text.send(player, "shop.purchased", p("item", item), n("price", session.price), n("balance", balance));
                plugin.getLogger().info("Purchase " + result.purchaseId() + ": " + player.getName() + " bought "
                        + session.itemId + " for " + session.price + " points.");
                // Reopen the shop only if the player has not opened something else meanwhile.
                if (player.getOpenInventory().getType() == InventoryType.CRAFTING) {
                    openShop(player, session.page);
                }
                return;
            }
            case INSUFFICIENT_FUNDS -> text.send(player, "shop.insufficient", n("price", session.price),
                    n("balance", balance));
            case PRICE_CHANGED -> text.send(player, "shop.price-changed");
            case REWARD_CHANGED -> text.send(player, "shop.reward-changed");
            case UNAVAILABLE -> text.send(player, "shop.unavailable");
            case NO_PERMISSION -> text.send(player, "shop.no-permission");
            case NO_SPACE -> text.send(player, "shop.no-space");
            case INVALID_NAME -> text.send(player, "shop.invalid-name");
            case BUSY -> text.send(player, "shop.busy");
            case REFUNDED -> text.send(player, "shop.refunded", n("balance", balance));
            case QUEUED, OFFLINE -> text.send(player, "shop.queued");
            case NEEDS_REVIEW -> {
                text.send(player, "shop.needs-review", p("id", result.purchaseId()));
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("exoquests.admin.recovery")) {
                        text.send(staff, "admin.recovery-alert", n("count", 1));
                    }
                }
            }
            case ERROR -> text.send(player, "shop.error");
        }
        text.play(player, "denied");
    }

    // ------------------------------------------------------------------ click guard

    boolean allowClick(UUID uuid) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.configs().current().settings().shop().clickCooldownMillis();
        Long last = lastClick.get(uuid);
        if (last != null && now - last < cooldown) {
            return false;
        }
        lastClick.put(uuid, now);
        return true;
    }

    // ------------------------------------------------------------------ refresh

    private static ExoMenu openMenu(Player player) {
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        return holder instanceof ExoMenu menu && menu.viewer().equals(player.getUniqueId()) ? menu : null;
    }

    /** Re-reads the balance and redraws an open quests menu. */
    public void refresh(Player player) {
        if (openMenu(player) instanceof QuestsMenu) {
            withBalance(player, balance -> {
                if (openMenu(player) instanceof QuestsMenu current) {
                    current.setBalance(balance);
                    current.render();
                }
            });
        }
    }

    /** Called every second: keeps progress and the reset countdown current. */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (openMenu(player) instanceof QuestsMenu menu) {
                menu.render();
            }
        }
    }

    /** Configuration changed: invalidate confirmations and redraw or reopen open menus. */
    public void onConfigChanged() {
        generation++;
        confirmations.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ExoMenu menu = openMenu(player);
            if (menu instanceof QuestsMenu quests) {
                quests.render();
            } else if (menu instanceof ShopMenu shop) {
                openShop(player, shop.page());
            } else if (menu instanceof ConfirmMenu) {
                player.closeInventory();
            }
        }
    }

    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (openMenu(player) != null) {
                player.closeInventory();
            }
        }
    }

    public void forget(UUID uuid) {
        lastClick.remove(uuid);
        confirmations.remove(uuid);
    }
}
