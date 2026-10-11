package net.exoeconomy;

import net.exoeconomy.api.BoosterInfo;
import net.exoeconomy.api.BoosterScope;
import net.exoeconomy.core.Money;
import net.exoeconomy.core.booster.*;
import net.exoeconomy.core.reward.DailyLimits;
import net.exoeconomy.core.reward.RewardAccumulator;
import net.exoeconomy.core.tx.*;
import net.exoeconomy.storage.Database;
import net.exoeconomy.util.Props;
import net.exoeconomy.util.Text;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-independent tests: money arithmetic, booster rules and timing, the transaction
 * coordinator against a real SQLite journal with a scripted economy, crash recovery, caps and
 * batching, the shipped price catalogue, and ExoSkills' real multiplier combination code.
 *
 * <p>Run: java -ea -cp build/classes:build/test-classes:lib/test/*:ExoSkills.jar net.exoeconomy.CoreTests
 */
public final class CoreTests {
    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    private static final Logger LOG = Logger.getLogger("test");

    public static void main(String[] args) throws Exception {
        LOG.setLevel(Level.OFF);
        LOG.setUseParentHandlers(false);
        String prices = args.length > 0 ? args[0] : "src/main/resources/prices.yml";
        String exoSkillsJar = args.length > 1 ? args[1] : null;

        run("money parsing rejects invalid input", CoreTests::moneyParsing);
        run("money rounding never creates Shards", CoreTests::moneyRounding);
        run("multiplier combine modes", CoreTests::combineModes);
        run("booster activates and applies to the right players", CoreTests::boosterActivation);
        run("global booster applies to players joining later", CoreTests::globalLateJoin);
        run("'all' boosters never boost selling", CoreTests::allNotSelling);
        run("same tier extends, capped by max-remaining-duration", CoreTests::boosterExtend);
        run("overlapping QUEUE starts when the active one expires", CoreTests::boosterQueue);
        run("REJECT rules and max live boosters", CoreTests::boosterReject);
        run("combined multiplier is capped", CoreTests::boosterCap);
        run("expiry emits changes once", CoreTests::boosterExpiry);
        run("personal offline PAUSE freezes and resumes", CoreTests::personalPause);
        run("server PAUSE survives clean restart; crash time counts", CoreTests::serverPause);
        run("cancel promotes the queue", CoreTests::cancelPromotes);
        run("apply re-plans after state changed (extend target expired)", CoreTests::replanAfterExpiry);
        run("boosters persist and reload from SQLite", CoreTests::boosterPersistence);
        run("tx: successful charge commits exactly once", CoreTests::txSuccess);
        run("tx: insufficient balance fails cleanly", CoreTests::txInsufficient);
        run("tx: economy unavailable refuses before journaling", CoreTests::txUnavailable);
        run("tx: per-player lock blocks double clicks", CoreTests::txDoubleClick);
        run("tx: validation abort never calls the economy", CoreTests::txValidationAbort);
        run("tx: uncertain outcome is journaled, not retried", CoreTests::txUncertain);
        run("tx: post-payment failure refunds automatically once", CoreTests::txAutoRefund);
        run("tx: refunds are idempotent per parent; failed refund can be retried", CoreTests::txRefundIdempotent);
        run("tx: storage failure refuses without touching the economy", CoreTests::txStorageFailure);
        run("tx: concurrent purchases by many players", CoreTests::txConcurrent);
        run("tx: shutdown reports in-flight transactions", CoreTests::txShutdown);
        run("recovery: PENDING becomes UNCERTAIN, escrow becomes claims", CoreTests::recovery);
        run("recovery: return-to-player mode and crash detection", CoreTests::recoveryReturn);
        run("claims cannot be delivered twice", CoreTests::claimOnce);
        run("schema newer than build is refused", CoreTests::schemaGuard);
        run("daily caps include pending rewards and reset daily", CoreTests::dailyCaps);
        run("reward batching carries fractions and restores on failure", CoreTests::accumulator);
        run("props and durations round-trip", CoreTests::propsAndDurations);
        run("price catalogue covers 1014 block items, all valid", () -> catalogue(prices));
        if (exoSkillsJar != null) run("ExoSkills 1.0.1 combine(skill, 1.0) returns the pure skill bonus", () -> exoSkillsCombine(exoSkillsJar));

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        failures.forEach(f -> System.out.println("  FAILED: " + f));
        if (failed > 0) System.exit(1);
    }

    interface Test { void run() throws Exception; }

