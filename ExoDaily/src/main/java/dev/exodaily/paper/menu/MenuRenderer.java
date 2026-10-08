package dev.exodaily.paper.menu;

import dev.exodaily.core.config.ConfigBundle;
import dev.exodaily.core.config.MenuConfig;
import dev.exodaily.core.progression.CycleState;
import dev.exodaily.core.reward.DaySchedule;
import dev.exodaily.core.reward.PoolEntry;
import dev.exodaily.core.reward.RewardCatalog;
import dev.exodaily.core.reward.RewardDefinition;
import dev.exodaily.core.reward.RewardPool;
import dev.exodaily.core.reward.RewardPosition;
import dev.exodaily.core.service.DailyView;
import dev.exodaily.core.service.PositionStatus;
import dev.exodaily.core.service.PreviousDay;
import dev.exodaily.core.storage.AssignmentRecord;
import dev.exodaily.core.text.TextStyler;
import dev.exodaily.core.time.DailyClock;
import dev.exodaily.paper.ItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure presentation: turns a {@link DailyView} plus menu configuration into inventory contents
 * and slot actions. It never selects rewards, touches storage or changes progression.
 */
public final class MenuRenderer {

    /** Rendered contents ready to be placed into an inventory. */
    public record Rendered(Component title, int size, ItemStack[] contents, Map<Integer, MenuAction> actions) {
    }

    private static final int PROGRESS_SEGMENTS = 10;

    private final ItemFactory items;
    private final DailyClock clock;

    public MenuRenderer(ItemFactory items, DailyClock clock) {
        this.items = items;
        this.clock = clock;
    }

    public Rendered render(MenuType type, ConfigBundle config, TextStyler styler, DailyView view, boolean premium) {
        return switch (type) {
            case MAIN -> renderMain(config, styler, view, premium);
            case DETAILS -> renderDetails(config, styler, view, premium);
            case OVERVIEW -> renderOverview(config, styler, view, premium);
        };
    }

    /** The countdown text shown in menus; menus are re-rendered only when it changes. */
    public String countdown(ConfigBundle config) {
        return TextStyler.formatDuration(clock.untilReset(), config.menus().line("under-a-minute"));
    }

    // ================================================================== main

    private Rendered renderMain(ConfigBundle config, TextStyler styler, DailyView view, boolean premium) {
        MenuConfig menus = config.menus();
        MenuConfig.Main main = menus.main();
        Context ctx = context(config, styler, view, premium);
        ItemStack[] contents = new ItemStack[main.frame().size()];
        Map<Integer, MenuAction> actions = new HashMap<>();

        place(contents, main.items().get("progress"), null, ctx, Map.of(), 1);
        actions.put(main.items().get("progress").slot(), new MenuAction.Open(MenuType.OVERVIEW));

        place(contents, main.items().get("standard-info"), premium ? "other" : "current", ctx, Map.of(), 1);
        place(contents, main.items().get("premium-info"), premium ? "current" : "other", ctx, Map.of(), 1);

        String todayState = todayState(view, premium);
        Context todayCtx = ctx.with(Placeholder.component("footer",
                styler.render(menus.line("today-footer-" + todayState), ctx.resolver())));
        place(contents, main.items().get("today"), todayState, todayCtx,
                Map.of("<reward-lines>", rewardLines(menus, styler, view, premium, ctx)), Math.max(1, view.state().day()));
        actions.put(main.items().get("today").slot(), new MenuAction.Open(MenuType.DETAILS));

        String previousState = switch (view.previousDay().kind()) {
            case NONE -> "none";
            case CLAIMED -> "claimed";
            case MISSED -> "missed";
        };
        place(contents, main.items().get("previous-day"), previousState, ctx, Map.of(), 1);
        place(contents, main.items().get("instructions"), null, ctx, Map.of(), 1);
        place(contents, main.items().get("countdown"), null, ctx, Map.of(), 1);

        border(contents, main.frame());
        return new Rendered(styler.render(main.frame().title(), ctx.resolver()), contents.length, contents, actions);
    }

