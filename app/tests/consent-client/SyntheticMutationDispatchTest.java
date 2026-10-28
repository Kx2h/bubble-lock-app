package org.privacybubble.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SyntheticMutationDispatchTest {
    public static void main(String[] args) throws Exception {
        AtomicInteger sent = new AtomicInteger();
        String response = SyntheticMutationDispatch.run(() -> {}, () -> { sent.incrementAndGet(); return "ok"; }, () -> false);
        if (!"ok".equals(response) || sent.get() != 1) throw new AssertionError("normal dispatch failed");

        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch preflightStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                SyntheticMutationDispatch.run(() -> {
                    preflightStarted.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("preflight timed out");
                }, () -> { sent.incrementAndGet(); return null; }, closed::get);
                result.set(new AssertionError("closed request was sent"));
            } catch (IllegalStateException expected) {
                // Close occurred while the preflight was blocked, before mutation.
            } catch (Throwable error) { result.set(error); }
        });
        worker.start();
        if (!preflightStarted.await(2, TimeUnit.SECONDS)) throw new AssertionError("preflight did not start");
        closed.set(true);
        release.countDown();
        worker.join(2000);
        if (worker.isAlive() || result.get() != null || sent.get() != 1) throw new AssertionError("close did not block send", result.get());

        AtomicReference<Throwable> interruptResult = new AtomicReference<>();
        Thread interrupted = new Thread(() -> {
            try {
                SyntheticMutationDispatch.run(() -> Thread.currentThread().interrupt(), () -> { sent.incrementAndGet(); return null; }, () -> false);
                interruptResult.set(new AssertionError("interrupted request was sent"));
            } catch (IllegalStateException expected) {
                if (!Thread.currentThread().isInterrupted()) interruptResult.set(new AssertionError("interrupt was cleared"));
            } catch (Throwable error) { interruptResult.set(error); }
        });
        interrupted.start(); interrupted.join(2000);
        if (interrupted.isAlive() || interruptResult.get() != null || sent.get() != 1) throw new AssertionError("interrupt did not block send", interruptResult.get());
        System.out.println("Synthetic mutation dispatch tests passed");
    }
}
