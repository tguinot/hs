package com.hsbc.eventbus.examples;

import com.hsbc.eventbus.CoalescingThreadSafeEventBus;
import com.hsbc.eventbus.Coalescable;
import com.hsbc.eventbus.Subscription;
import com.hsbc.eventbus.ThreadSafeEventBus;
import com.hsbc.eventbus.ThreadUnsafeEventBus;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compact demonstrations of the three EventBus implementations.
 */
public final class Example {

    private enum Side { BID, ASK }

    private static final class PriceLevelKey {
        final String symbol;
        final double price;
        final Side side;

        PriceLevelKey(String symbol, double price, Side side) {
            this.symbol = symbol;
            this.price = price;
            this.side = side;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PriceLevelKey)) return false;
            PriceLevelKey that = (PriceLevelKey) o;
            return Double.compare(that.price, price) == 0
                && Objects.equals(symbol, that.symbol)
                && side == that.side;
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, price, side);
        }

        @Override
        public String toString() {
            return symbol + " " + side + " @" + String.format("%.2f", price);
        }
    }

    private Example() {
        // utility class
    }

    public static void main(String[] args) {
        System.out.println("=== ThreadUnsafeEventBus ===");
        demoThreadUnsafeEventBus();

        System.out.println();
        System.out.println("=== ThreadSafeEventBus ===");
        demoThreadSafeEventBus();

        System.out.println();
        System.out.println("=== CoalescingThreadSafeEventBus ===");
        demoCoalescingThreadSafeEventBus();
    }

    private static void demoThreadUnsafeEventBus() {
        ThreadUnsafeEventBus eventBus = new ThreadUnsafeEventBus();

        class Greeting {
            final String message;
            Greeting(String message) { this.message = message; }
        }

        Subscription subscription = eventBus.addSubscriber(Greeting.class, event ->
            System.out.println("Received greeting: " + event.message)
        );

        eventBus.publishEvent(new Greeting("Hello from the single-threaded bus"));

        subscription.unsubscribe();
        eventBus.close();
    }

    private static void demoThreadSafeEventBus() {
        ThreadSafeEventBus eventBus = new ThreadSafeEventBus();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        class TaskUpdate {
            final String worker;
            TaskUpdate(String worker) { this.worker = worker; }
        }

        eventBus.addSubscriber(TaskUpdate.class, update ->
            System.out.println(Thread.currentThread().getName() + " processed update from " + update.worker)
        );

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            pool.submit(() -> eventBus.publishEvent(new TaskUpdate("worker-" + idx)));
        }

        pool.shutdown();
        while (!pool.isTerminated()) {
            Thread.yield();
        }

        eventBus.close();
    }

    private static void demoCoalescingThreadSafeEventBus() {
        CoalescingThreadSafeEventBus eventBus = new CoalescingThreadSafeEventBus();
        AtomicLong arrivalCounter = new AtomicLong();

        class PriceUpdate implements Coalescable {
            final double price;
            final String symbol;
            final Side side;
            final double size;
            final long sequenceNumber;
            final long arrivalOrder;
            final PriceLevelKey key;

            PriceUpdate(String symbol, double price, Side side, double size, long sequenceNumber) {
                this.price = price;
                this.symbol = symbol;
                this.side = side;
                this.size = size;
                this.sequenceNumber = sequenceNumber;
                this.arrivalOrder = arrivalCounter.incrementAndGet();
                this.key = new PriceLevelKey(symbol, price, side);
            }

            PriceLevelKey key() {
                return key;
            }

            @Override
            public Object getCoalescingKey() {
                return key;
            }

            @Override
            public String toString() {
                return String.format("%s %.0f@%.2f (seq %d, arrival#%d, side=%s)",
                    symbol, size, price, sequenceNumber, arrivalOrder, side);
            }
        }

        Map<PriceLevelKey, PriceUpdate> latest = new ConcurrentHashMap<>();

        eventBus.addCoalescingSubscriber(
            PriceUpdate.class,
            PriceUpdate::key,
            Comparator.comparingLong(update -> update.sequenceNumber),
            update -> {
                latest.put(update.key(), update);
                System.out.println("Delivered: " + update);
            }
        );

        // Same price level (symbol + price + side) repeatedly updates: only highest sequence survives.
        eventBus.publishEvent(new PriceUpdate("AAPL", 190.10, Side.BID, 1000, 2));
        eventBus.publishEvent(new PriceUpdate("AAPL", 190.10, Side.BID, 900, 1));   // older sequence arrives late
        eventBus.publishEvent(new PriceUpdate("AAPL", 190.10, Side.BID, 1200, 5));  // newest for AAPL bid @190.10

        // Different price level (same symbol/side but new price) coalesces independently.
        eventBus.publishEvent(new PriceUpdate("AAPL", 190.05, Side.BID, 1500, 4));

        // Different side creates a distinct key, even at same price.
        eventBus.publishEvent(new PriceUpdate("AAPL", 190.10, Side.ASK, 800, 3));

        // Different symbol also keeps its own slot.
        eventBus.publishEvent(new PriceUpdate("GOOG", 2780.00, Side.ASK, 50, 6));

        eventBus.flushCoalescedEvents(PriceUpdate.class);
        eventBus.close();

        System.out.println("Latest price levels:");
        latest.forEach((key, update) -> System.out.println("  " + key + " -> " + update));
    }
}