    private static String todayState(DailyView view, boolean premium) {
        boolean review = false;
        boolean processing = false;
        boolean locked = false;
        for (RewardPosition position : RewardPosition.values()) {
            switch (view.status(position, premium)) {
                case AVAILABLE -> {
                    return "available";
                }
                case REVIEW -> review = true;
                case PROCESSING -> processing = true;
                case LOCKED -> locked = true;
                case CLAIMED -> {
                }
            }
        }
        if (processing) {
            return "processing";
        }
        if (review) {
            return "review";
        }
        return locked ? "complete-locked" : "complete";
    }

    private List<Component> rewardLines(MenuConfig menus, TextStyler styler, DailyView view, boolean premium, Context ctx) {
        List<Component> lines = new ArrayList<>();
        for (RewardPosition position : RewardPosition.values()) {
            AssignmentRecord assignment = view.assignments().get(position);
            if (assignment == null) {
                continue;
            }
            PositionStatus status = view.status(position, premium);
            String template = menus.line("reward-line-" + status.name().toLowerCase(Locale.ROOT));
            lines.add(styler.render(template, TagResolver.resolver(ctx.resolver(),
                    positionResolver(menus, styler, position, assignment.reward()))));
        }
        return lines;
    }

    // ================================================================== details

    private Rendered renderDetails(ConfigBundle config, TextStyler styler, DailyView view, boolean premium) {
        MenuConfig menus = config.menus();
        MenuConfig.Details details = menus.details();
        Context ctx = context(config, styler, view, premium);
        ItemStack[] contents = new ItemStack[details.frame().size()];
        Map<Integer, MenuAction> actions = new HashMap<>();

        place(contents, details.items().get("header"), todayState(view, premium), ctx, Map.of(), Math.max(1, view.state().day()));
        place(contents, details.items().get("back"), null, ctx, Map.of(), 1);
        actions.put(details.items().get("back").slot(), new MenuAction.Open(MenuType.MAIN));
        place(contents, details.items().get("countdown"), null, ctx, Map.of(), 1);

        for (RewardPosition position : RewardPosition.values()) {
            MenuConfig.PositionSlots slots = details.positions().get(position);
            AssignmentRecord assignment = view.assignments().get(position);
            if (slots == null || assignment == null) {
                continue;
            }
            PositionStatus status = view.status(position, premium);
            String state = status.name().toLowerCase(Locale.ROOT);
            TagResolver resolver = TagResolver.resolver(ctx.resolver(), positionResolver(menus, styler, position, assignment.reward()),
                    Placeholder.component("status-line", styler.render(menus.line("status-" + state), ctx.resolver())));

            List<Component> before = renderLines(styler, details.rewardLoreBefore(), resolver);
            List<Component> after = renderLines(styler, details.rewardLoreAfter(), resolver);
            Component fallbackName = styler.render(menus.line("reward-name"), resolver);
            Optional<ItemStack> display = items.rewardDisplay(assignment.reward(), styler, fallbackName, before, after,
                    details.showRewardLore());
            contents[slots.rewardSlot()] = display.orElseGet(() ->
                    items.icon(new MenuConfig.Variant("BARRIER", menus.line("reward-unavailable"), List.of(), false),
                            styler, resolver, Map.of(), 1));
            contents[slots.buttonSlot()] = items.icon(details.claimButton().resolve(state), styler, resolver, Map.of(), 1);
            actions.put(slots.rewardSlot(), new MenuAction.Claim(position));
            actions.put(slots.buttonSlot(), new MenuAction.Claim(position));
        }
        border(contents, details.frame());
        return new Rendered(styler.render(details.frame().title(), ctx.resolver()), contents.length, contents, actions);
    }

    // ================================================================== overview

