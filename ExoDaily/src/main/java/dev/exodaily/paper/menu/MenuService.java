package dev.exodaily.paper.menu;

import dev.exodaily.core.claim.ClaimOutcome;
import dev.exodaily.core.claim.ClaimRequest;
import dev.exodaily.core.claim.ClaimResult;
import dev.exodaily.core.claim.ClaimService;
import dev.exodaily.core.progression.CycleState;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.service.DailyService;
import dev.exodaily.core.service.DailyView;
import dev.exodaily.core.service.PositionStatus;
import dev.exodaily.core.time.DailyClock;
import dev.exodaily.paper.ItemFactory;
import dev.exodaily.paper.Messenger;
import dev.exodaily.paper.PaperClaimParticipant;
import dev.exodaily.paper.Permissions;
import dev.exodaily.paper.Presentation;
import dev.exodaily.paper.session.PlayerSession;
import dev.exodaily.paper.session.SessionManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens, refreshes and handles clicks for ExoDaily menus. Server-thread entry points; storage
 * work goes to the storage executor and results come back via the server executor.
 */
public final class MenuService {

    private final Supplier<Presentation> presentation;
    private final SessionManager sessions;
    private final DailyService dailyService;
    private final ClaimService claimService;
    private final Executor storage;
    private final Executor server;
    private final MenuRenderer renderer;
    private final Messenger messenger;
    private final ItemFactory items;
    private final DailyClock clock;
    private final BooleanSupplier ready;
    private final MenuLookup lookup;
    private final Logger logger;

    public MenuService(Supplier<Presentation> presentation, SessionManager sessions, DailyService dailyService,
                       ClaimService claimService, Executor storage, Executor server, MenuRenderer renderer,
                       Messenger messenger, ItemFactory items, DailyClock clock, BooleanSupplier ready,
                       MenuLookup lookup, Logger logger) {
        this.presentation = presentation;
        this.sessions = sessions;
        this.dailyService = dailyService;
        this.claimService = claimService;
        this.storage = storage;
        this.server = server;
        this.renderer = renderer;
        this.messenger = messenger;
        this.items = items;
        this.clock = clock;
        this.ready = ready;
        this.lookup = lookup;
        this.logger = logger;
    }

    // ------------------------------------------------------------------ opening

