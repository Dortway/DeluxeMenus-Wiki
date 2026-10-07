package dev.exo.dailyspinner.spin;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OperationGuardTest {

    @Test
    void onlyOneOperationPerPlayer() {
        OperationGuard guard = new OperationGuard();
        UUID player = UUID.randomUUID();
        Optional<OperationGuard.Token> first = guard.tryAcquire(player, OperationGuard.Kind.SPIN);
        assertTrue(first.isPresent());
        assertTrue(guard.tryAcquire(player, OperationGuard.Kind.CLAIM).isEmpty());
        assertTrue(guard.tryAcquire(UUID.randomUUID(), OperationGuard.Kind.SPIN).isPresent());
        assertTrue(guard.release(first.get()));
        assertTrue(guard.tryAcquire(player, OperationGuard.Kind.CLAIM).isPresent());
    }

    @Test
    void staleTokenCannotReleaseNewOperation() {
        OperationGuard guard = new OperationGuard();
        UUID player = UUID.randomUUID();
        OperationGuard.Token old = guard.tryAcquire(player, OperationGuard.Kind.SPIN).orElseThrow();
        assertTrue(guard.release(old));
        OperationGuard.Token current = guard.tryAcquire(player, OperationGuard.Kind.SPIN).orElseThrow();
        assertFalse(guard.release(old), "replayed release must not free the new operation");
        assertTrue(guard.isHeld(current));
        assertFalse(guard.release(old));
        assertTrue(guard.release(current));
        assertFalse(guard.release(current), "double release is a no-op");
    }

    @Test
    void concurrentAcquireAllowsExactlyOne() throws Exception {
        OperationGuard guard = new OperationGuard();
        UUID player = UUID.randomUUID();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                if (guard.tryAcquire(player, OperationGuard.Kind.SPIN).isPresent()) {
                    wins.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(1, wins.get());
    }

    @Test
    void throttleBlocksSpam() {
        AtomicLong now = new AtomicLong(1000);
        Throttle throttle = new Throttle(250, now::get);
        UUID id = UUID.randomUUID();
        assertTrue(throttle.tryPass(id));
        assertFalse(throttle.tryPass(id));
        now.addAndGet(249);
        assertFalse(throttle.tryPass(id));
        now.addAndGet(1);
        assertTrue(throttle.tryPass(id));
    }
}