    private Rendered renderOverview(ConfigBundle config, TextStyler styler, DailyView view, boolean premium) {
        MenuConfig menus = config.menus();
        MenuConfig.Overview overview = menus.overview();
        Context ctx = context(config, styler, view, premium);
        ItemStack[] contents = new ItemStack[overview.frame().size()];
        Map<Integer, MenuAction> actions = new HashMap<>();

        place(contents, overview.items().get("header"), null, ctx, Map.of(), 1);
        place(contents, overview.items().get("back"), null, ctx, Map.of(), 1);
        actions.put(overview.items().get("back").slot(), new MenuAction.Open(MenuType.MAIN));

        CycleState state = view.state();
        RewardCatalog catalog = config.rewards();
        for (int day = 1; day <= state.cycleLength() && day <= overview.daySlots().size(); day++) {
            int slot = overview.daySlots().get(day - 1);
            LocalDate date = state.dateOfDay(day);
            int claimed = view.cycleClaimCounts().getOrDefault(day, 0);
            boolean milestone = catalog.milestoneDays().contains(day);
            String dayState;
            Map<String, List<Component>> expansions = Map.of();
            if (day < state.day()) {
                dayState = claimed > 0 ? "claimed" : "missed";
            } else if (day == state.day()) {
                dayState = "today";
                claimed = view.claimedCount(premium);
            } else {
                dayState = milestone ? "milestone" : "future";
                expansions = Map.of("<preview-lines>", previewLines(menus, styler, catalog, day, ctx));
            }
            TagResolver resolver = TagResolver.resolver(ctx.resolver(),
                    Placeholder.unparsed("day-number", Integer.toString(day)),
                    Placeholder.unparsed("day-date", formatDate(menus, date)),
                    Placeholder.unparsed("day-claimed", Integer.toString(claimed)));
            contents[slot] = items.icon(overview.dayItem().resolve(dayState), styler, resolver, expansions, day);
        }
        border(contents, overview.frame());
        return new Rendered(styler.render(overview.frame().title(), ctx.resolver()), contents.length, contents, actions);
    }

    /** Possible rewards for a future day. Describes pools only; nothing is assigned or promised. */
    private List<Component> previewLines(MenuConfig menus, TextStyler styler, RewardCatalog catalog, int day, Context ctx) {
        List<Component> lines = new ArrayList<>();
        DaySchedule schedule = catalog.scheduleFor(day);
        String previousPool = null;
        for (RewardPosition position : RewardPosition.values()) {
            String poolId = schedule.poolFor(position);
            if (poolId == null || poolId.equals(previousPool)) {
                continue;
            }
            previousPool = poolId;
            RewardPool pool = catalog.pools().get(poolId);
            if (pool == null) {
                continue;
            }
            TagResolver poolResolver = TagResolver.resolver(ctx.resolver(),
                    Placeholder.component("tier", styler.render(tierName(menus, position))),
                    Placeholder.component("description", styler.render(pool.description())));
            lines.add(styler.render(menus.line("preview-pool-line"), poolResolver));
            List<PoolEntry> sorted = new ArrayList<>(pool.entries());
            sorted.sort(Comparator.comparingInt(PoolEntry::weight).reversed());
            int shown = 0;
            for (PoolEntry entry : sorted) {
                if (shown >= catalog.previewMaxEntries()) {
                    break;
                }
                RewardDefinition reward = catalog.rewards().get(entry.rewardId());
                if (reward == null) {
                    continue;
                }
                lines.add(styler.render(menus.line("preview-reward-line"), TagResolver.resolver(poolResolver,
                        Placeholder.component("reward", styler.render(reward.summary())))));
                shown++;
            }
            if (sorted.size() > shown && catalog.previewMaxEntries() > 0) {
                lines.add(styler.render(menus.line("preview-more"), TagResolver.resolver(poolResolver,
                        Placeholder.unparsed("count", Integer.toString(sorted.size() - shown)))));
            }
        }
        return lines;
    }

    // ================================================================== shared

    private record Context(TextStyler styler, TagResolver resolver) {

        Context with(TagResolver extra) {
            return new Context(styler, TagResolver.resolver(resolver, extra));
        }
    }