    /** Entry point for /daily. */
    public void openFromCommand(Player player) {
        if (!checkReady(player)) {
            return;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        long cooldown = TimeUnit.MILLISECONDS.toNanos(presentation.get().config().settings().openCooldownMs());
        if (!session.tryOpen(System.nanoTime(), cooldown)) {
            return;
        }
        load(player, (view, epoch) -> {
            show(player, MenuType.MAIN, view, epoch);
            messenger.sound(player, "open");
        });
    }

    private boolean checkReady(Player player) {
        if (presentation.get() == null) {
            player.sendPlainMessage("ExoDaily is not configured correctly; please tell an administrator.");
            return false;
        }
        if (!ready.getAsBoolean()) {
            messenger.send(player, "storage-unavailable");
            return false;
        }
        return true;
    }

    /**
     * Loads the player's view on the storage thread and hands it, with the session epoch captured
     * before loading, to {@code then} on the server thread.
     */
    private void load(Player player, BiConsumer<DailyView, Long> then) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (!session.beginLoading()) {
            return;
        }
        long epoch = session.epoch();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        CompletableFuture<DailyView> future = new CompletableFuture<>();
        try {
            storage.execute(() -> {
                try {
                    future.complete(dailyService.open(uuid, name));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        future.whenComplete((view, error) -> onServer(() -> {
            session.endLoading();
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                if (error instanceof RejectedExecutionException) {
                    messenger.send(player, "busy");
                } else {
                    logger.log(Level.SEVERE, "Could not load daily rewards for " + name, error);
                    messenger.send(player, "storage-error");
                }
                closeIfOpen(player);
                return;
            }
            session.view(view);
            then.accept(view, epoch);
        }));
    }

    /**
     * Renders {@code view} into the player's menu, updating in place when the same menu is already
     * open. {@code epoch} is the session epoch the view was loaded under; if the session has been
     * invalidated since, the menu stays stale and clicks on it are refused.
     */
    void show(Player player, MenuType type, DailyView view, long epoch) {
        Presentation p = presentation.get();
        if (p == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        PlayerSession session = sessions.get(uuid);
        boolean premium = player.hasPermission(Permissions.PREMIUM);
        MenuRenderer.Rendered rendered = renderer.render(type, p.config(), p.styler(), view, premium);
        Inventory top = player.getOpenInventory().getTopInventory();
        if (lookup.find(top) instanceof ExoMenu open && open.viewer().equals(uuid) && open.type() == type
                && open.title().equals(rendered.title()) && top.getSize() == rendered.size()) {
            open.apply(view, epoch, premium, rendered.actions());
            top.setContents(rendered.contents());
        } else {
            ExoMenu holder = new ExoMenu(type, uuid, rendered.title());
            Inventory inventory = Bukkit.createInventory(holder, rendered.size(), rendered.title());
            holder.attach(inventory);
            holder.apply(view, epoch, premium, rendered.actions());
            inventory.setContents(rendered.contents());
            player.openInventory(inventory);
            sessions.menuOpened(uuid);
        }
        session.view(view);
        session.lastCountdown(renderer.countdown(p.config()));
    }

    // ------------------------------------------------------------------ clicks

    public void handleClick(Player player, ExoMenu menu, int rawSlot) {
        UUID uuid = player.getUniqueId();
        if (!menu.viewer().equals(uuid) || menu.view() == null || presentation.get() == null) {
            return;
        }
        if (!player.hasPermission(Permissions.USE)) {
            onServer(player::closeInventory);
            return;
        }
        MenuAction action = menu.action(rawSlot);
        if (action == null) {
            return;
        }
        PlayerSession session = sessions.get(uuid);
        long cooldown = TimeUnit.MILLISECONDS.toNanos(presentation.get().config().settings().clickCooldownMs());
        if (menu.epoch() != session.epoch() || !clock.today().equals(menu.view().state().date())) {
            // Stale menu: never act on it. Re-render from fresh data instead.
            if (session.guard().tryClick(System.nanoTime(), cooldown)) {
                messenger.send(player, "menu-refreshed");
                refresh(player);
            }
            return;
        }
        switch (action) {
            case MenuAction.Open open -> {
                if (!session.guard().tryClick(System.nanoTime(), cooldown)) {
                    return;
                }
                messenger.sound(player, "click");
                DailyView view = menu.view();
                long epoch = menu.epoch();
                // Opening inventories inside a click event is unsafe; do it on the next tick.
                onServer(() -> show(player, open.type(), view, epoch));
            }
            case MenuAction.Claim claim -> claim(player, menu, claim.position(), session, cooldown);
        }
    }

    private void claim(Player player, ExoMenu menu, RewardPosition position, PlayerSession session, long cooldown) {
        // Fast feedback from the rendered view; the claim pipeline revalidates everything anyway.
        PositionStatus status = menu.view().status(position, player.hasPermission(Permissions.PREMIUM));
        switch (status) {
            case LOCKED -> {
                if (session.guard().tryClick(System.nanoTime(), cooldown)) {
                    messenger.send(player, "claim-locked");
                    messenger.sound(player, "locked");
                }
                return;
            }
            case CLAIMED -> {
                if (session.guard().tryClick(System.nanoTime(), cooldown)) {
                    messenger.send(player, "claim-already");
                    messenger.sound(player, "error");
                }
                return;
            }
            case REVIEW -> {
                if (session.guard().tryClick(System.nanoTime(), cooldown)) {
                    messenger.send(player, "claim-pending-review");
                }
                return;
            }
            case PROCESSING -> {
                return;
            }
            case AVAILABLE -> {
            }
        }
        if (!session.guard().tryBegin(System.nanoTime(), cooldown)) {
            return;
        }
        CycleState state = menu.view().state();
        ClaimRequest request = new ClaimRequest(player.getUniqueId(), state.cycleNumber(), state.day(), state.date(), position);
        PaperClaimParticipant participant = new PaperClaimParticipant(player, items,
                () -> presentation.get().styler(), Permissions.PREMIUM, logger);
        claimService.claim(request, participant).whenComplete((result, error) -> onServer(() -> {
            session.guard().end();
            ClaimResult outcome = result;
            if (error != null) {
                logger.log(Level.SEVERE, "Unexpected claim failure for " + request.key(), error);
                outcome = ClaimResult.of(ClaimOutcome.STORAGE_ERROR);
            }
            if (!player.isOnline()) {
                return;
            }
            feedback(player, outcome);
            refresh(player);
        }));
    }

    private void feedback(Player player, ClaimResult result) {
        Presentation p = presentation.get();
        var reward = result.reward() == null || p == null
                ? Placeholder.unparsed("reward", "")
                : Placeholder.component("reward", p.styler().render(result.reward().summary()));
        switch (result.outcome()) {
            case SUCCESS -> {
                messenger.send(player, "claim-success", reward);
                messenger.sound(player, "claim");
                messenger.claimEffect(player);
            }
            case DELIVERED_UNCONFIRMED -> {
                messenger.send(player, "claim-success-unconfirmed", reward);
                messenger.sound(player, "claim");
            }
            case ALREADY_CLAIMED -> error(player, "claim-already");
            case LOCKED -> {
                messenger.send(player, "claim-locked");
                messenger.sound(player, "locked");
            }
            case STALE, NOT_ASSIGNED -> error(player, "claim-stale");
            case INVENTORY_FULL -> error(player, "claim-inventory-full", reward);
            case DELIVERY_FAILED -> error(player, "claim-delivery-failed");
            case PENDING_REVIEW -> error(player, "claim-pending-review");
            case IN_PROGRESS -> error(player, "claim-in-progress");
            case STORAGE_ERROR -> error(player, "storage-error");
            case BUSY -> error(player, "busy");
            case OFFLINE -> {
            }
        }
    }

    private void error(Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        messenger.send(player, key, resolvers);
        messenger.sound(player, "error");
    }

    // ------------------------------------------------------------------ refresh & invalidation

    /** Reloads the view and re-renders whichever ExoDaily menu the player currently has open. */
    public void refresh(Player player) {
        if (!ready.getAsBoolean() || presentation.get() == null) {
            closeIfOpen(player);
            return;
        }
        load(player, (view, epoch) -> {
            ExoMenu open = openMenu(player);
            if (open != null) {
                show(player, open.type(), view, epoch);
            }
        });
    }

    /** Marks a player's rendered menus stale (admin change) and refreshes an open menu. */
    public void invalidate(UUID uuid, String messageKey) {
        PlayerSession session = sessions.peek(uuid);
        if (session != null) {
            session.invalidate();
            session.view(null);
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && openMenu(player) != null) {
            if (messageKey != null) {
                messenger.send(player, messageKey);
            }
            refresh(player);
        }
    }

    public void invalidateAll(String messageKey) {
        for (PlayerSession session : sessions.all()) {
            invalidate(session.uuid(), messageKey);
        }
    }

    /** Periodic update for open menus only: day rollover, countdown changes and premium changes. */
    public void tick() {
        Presentation p = presentation.get();
        if (p == null) {
            return;
        }
        String countdown = renderer.countdown(p.config());
        for (UUID uuid : sessions.openMenus()) {
            Player player = Bukkit.getPlayer(uuid);
            ExoMenu menu = player == null ? null : openMenu(player);
            if (menu == null) {
                sessions.menuClosed(uuid);
                continue;
            }
            PlayerSession session = sessions.get(uuid);
            if (menu.view() == null) {
                continue;
            }
            if (!clock.today().equals(menu.view().state().date())) {
                // Only once per stale menu: after invalidation the epochs differ until the refresh lands.
                if (menu.epoch() == session.epoch() && !session.guard().busy()) {
                    messenger.send(player, "day-changed");
                    messenger.sound(player, "refresh");
                    session.invalidate();
                    refresh(player);
                }
                continue;
            }
            if (menu.epoch() != session.epoch()) {
                continue; // stale; a refresh with fresh data is on its way
            }
            boolean premium = player.hasPermission(Permissions.PREMIUM);
            if (premium != menu.premium() || !countdown.equals(session.lastCountdown())) {
                show(player, menu.type(), menu.view(), menu.epoch());
            }
        }
    }

    public void closeAll() {
        for (UUID uuid : sessions.openMenus()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                closeIfOpen(player);
            }
        }
    }

    private ExoMenu openMenu(Player player) {
        ExoMenu menu = lookup.find(player.getOpenInventory().getTopInventory());
        return menu != null && menu.viewer().equals(player.getUniqueId()) ? menu : null;
    }

    private void closeIfOpen(Player player) {
        if (openMenu(player) != null) {
            player.closeInventory();
        }
    }

    private void onServer(Runnable task) {
        try {
            server.execute(task);
        } catch (RejectedExecutionException e) {
            logger.fine("Server task rejected during shutdown");
        }
    }
}