    private static void run(String name, Test test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
            System.out.println("FAIL  " + name + ": " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void eq(Object expected, Object actual, String message) {
        if (expected instanceof BigDecimal e && actual instanceof BigDecimal a) {
            if (e.compareTo(a) != 0) throw new AssertionError(message + ": expected " + e + " got " + a);
        } else if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + " got " + actual);
    }

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static void throwsIae(Runnable r, String message) {
        try { r.run(); } catch (IllegalArgumentException e) { return; }
        throw new AssertionError(message);
    }

    // ------------------------------------------------------------------ money

    static void moneyParsing() {
        eq(d("12.5"), Money.parse("12.5"), "plain decimal");
        for (String bad : new String[]{"-1", "1e5", "NaN", "Infinity", "", " ", "1,000", "0x10", "10000000000000", "1.123456789"})
            throwsIae(() -> Money.parse(bad), "should reject " + bad);
        throwsIae(() -> Money.fromConfig(Double.NaN, "x"), "NaN config");
        throwsIae(() -> Money.multiplier(0.5, "m"), "multiplier below 1");
        throwsIae(() -> Money.multiplier(101, "m"), "multiplier above 100");
        check(!Money.isPositiveOperation(BigDecimal.ZERO), "zero is not an operation");
        check(!Money.isPositiveOperation(d("-5")), "negative is not an operation");
        check(!Money.isPositiveOperation(d("1000000000001")), "overflow guard");
        check(Money.isPositiveOperation(d("0.01")), "small positive ok");
    }

    static void moneyRounding() {
        eq(d("1.99"), Money.floor(d("1.999"), 2), "floors");
        eq(d("1"), Money.floor(d("1.999"), 0), "floors to whole");
        eq(d("0"), Money.floor(d("0.999"), 0), "never rounds up");
        check(Money.sameAtScale(d("10.004"), d("10"), 2), "same at scale");
        check(!Money.sameAtScale(d("10.01"), d("10"), 2), "different at scale");
    }

    static void combineModes() {
        List<BigDecimal> ms = List.of(d("1.5"), d("2"));
        eq(d("2.5"), CombineMode.ADDITIVE.combine(ms), "additive");
        eq(d("3"), CombineMode.MULTIPLICATIVE.combine(ms), "multiplicative");
        eq(d("2"), CombineMode.HIGHEST.combine(ms), "highest");
        eq(BigDecimal.ONE, CombineMode.ADDITIVE.combine(List.of()), "empty is 1");
    }

    // ------------------------------------------------------------------ boosters

    static final class Clock { long now = 1_000_000L; long get() { return now; } }

    static BoosterSpec spec(String id, BoosterScope scope, String activity, String mult, long minutes, String price) {
        return new BoosterSpec(id, scope, activity, d(mult), minutes * 60_000L, d(price));
    }

    static BoosterRules rules(BoosterRules.SameTier same, BoosterRules.Overlap overlap, BoosterRules.OfflineTime personal, BoosterRules.OfflineTime server) {
        return new BoosterRules(same, overlap, 3, 2, 6 * 3600_000L, CombineMode.ADDITIVE, d("4"), personal, server);
    }

    static BoosterRules defaults() { return BoosterRules.defaults(); }

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static void boosterActivation() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(defaults(), c::get);
        BoosterSpec s = spec("p150", BoosterScope.PERSONAL, "mining", "1.5", 30, "600");
        eq(Plan.Kind.ACTIVATE, book.plan(s, A).kind(), "plan");
        Booster b = book.apply(s, A, "b1", "tx1", Set.of(A));
        eq(BoosterState.ACTIVE, b.state, "active");
        eq(c.now + 30 * 60_000L, b.expiresAt, "expiry");
        eq(d("1.5"), book.multiplier(A, "mining"), "applies to owner");
        eq(BigDecimal.ONE, book.multiplier(A, "farming"), "not other activity");
        eq(BigDecimal.ONE, book.multiplier(B, "mining"), "not other player");
    }