    private Context context(ConfigBundle config, TextStyler styler, DailyView view, boolean premium) {
        MenuConfig menus = config.menus();
        CycleState state = view.state();
        Duration untilReset = clock.untilReset();
        int max = RewardPosition.claimableCount(premium);
        PreviousDay previous = view.previousDay();
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("day", Integer.toString(state.day())),
                Placeholder.unparsed("cycle", Integer.toString(state.cycleNumber())),
                Placeholder.unparsed("cycle-length", Integer.toString(state.cycleLength())),
                Placeholder.unparsed("cycle-start", formatDate(menus, state.cycleStart())),
                Placeholder.unparsed("cycle-end", formatDate(menus, state.cycleEnd())),
                Placeholder.unparsed("days-left", Integer.toString(state.daysRemaining())),
                Placeholder.unparsed("date", formatDate(menus, state.date())),
                Placeholder.unparsed("claimed", Integer.toString(view.claimedCount(premium))),
                Placeholder.unparsed("max", Integer.toString(max)),
                Placeholder.unparsed("available", Integer.toString(view.availableCount(premium))),
                Placeholder.unparsed("reset", TextStyler.formatDuration(untilReset, menus.line("under-a-minute"))),
                Placeholder.unparsed("timezone", clock.zone().getId()),
                Placeholder.unparsed("previous-date", formatDate(menus, previous.date())),
                Placeholder.unparsed("previous-claimed", Integer.toString(previous.claimed())),
                Placeholder.component("tier-name", styler.render(menus.line(premium ? "tier-premium" : "tier-standard"))),
                Placeholder.component("progress-bar", progressBar(menus, styler, state)));
        return new Context(styler, resolver);
    }

    private TagResolver positionResolver(MenuConfig menus, TextStyler styler, RewardPosition position, RewardDefinition reward) {
        return TagResolver.resolver(
                Placeholder.unparsed("position", Integer.toString(position.number())),
                Placeholder.component("tier", styler.render(tierName(menus, position))),
                Placeholder.component("reward", styler.render(reward.summary())));
    }

    private static String tierName(MenuConfig menus, RewardPosition position) {
        return menus.line(position.requiresPremium() ? "tier-premium" : "tier-standard");
    }

    private static Component progressBar(MenuConfig menus, TextStyler styler, CycleState state) {
        int filled = (int) Math.round((double) state.day() / state.cycleLength() * PROGRESS_SEGMENTS);
        filled = Math.max(1, Math.min(PROGRESS_SEGMENTS, filled));
        String template = menus.line("progress-filled").repeat(filled)
                + menus.line("progress-empty").repeat(PROGRESS_SEGMENTS - filled);
        return styler.render(template);
    }

    private static String formatDate(MenuConfig menus, LocalDate date) {
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(menus.line("date-format"), Locale.ENGLISH);
        } catch (IllegalArgumentException e) {
            formatter = DateTimeFormatter.ISO_LOCAL_DATE;
        }
        return date.format(formatter).toLowerCase(Locale.ROOT);
    }

    private static List<Component> renderLines(TextStyler styler, List<String> templates, TagResolver resolver) {
        List<Component> lines = new ArrayList<>(templates.size());
        for (String template : templates) {
            lines.add(styler.render(template, resolver));
        }
        return lines;
    }

    private void place(ItemStack[] contents, MenuConfig.ItemSpec spec, String state, Context ctx,
                       Map<String, List<Component>> expansions, int amount) {
        // Specs are validated on load; this guard keeps rendering safe if a slot is out of range.
        if (spec == null || spec.slot() < 0 || spec.slot() >= contents.length) {
            return;
        }
        contents[spec.slot()] = items.icon(spec.resolve(state), ctx.styler(), ctx.resolver(), expansions, amount);
    }

    private void border(ItemStack[] contents, MenuConfig.Frame frame) {
        MenuConfig.Border border = frame.border();
        if (!border.enabled()) {
            return;
        }
        ItemStack base = items.filler(border.material());
        ItemStack accent = items.filler(border.accentMaterial());
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] == null) {
                contents[slot] = (border.accentSlots().contains(slot) ? accent : base).clone();
            }
        }
    }
}