    static void globalLateJoin() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(defaults(), c::get);
        book.apply(spec("g2", BoosterScope.GLOBAL, "all", "2", 30, "15000"), null, "g", "tx", Set.of(A));
        UUID joinedLater = UUID.randomUUID();
        eq(d("2"), book.multiplier(joinedLater, "slaying"), "late joiner boosted");
        eq(d("2"), book.multiplier(joinedLater, "custom_fishing"), "custom activities covered by 'all'");
    }

    static void allNotSelling() {
        BoosterBook book = new BoosterBook(defaults(), new Clock()::get);
        book.apply(spec("g2", BoosterScope.GLOBAL, "all", "2", 30, "1"), null, "g", null, Set.of());
        eq(BigDecimal.ONE, book.multiplier(A, "selling"), "'all' must not boost selling");
        book.apply(spec("s", BoosterScope.PERSONAL, "selling", "1.25", 30, "1"), A, "s", null, Set.of(A));
        eq(d("1.25"), book.multiplier(A, "selling"), "explicit selling booster applies");
    }

    static void boosterExtend() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(defaults(), c::get);
        BoosterSpec s = spec("p", BoosterScope.PERSONAL, "all", "1.5", 120, "1");
        Booster first = book.apply(s, A, "b1", null, Set.of(A));
        long expiry = first.expiresAt;
        Plan p = book.plan(s, A);
        eq(Plan.Kind.EXTEND, p.kind(), "extend planned");
        Booster ext = book.apply(s, A, "b2", null, Set.of(A));
        eq("b1", ext.id, "same booster extended");
        eq(expiry + 120 * 60_000L, ext.expiresAt, "duration added");
        eq(d("1.5"), book.multiplier(A, "mining"), "extension does not stack the multiplier");
        // 4h remaining + 2h = 6h allowed; another 2h would exceed 6h.
        book.apply(s, A, "b3", null, Set.of(A));
        Plan over = book.plan(s, A);
        eq(Plan.Kind.REJECT, over.kind(), "cap reached");
        eq("booster-max-duration", over.reason(), "reason");
    }

    static void boosterQueue() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(rules(BoosterRules.SameTier.QUEUE, BoosterRules.Overlap.QUEUE, BoosterRules.OfflineTime.CONTINUE, BoosterRules.OfflineTime.CONTINUE), c::get);
        BoosterSpec a = spec("a", BoosterScope.GLOBAL, "mining", "1.5", 10, "1");
        BoosterSpec b = spec("b", BoosterScope.GLOBAL, "all", "2", 5, "1");
        book.apply(a, null, "a1", null, Set.of());
        Booster queued = book.apply(b, null, "b1", null, Set.of());
        eq(BoosterState.QUEUED, queued.state, "queued");
        eq(d("1.5"), book.multiplier(A, "mining"), "queued does not apply yet");
        book.apply(b, null, "b2", null, Set.of());
        eq(Plan.Kind.REJECT, book.plan(b, null).kind(), "max-queued-per-owner=2");
        c.now += 10 * 60_000L;
        List<BoosterBook.Change> changes = book.tick(Set.of());
        eq(2, changes.size(), "one expired, one activated");
        eq(BoosterBook.Change.Type.EXPIRED, changes.get(0).type(), "expired first");
        eq("b1", changes.get(1).booster().id(), "oldest queued starts");
        eq(d("2"), book.multiplier(A, "mining"), "queued now applies");
        c.now += 5 * 60_000L;
        book.tick(Set.of());
        eq(d("2"), book.multiplier(A, "mining"), "second queued started after first queued ended");
    }

    static void boosterReject() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(rules(BoosterRules.SameTier.REJECT, BoosterRules.Overlap.REJECT, BoosterRules.OfflineTime.CONTINUE, BoosterRules.OfflineTime.CONTINUE), c::get);
        BoosterSpec s = spec("m", BoosterScope.PERSONAL, "mining", "1.5", 10, "1");
        book.apply(s, A, "1", null, Set.of(A));
        eq("booster-already-active", book.plan(s, A).reason(), "same tier rejected");
        eq("booster-overlap", book.plan(spec("x", BoosterScope.PERSONAL, "all", "2", 10, "1"), A).reason(), "overlap rejected");
        eq(Plan.Kind.ACTIVATE, book.plan(spec("f", BoosterScope.PERSONAL, "farming", "2", 10, "1"), A).kind(), "non-overlapping allowed");
        BoosterBook stack = new BoosterBook(defaults(), c::get);
        for (String a : List.of("mining", "farming", "slaying")) stack.apply(spec(a, BoosterScope.PERSONAL, a, "1.5", 10, "1"), A, a, null, Set.of(A));
        eq("booster-too-many", stack.plan(spec("x", BoosterScope.PERSONAL, "custom", "1.5", 10, "1"), A).reason(), "max live per owner");
        try {
            stack.apply(spec("x", BoosterScope.PERSONAL, "custom", "1.5", 10, "1"), A, "x", null, Set.of(A));
            throw new AssertionError("apply must refuse");
        } catch (IllegalStateException expected) { }
    }

    static void boosterCap() {
        BoosterBook book = new BoosterBook(defaults(), new Clock()::get);
        book.apply(spec("p3", BoosterScope.PERSONAL, "all", "3", 10, "1"), A, "p", null, Set.of(A));
        book.apply(spec("g3", BoosterScope.GLOBAL, "all", "3", 10, "1"), null, "g", null, Set.of(A));
        eq(d("4"), book.multiplier(A, "mining"), "additive 5x capped at 4x");
        eq(d("3"), book.multiplier(B, "mining"), "global only for others");
    }

    static void boosterExpiry() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(defaults(), c::get);
        book.apply(spec("p", BoosterScope.PERSONAL, "all", "2", 1, "1"), A, "p", null, Set.of(A));
        c.now += 59_999;
        check(book.tick(Set.of(A)).isEmpty(), "not yet");
        eq(d("2"), book.multiplier(A, "mining"), "still active");
        c.now += 1;
        eq(BigDecimal.ONE, book.multiplier(A, "mining"), "multiplier checks expiry precisely, even before tick");
        eq(1, book.tick(Set.of(A)).size(), "expired once");
        check(book.tick(Set.of(A)).isEmpty(), "no duplicate expiry");
        check(book.drainDirty().stream().anyMatch(b -> b.state == BoosterState.EXPIRED), "expired state persisted");
        check(book.snapshot().isEmpty(), "removed from snapshot");
    }

    static void personalPause() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(rules(BoosterRules.SameTier.EXTEND, BoosterRules.Overlap.STACK, BoosterRules.OfflineTime.PAUSE, BoosterRules.OfflineTime.CONTINUE), c::get);
        book.apply(spec("p", BoosterScope.PERSONAL, "all", "2", 10, "1"), A, "p", null, Set.of(A));
        c.now += 4 * 60_000L;
        book.ownerOffline(A);
        c.now += 60 * 60_000L;
        check(book.tick(Set.of()).isEmpty(), "paused booster does not expire");
        eq(BigDecimal.ONE, book.multiplier(A, "mining"), "paused booster does not apply");
        book.ownerOnline(A);
        BoosterInfo info = book.snapshot().get(0);
        eq(6 * 60_000L, info.remaining(c.now), "6 minutes left after resume");
        Booster offlineGrant = book.apply(spec("q", BoosterScope.PERSONAL, "farming", "1.5", 10, "1"), B, "q", null, Set.of(A));
        eq(BoosterState.PAUSED, offlineGrant.state, "booster granted to offline player starts paused");
    }

    static void serverPause() throws Exception {
        Clock c = new Clock();
        BoosterRules pause = rules(BoosterRules.SameTier.EXTEND, BoosterRules.Overlap.STACK, BoosterRules.OfflineTime.CONTINUE, BoosterRules.OfflineTime.PAUSE);
        BoosterBook book = new BoosterBook(pause, c::get);
        book.apply(spec("g", BoosterScope.GLOBAL, "all", "2", 10, "1"), null, "g", null, Set.of());
        c.now += 3 * 60_000L;
        book.serverStopping();
        List<Booster> saved = book.drainDirty();
        c.now += 5 * 3600_000L; // server down 5 hours
        BoosterBook restarted = new BoosterBook(pause, c::get);
        restarted.restore(saved, Set.of());
        eq(7 * 60_000L, restarted.snapshot().get(0).remaining(c.now), "downtime not counted with PAUSE");

        BoosterRules cont = defaults();
        BoosterBook crash = new BoosterBook(cont, c::get);
        crash.apply(spec("g", BoosterScope.GLOBAL, "all", "2", 10, "1"), null, "g", null, Set.of());
        List<Booster> beforeCrash = crash.drainDirty(); // last persisted state; no serverStopping (crash)
        c.now += 4 * 60_000L;
        BoosterBook afterCrash = new BoosterBook(pause, c::get);
        afterCrash.restore(beforeCrash, Set.of());
        eq(6 * 60_000L, afterCrash.snapshot().get(0).remaining(c.now), "crash downtime counts even in PAUSE mode");
    }

    static void cancelPromotes() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(rules(BoosterRules.SameTier.QUEUE, BoosterRules.Overlap.QUEUE, BoosterRules.OfflineTime.CONTINUE, BoosterRules.OfflineTime.CONTINUE), c::get);
        book.apply(spec("a", BoosterScope.PERSONAL, "all", "1.5", 10, "1"), A, "a", null, Set.of(A));
        book.apply(spec("b", BoosterScope.PERSONAL, "all", "2", 10, "1"), A, "b", null, Set.of(A));
        check(book.cancel("a", Set.of(A)).isPresent(), "cancelled");
        eq(d("2"), book.multiplier(A, "mining"), "queued booster promoted after cancel");
        check(book.cancel("a", Set.of(A)).isEmpty(), "cancel is idempotent");
    }

    static void replanAfterExpiry() {
        Clock c = new Clock();
        BoosterBook book = new BoosterBook(defaults(), c::get);
        BoosterSpec s = spec("p", BoosterScope.PERSONAL, "all", "1.5", 10, "1");
        book.apply(s, A, "b1", null, Set.of(A));
        eq(Plan.Kind.EXTEND, book.plan(s, A).kind(), "player saw 'extend'");
        c.now += 10 * 60_000L;
        book.tick(Set.of(A)); // expired while the payment was in flight
        Booster b = book.apply(s, A, "b2", "tx", Set.of(A));
        eq("b2", b.id, "new booster instead of extending an expired one");
        eq(BoosterState.ACTIVE, b.state, "active");
    }

    static void boosterPersistence() throws Exception {
        try (TempDb t = new TempDb()) {
            Clock c = new Clock();
            BoosterBook book = new BoosterBook(defaults(), c::get);
            book.apply(spec("p", BoosterScope.PERSONAL, "mining", "1.5", 10, "1"), A, "p", "tx-p", Set.of(A));
            book.apply(spec("g", BoosterScope.GLOBAL, "all", "2", 20, "1"), null, "g", null, Set.of());
            t.db.write(List.of(Database.saveBoosters(book.drainDirty()))).get(5, TimeUnit.SECONDS);
            List<Booster> loaded = t.db.loadBoosters();
            eq(2, loaded.size(), "both loaded");
            BoosterBook again = new BoosterBook(defaults(), c::get);
            again.restore(loaded, Set.of(A));
            eq(d("2.5"), again.multiplier(A, "mining"), "additive 1 + 0.5 + 1 after reload");
            eq("tx-p", loaded.stream().filter(b -> b.id.equals("p")).findFirst().orElseThrow().sourceTx, "source tx kept");
        }
    }

    // ------------------------------------------------------------------ transactions

    /** Scripted economy. Balances live here only to simulate ExcellentEconomy. */
    static final class FakeLedger implements ShardsLedger {
        final Map<UUID, BigDecimal> balances = new HashMap<>();
        boolean available = true;
        LedgerResult.Outcome forced;
        final AtomicInteger withdrawCalls = new AtomicInteger(), depositCalls = new AtomicInteger();

        public boolean available() { return available; }
        public String unavailableReason() { return available ? null : "down"; }
        public String currencyId() { return "shards"; }
        public int scale() { return 0; }
        public Optional<BigDecimal> maxBalance() { return Optional.empty(); }
        public Optional<BigDecimal> balance(UUID p) { return Optional.of(balances.getOrDefault(p, BigDecimal.ZERO)); }
        public LedgerResult withdraw(UUID p, BigDecimal amount) {
            withdrawCalls.incrementAndGet();
            BigDecimal before = balances.getOrDefault(p, BigDecimal.ZERO);
            if (forced == LedgerResult.Outcome.UNCERTAIN) { balances.put(p, before.subtract(amount)); return new LedgerResult(LedgerResult.Outcome.UNCERTAIN, false, before, null, "timeout"); }
            if (before.compareTo(amount) < 0) return LedgerResult.rejected("insufficient-funds", before);
            balances.put(p, before.subtract(amount));
            return new LedgerResult(LedgerResult.Outcome.SUCCESS, true, before, before.subtract(amount), null);
        }
        public LedgerResult deposit(UUID p, BigDecimal amount) {
            depositCalls.incrementAndGet();
            BigDecimal before = balances.getOrDefault(p, BigDecimal.ZERO);
            if (forced == LedgerResult.Outcome.REJECTED) return LedgerResult.rejected("economy-refused", before);
            balances.put(p, before.add(amount));
            return new LedgerResult(LedgerResult.Outcome.SUCCESS, true, before, before.add(amount), null);
        }
        public CompletableFuture<LedgerResult> depositAnywhere(UUID p, BigDecimal amount) { return CompletableFuture.completedFuture(deposit(p, amount)); }
        public String format(BigDecimal amount) { return amount + " Shards"; }
    }

    /** Executes "server thread" tasks only when pumped, so tests control interleaving. */
    static final class ManualExecutor implements Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();
        public synchronized void execute(Runnable r) { queue.add(r); notifyAll(); }
        /** Runs queued tasks until {@code done} holds (or times out). */
        void pumpUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!done.getAsBoolean()) {
                Runnable r;
                synchronized (this) {
                    while (queue.isEmpty() && !done.getAsBoolean()) {
                        if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
                        wait(50);
                    }
                    r = queue.poll();
                }
                if (r != null) r.run();
            }
        }
        void drain(TempDb t) throws Exception {
            for (int i = 0; i < 20; i++) {
                t.db.write(List.of()).get(5, TimeUnit.SECONDS);
                Runnable r;
                while (true) { synchronized (this) { r = queue.poll(); } if (r == null) break; r.run(); }
            }
        }
    }

    static final class TempDb implements AutoCloseable {
        final Path dir = Files.createTempDirectory("exoeco");
        final Path file = dir.resolve("test.db");
        Database db;
        TempDb() throws Exception { db = new Database(file, 4096, LOG); }
        Database reopen() throws Exception { return new Database(file, 4096, LOG); }
        public void close() throws Exception { try { db.close(); } catch (Exception ignored) { } }
    }

    /** Charge operation that records which callbacks ran. */
    static final class Op implements TransactionCoordinator.Operation {
        final UUID player; final BigDecimal amount; final List<String> events = Collections.synchronizedList(new ArrayList<>());
        String invalid; boolean throwOnCommit; TxKind kind = TxKind.BOOSTER_PURCHASE;
        Op(UUID player, String amount) { this.player = player; this.amount = d(amount); }
        public UUID player() { return player; }
        public TxKind kind() { return kind; }
        public BigDecimal amount() { return amount; }
        public String details() { return Props.of("tier", "test"); }
        public String validate() { return invalid; }
        public List<Journal.Work> committed(String id, LedgerResult r) { events.add("committed"); if (throwOnCommit) throw new IllegalStateException("booster-too-many"); return List.of(); }
        public List<Journal.Work> rejected(String id, String reason) { events.add("rejected:" + reason); return List.of(); }
        public List<Journal.Work> uncertain(String id, LedgerResult r) { events.add("uncertain"); return List.of(); }
        public void aborted(String reason) { events.add("aborted:" + reason); }
        boolean finished() { return !events.isEmpty(); }
    }

    static TransactionCoordinator coordinator(Journal journal, ShardsLedger ledger, Executor main) {
        AtomicInteger n = new AtomicInteger();
        return new TransactionCoordinator(journal, ledger, main, System::currentTimeMillis, () -> "tx-" + n.incrementAndGet(), LOG);
    }

    static TxRecord record(TempDb t, String id) throws Exception {
        return t.db.transaction(id).get(5, TimeUnit.SECONDS).orElseThrow(() -> new AssertionError("no record " + id));
    }

    static void txSuccess() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.balances.put(A, d("1000"));
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            Op op = new Op(A, "600");
            String id = co.submit(op);
            main.pumpUntil(op::finished);
            main.drain(t);
            eq(List.of("committed"), op.events, "callbacks");
            eq(d("400"), ledger.balances.get(A), "charged once");
            eq(1, ledger.withdrawCalls.get(), "one economy call");
            TxRecord r = record(t, id);
            eq(TxState.COMMITTED, r.state(), "journal committed");
            eq(d("1000"), r.balanceBefore(), "before recorded");
            eq(d("400"), r.balanceAfter(), "after recorded");
            check(!co.busy(A), "lock released");
        }
    }

    static void txInsufficient() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.balances.put(A, d("100"));
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            Op op = new Op(A, "600");
            String id = co.submit(op);
            main.pumpUntil(op::finished);
            main.drain(t);
            eq(List.of("rejected:insufficient-funds"), op.events, "rejected");
            eq(d("100"), ledger.balances.get(A), "unchanged");
            eq(TxState.FAILED, record(t, id).state(), "journal failed");
        }
    }

    static void txUnavailable() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.available = false;
            TransactionCoordinator co = coordinator(t.db, ledger, Runnable::run);
            Op op = new Op(A, "10");
            check(co.submit(op) == null, "refused");
            eq(List.of("aborted:economy-unavailable"), op.events, "message key");
            eq(0, t.db.transactions(null, null, 10).get(5, TimeUnit.SECONDS).size(), "nothing journaled");
            Op zero = new Op(A, "0");
            ledger.available = true;
            check(co.submit(zero) == null, "zero refused");
            eq(List.of("aborted:invalid-amount"), zero.events, "invalid amount");
        }
    }

    static void txDoubleClick() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.balances.put(A, d("1000"));
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            Op first = new Op(A, "600"), second = new Op(A, "600"), third = new Op(A, "600");
            co.submit(first);
            co.submit(second); // same tick: double click / macro
            co.submit(third);
            eq(List.of("aborted:transaction-in-progress"), second.events, "second refused");
            eq(List.of("aborted:transaction-in-progress"), third.events, "third refused");
            main.pumpUntil(first::finished);
            main.drain(t);
            eq(d("400"), ledger.balances.get(A), "charged exactly once");
            eq(1, ledger.withdrawCalls.get(), "single economy call");
        }
    }

    static void txValidationAbort() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.balances.put(A, d("1000"));
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            Op op = new Op(A, "100");
            op.invalid = "sell-menu-closed";
            String id = co.submit(op);
            main.pumpUntil(op::finished);
            main.drain(t);
            eq(List.of("aborted:sell-menu-closed"), op.events, "aborted");
            eq(0, ledger.withdrawCalls.get(), "economy never called");
            eq(TxState.ABORTED, record(t, id).state(), "journal aborted");
        }
    }

    static void txUncertain() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.balances.put(A, d("1000"));
            ledger.forced = LedgerResult.Outcome.UNCERTAIN;
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            Op op = new Op(A, "100");
            String id = co.submit(op);
            main.pumpUntil(op::finished);
            main.drain(t);
            eq(List.of("uncertain"), op.events, "uncertain callback");
            eq(TxState.UNCERTAIN, record(t, id).state(), "journal uncertain");
            eq(1, ledger.withdrawCalls.get(), "not retried");
            eq(0, ledger.depositCalls.get(), "not refunded blindly");
        }
    }

    static void txAutoRefund() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.balances.put(A, d("1000"));
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            Op op = new Op(A, "600");
            op.throwOnCommit = true; // e.g. booster refused after payment
            String id = co.submit(op);
            main.pumpUntil(op::finished);
            main.drain(t);
            eq(d("1000"), ledger.balances.get(A), "refunded in full");
            List<TxRecord> children = t.db.children(id).get(5, TimeUnit.SECONDS);
            eq(1, children.size(), "one refund");
            eq(TxKind.REFUND, children.get(0).kind(), "refund kind");
            eq(TxState.COMMITTED, children.get(0).state(), "refund committed");
            eq(TxState.COMMITTED, record(t, id).state(), "purchase record keeps that money moved");
        }
    }

    static void txRefundIdempotent() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            BiConsumer<TxState, String> record = (s, why) -> results.add(s + ":" + why);
            co.credit(TxKind.REFUND, "parent-1", A, d("50"), "", record);
            co.credit(TxKind.REFUND, "parent-1", A, d("50"), "", record); // repeated admin command
            main.pumpUntil(() -> results.size() == 2);
            main.drain(t);
            check(results.contains("COMMITTED:null"), "one refund committed: " + results);
            check(results.contains("null:already-processed"), "second refused: " + results);
            eq(d("50"), ledger.balances.get(A), "paid once");
            // A failed refund frees the slot so an admin can retry.
            ledger.forced = LedgerResult.Outcome.REJECTED;
            results.clear();
            co.credit(TxKind.REFUND, "parent-2", A, d("5"), "", record);
            main.pumpUntil(() -> results.size() == 1);
            main.drain(t);
            eq("FAILED:economy-refused", results.get(0), "refund failed");
            ledger.forced = null;
            results.clear();
            co.credit(TxKind.REFUND, "parent-2", A, d("5"), "", record);
            main.pumpUntil(() -> results.size() == 1);
            main.drain(t);
            eq("COMMITTED:null", results.get(0), "retry allowed after failure");
            eq(d("55"), ledger.balances.get(A), "total paid");
        }
    }

    static void txStorageFailure() throws Exception {
        FakeLedger ledger = new FakeLedger();
        ledger.balances.put(A, d("1000"));
        Journal broken = new Journal() {
            public boolean healthy() { return true; }
            public CompletableFuture<Void> begin(TxRecord r, List<Work> extras) { return CompletableFuture.failedFuture(new SQLException("disk full")); }
            public CompletableFuture<Void> finish(String id, TxState s, BigDecimal b, BigDecimal a, String n, List<Work> e) { return CompletableFuture.completedFuture(null); }
        };
        TransactionCoordinator co = coordinator(broken, ledger, Runnable::run);
        Op op = new Op(A, "100");
        co.submit(op);
        eq(List.of("aborted:storage-unavailable"), op.events, "refused");
        eq(0, ledger.withdrawCalls.get(), "economy untouched");
        check(!co.busy(A), "lock released");
        Journal unhealthy = new Journal() {
            public boolean healthy() { return false; }
            public CompletableFuture<Void> begin(TxRecord r, List<Work> extras) { throw new AssertionError("must not be called"); }
            public CompletableFuture<Void> finish(String id, TxState s, BigDecimal b, BigDecimal a, String n, List<Work> e) { throw new AssertionError(); }
        };
        Op op2 = new Op(A, "100");
        coordinator(unhealthy, ledger, Runnable::run).submit(op2);
        eq(List.of("aborted:storage-unavailable"), op2.events, "unhealthy storage refused up front");
    }

    static void txConcurrent() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            List<Op> ops = new ArrayList<>();
            List<UUID> players = new ArrayList<>();
            for (int i = 0; i < 50; i++) { UUID p = UUID.randomUUID(); players.add(p); ledger.balances.put(p, d("1000")); }
            for (int round = 0; round < 3; round++)
                for (UUID p : players) { Op op = new Op(p, "400"); ops.add(op); co.submit(op); }
            main.pumpUntil(() -> ops.stream().allMatch(Op::finished));
            main.drain(t);
            long committed = ops.stream().filter(o -> o.events.contains("committed")).count();
            long locked = ops.stream().filter(o -> o.events.contains("aborted:transaction-in-progress")).count();
            eq(50L, committed, "one purchase per player while in flight");
            eq(100L, locked, "the rest refused by the lock");
            for (UUID p : players) eq(d("600"), ledger.balances.get(p), "each charged once");
            eq(50, t.db.transactions(null, EnumSet.of(TxState.COMMITTED), 100).get(5, TimeUnit.SECONDS).size(), "journal agrees");
        }
    }

    static void txShutdown() throws Exception {
        try (TempDb t = new TempDb()) {
            FakeLedger ledger = new FakeLedger();
            ledger.balances.put(A, d("1000"));
            ManualExecutor main = new ManualExecutor();
            TransactionCoordinator co = coordinator(t.db, ledger, main);
            String id = co.submit(new Op(A, "100"));
            Map<String, Boolean> open = co.close();
            eq(Boolean.TRUE, open.get(id), "reported as before the economy call");
            t.db.finish(id, TxState.ABORTED, null, null, "shutdown", List.of()).get(5, TimeUnit.SECONDS);
            eq(TxState.ABORTED, record(t, id).state(), "aborted on shutdown");
            Op late = new Op(B, "1");
            co.submit(late);
            eq(List.of("aborted:storage-unavailable"), late.events, "no new work while closing");
            eq(0, ledger.withdrawCalls.get(), "economy untouched");
        }
    }

    // ------------------------------------------------------------------ recovery

    static void recovery() throws Exception {
        try (TempDb t = new TempDb()) {
            t.db.begin(TxRecord.pending("tx-pending", A, TxKind.SALE, d("12"), "shards", null, "items=64x STONE", 1L),
                    List.of(Database.saveEscrow("s-selling", A, "SELLING", "tx-pending", new byte[]{1, 2}, "64x STONE"))).get(5, TimeUnit.SECONDS);
            t.db.write(List.of(Database.saveEscrow("s-open", B, "OPEN", null, new byte[]{3}, "1x DIRT"))).get(5, TimeUnit.SECONDS);
            Database second = t.reopen(); // first instance never shut down cleanly: simulated crash
            Database.RecoveryReport report = second.recover(false);
            eq(1, report.uncertainTransactions(), "pending flagged");
            eq(2, report.recoveredEscrows(), "both escrows recovered");
            eq(TxState.UNCERTAIN, second.transaction("tx-pending").get(5, TimeUnit.SECONDS).orElseThrow().state(), "uncertain");
            List<Database.Claim> review = second.claims(null, Database.CLAIM_REVIEW).get(5, TimeUnit.SECONDS);
            eq(2, review.size(), "held for review by default");
            check(review.stream().anyMatch(c -> "tx-pending".equals(c.tx()) && c.player().equals(A)), "sale claim linked to its tx");
            check(review.stream().anyMatch(c -> c.tx() == null && c.player().equals(B)), "open menu claim");
            eq(0, second.recover(false).recoveredEscrows(), "recovery is idempotent");
            second.close();
        }
    }

    static void recoveryReturn() throws Exception {
        try (TempDb t = new TempDb()) {
            t.db.recover(true);
            t.db.write(List.of(Database.saveEscrow("s-open", B, "OPEN", null, new byte[]{3}, "1x DIRT"))).get(5, TimeUnit.SECONDS);
            Database second = t.reopen();
            Database.RecoveryReport report = second.recover(true);
            check(!report.cleanShutdown(), "crash detected (no clean marker)");
            eq(1, second.claims(B, Database.CLAIM_CLAIMABLE).get(5, TimeUnit.SECONDS).size(), "returned to player");
            second.close();
            t.db.close();
            Database third = t.reopen();
            check(third.recover(true).cleanShutdown(), "clean shutdown detected after close()");
            t.db = third;
        }
    }

    static void claimOnce() throws Exception {
        try (TempDb t = new TempDb()) {
            t.db.write(List.of(Database.insertClaim(new Database.Claim("c1", A, Database.CLAIM_CLAIMABLE, null, "full inventory", new byte[]{1}, "1x STONE", 1L)))).get(5, TimeUnit.SECONDS);
            t.db.write(List.of(Database.deleteClaim("c1", Database.CLAIM_CLAIMABLE))).get(5, TimeUnit.SECONDS);
            try {
                t.db.write(List.of(Database.deleteClaim("c1", Database.CLAIM_CLAIMABLE))).get(5, TimeUnit.SECONDS);
                throw new AssertionError("second delivery must fail");
            } catch (java.util.concurrent.ExecutionException expected) { }
            check(t.db.probe().get(5, TimeUnit.SECONDS), "storage healthy again after the expected failure");
        }
    }

    static void schemaGuard() throws Exception {
        try (TempDb t = new TempDb()) {
            t.db.write(List.of(Database.putMeta("schema_version", "99"))).get(5, TimeUnit.SECONDS);
            try {
                t.reopen();
                throw new AssertionError("newer schema must be refused");
            } catch (SQLException expected) {
                check(expected.getMessage().contains("newer"), "clear message");
            }
            t.db.write(List.of(Database.putMeta("schema_version", "1"))).get(5, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------ rewards

    static void dailyCaps() {
        DailyLimits limits = new DailyLimits(100);
        eq(d("5"), limits.take(A, "mining", d("5"), d("8"), BigDecimal.ZERO), "under cap");
        eq(d("3"), limits.take(A, "mining", d("5"), d("8"), BigDecimal.ZERO), "partially capped");
        eq(BigDecimal.ZERO, limits.take(A, "mining", d("1"), d("8"), BigDecimal.ZERO), "capped");
        eq(d("2"), limits.take(A, "farming", d("2"), BigDecimal.ZERO, d("10")), "total cap 10, 8 used");
        eq(BigDecimal.ZERO, limits.take(A, "slaying", d("1"), BigDecimal.ZERO, d("10")), "total cap reached");
        limits.giveBack(A, "farming", d("2"));
        eq(d("1"), limits.take(A, "slaying", d("1"), BigDecimal.ZERO, d("10")), "given back after failed deposit");
        check(!limits.drainDirty().isEmpty(), "dirty rows for persistence");
        check(limits.roll(101), "new day");
        eq(d("5"), limits.take(A, "mining", d("5"), d("8"), BigDecimal.ZERO), "reset");
        limits.load(new DailyLimits.Row(B, 101, "mining", d("7")));
        eq(d("1"), limits.take(B, "mining", d("5"), d("8"), BigDecimal.ZERO), "loaded counters respected after restart");
    }

    static void accumulator() {
        RewardAccumulator acc = new RewardAccumulator();
        acc.add(A, "mining", d("0.4"));
        acc.add(A, "mining", d("0.4"));
        check(acc.take(A, 0).isEmpty(), "below 1 Shard on whole-number currency");
        acc.add(A, "farming", d("0.5"));
        RewardAccumulator.Payout p = acc.take(A, 0).orElseThrow();
        eq(d("1"), p.amount(), "pays whole part");
        eq(d("0.3"), acc.pending(A), "keeps remainder");
        eq(Set.of("mining", "farming"), p.byActivity().keySet(), "breakdown");
        eq(1, acc.restore(p), "restored after refusal");
        eq(d("1.3"), acc.pending(A), "nothing lost");
        RewardAccumulator.Payout two = acc.take(A, 2).orElseThrow();
        eq(d("1.30"), two.amount(), "2-decimal currency");
        acc.add(A, "mining", d("-5"));
        eq(BigDecimal.ZERO.compareTo(acc.pending(A)), 0, "negative rewards ignored");
    }

    static void propsAndDurations() {
        String text = Props.of("tier", "p=1:x", "items", "64x STONE, 3x DIRT", "empty", null);
        Map<String, String> back = Props.read(text);
        eq("p=1:x", back.get("tier"), "special chars");
        eq("64x STONE, 3x DIRT", back.get("items"), "commas");
        eq("", back.get("empty"), "null as empty");
        eq(5_400_000L, Text.parseDuration("1h30m"), "1h30m");
        eq(172_800_000L, Text.parseDuration("2d"), "2d");
        throwsIae(() -> Text.parseDuration("30"), "unit required");
        throwsIae(() -> Text.parseDuration("-5m"), "negative refused");
        eq("1h 05m", Text.duration(3_900_000L), "format");
    }

    // ------------------------------------------------------------------ catalogue

    static void catalogue(String path) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(path));
        int blocks = 0, priced = 0, disabled = 0;
        boolean inBlocks = false;
        Set<String> keys = new HashSet<>();
        for (String raw : lines) {
            if (raw.startsWith("blocks:")) { inBlocks = true; continue; }
            if (raw.startsWith("items:")) { inBlocks = false; continue; }
            if (!raw.startsWith("  ") || raw.trim().startsWith("#")) continue;
            String line = raw.trim();
            String key = line.substring(0, line.indexOf(':'));
            String value = line.substring(line.indexOf(':') + 1).trim();
            if (value.contains("#")) {
                check(value.startsWith("false"), "only disabled entries carry reasons: " + line);
                check(value.substring(value.indexOf('#') + 1).trim().length() > 5, "disabled entry needs a reason: " + line);
                value = value.substring(0, value.indexOf('#')).trim();
            }
            check(keys.add(key), "duplicate key " + key);
            check(key.matches("[A-Z0-9_]+"), "material key " + key);
            if (inBlocks) blocks++;
            if (value.equals("false")) disabled++;
            else {
                BigDecimal price = Money.parse(value);
                check(price.signum() > 0, "positive price " + key);
                priced++;
            }
        }
        eq(1014, blocks, "every 26.1 block item listed");
        check(keys.contains("BEDROCK") && keys.contains("COBBLESTONE") && keys.contains("GOLDEN_DANDELION"), "sample keys");
        check(priced > 1000 && disabled >= 34, "priced " + priced + ", disabled " + disabled);
    }

    // ------------------------------------------------------------------ ExoSkills

    /** Verifies ExoSkillsBridge's assumption against ExoSkills 1.0.1's shipped Progress.combine. */
    static void exoSkillsCombine(String jar) throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{Path.of(jar).toUri().toURL()}, null)) {
            Class<?> progress = Class.forName("net.exoskills.Progress", true, loader);
            Method combine = progress.getMethod("combine", double.class, double.class, boolean.class);
            for (double skill : new double[]{1.0, 1.2, 1.4, 2.0, 3.0}) {
                eq(skill, (double) combine.invoke(null, skill, 1.0, false), "multiply mode, skill " + skill);
                eq(skill, (double) combine.invoke(null, skill, 1.0, true), "add mode, skill " + skill);
            }
            try {
                combine.invoke(null, 0.5, 1.0, false);
                throw new AssertionError("ExoSkills rejects multipliers below 1");
            } catch (java.lang.reflect.InvocationTargetException expected) {
                check(expected.getCause() instanceof IllegalArgumentException, "IAE");
            }
            Method milestone = progress.getMethod("milestone", int.class, NavigableMap.class);
            NavigableMap<Integer, Double> m = new TreeMap<>(Map.of(20, 1.2, 40, 1.4));
            eq(1.0, (double) milestone.invoke(null, 19, m), "below first milestone");
            eq(1.4, (double) milestone.invoke(null, 55, m), "floor milestone");
        }
    }
}
