# ThreadUnsafeEventBus, ThreadSafeEventBus & CoalescingThreadSafeEventBus

Three `EventBus` implementations ship with this project:
- `ThreadUnsafeEventBus` for single-threaded workflows that value raw throughput.
- `ThreadSafeEventBus` for concurrent publishers and subscribers that require coordination-free access.
- `CoalescingThreadSafeEventBus` for concurrent publishers ensuring subscribers only see the most recent update for a given key.

Each section below explains when to choose an implementation, how it behaves, and how to get started quickly.

---

## ThreadUnsafeEventBus

A lightweight option optimized for single-threaded scenarios. `ThreadUnsafeEventBus` trades synchronization for throughput by using basic collections (`HashMap`, `ArrayList`). Only one thread should interact with the bus at a time or you must guard access externally.

If multiple threads will publish or subscribe concurrently, prefer `ThreadSafeEventBus` instead.

### Core Characteristics
- **Zero synchronization overhead:** relies on `HashMap` and `ArrayList` internally.
- **Type-safe subscriptions:** register handlers for concrete event classes.
- **Dead-event optionality:** emits `DeadEvent` when no subscribers handle an event (configurable via constructor).
- **Lightweight metrics:** tracks total published events and dead-event count for diagnostics.

### Quick Start
```java
ThreadUnsafeEventBus eventBus = new ThreadUnsafeEventBus();

record OrderCreated(String orderId) {}
record InventoryAdjusted(String sku, int delta) {}

Subscription orderSub = eventBus.addSubscriber(OrderCreated.class, event -> {
    System.out.println("Processing order " + event.orderId());
});

// All calls must occur on the same thread
eventBus.publishEvent(new OrderCreated("ORD-123"));

// Unsubscribe when finished
orderSub.unsubscribe();

eventBus.close();
```

### Best Practices
- **Single-thread discipline:** publish, subscribe, and unsubscribe from the same thread.
- **Avoid manual list mutation:** let the bus manage subscriber lists to prevent `ConcurrentModificationException`.
- **Use try-with-resources:** take advantage of `AutoCloseable` for deterministic cleanup.
- **Inspect metrics in tests:** call `getDetailedMetrics()` to verify event flow during testing.

### Key API Highlights
- `addSubscriber(Class<E>, Consumer<E>)` — register a handler.
- `addSubscriberForFilteredEvents(Class<E>, Predicate<E>, Consumer<E>)` — register with filtering.
- `publishEvent(E event)` — dispatch an event; throws `NullPointerException` on `null`.
- `getSubscriberCount(Class<?>)` / `getTotalSubscriberCount()` — inspect active subscriptions.
- `close()` — releases all subscribers and resets metrics.

Keep interactions confined to a single thread and `ThreadUnsafeEventBus` delivers minimal-overhead event routing with familiar APIs.

---

## ThreadSafeEventBus

Designed for concurrent environments, `ThreadSafeEventBus` uses `ConcurrentHashMap`, `CopyOnWriteArrayList`, and `AtomicLong` to guarantee safe access from multiple threads without requiring external locks. Subscriber callbacks execute on the publishing thread, so you control execution context.


### Core Characteristics
- **Full thread safety:** all public APIs support concurrent calls.
- **Non-blocking collections:** `ConcurrentHashMap` registry with `CopyOnWriteArrayList` per event type.
- **Atomic metrics:** publish counts and dead-event totals tracked with `AtomicLong`.
- **Hierarchy-aware dispatch:** subscribers registered for supertypes/interfaces still receive subclass events.

### Quick Start
```java
ThreadSafeEventBus eventBus = new ThreadSafeEventBus();

record PriceTick(String symbol, double price) {}

eventBus.addSubscriber(PriceTick.class, tick -> {
    System.out.println(Thread.currentThread().getName() + " received " + tick);
});

ExecutorService pool = Executors.newFixedThreadPool(4);
for (int i = 0; i < 10; i++) {
    pool.submit(() -> eventBus.publishEvent(new PriceTick("AAPL", Math.random() * 200)));
}

pool.shutdown();
eventBus.close();
```

### Best Practices
- **Avoid long-running handlers:** callbacks execute on the publishing thread; offload heavy work if needed.
- **Use filters for hot topics:** reduce per-event work with `addSubscriberForFilteredEvents`.
- **Monitor metrics:** `getDetailedMetrics()` returns thread-safe snapshots of bus activity.
- **Combine with `AutoCloseable`:** close the bus during shutdown to flush subscribers and log metrics.

### Key API Highlights
- `addSubscriber(Class<E>, Consumer<E>)` / `addSubscriberForFilteredEvents(...)` — thread-safe registration.
- `publishEvent(E event)` — safe to call from any thread.
- `getEventClassesWithSubscribers()` — view registered event types.
- `getDetailedMetrics()` — synchronized snapshot with per-class subscriber counts.
- `close()` — removes subscribers and resets state while preventing further use.

Use `ThreadSafeEventBus` whenever concurrency is a requirement; it provides safe, predictable event delivery without manual locking.

---

## CoalescingThreadSafeEventBus

Extends the thread-safe core with event coalescing, ensuring subscribers only see the most recent update for a given key. `CoalescingThreadSafeEventBus` is ideal for high-frequency feeds—such as market data—where intermediate states can be dropped safely.


### Core Characteristics
- **Per-subscriber coalescing:** each handler can specify its own key extractor and optional filter.
- **Global configuration:** enable coalescing for an entire event class via `configureCoalescing`.
- **Null-key bypass:** events with `null` keys skip coalescing and are delivered immediately.
- **Detailed metrics:** exposes counts for coalesced, replaced, and pending events.
- **Async-friendly:** optional `ScheduledExecutorService` batches deliveries in background threads.

### Quick Start
```java
CoalescingThreadSafeEventBus eventBus = new CoalescingThreadSafeEventBus();

record Quote(String symbol, double price, long sequence) {}

eventBus.addCoalescingSubscriber(
    Quote.class,
    quote -> quote.symbol(),
    quote -> System.out.println("Latest quote for " + quote.symbol() + " = " + quote.price())
);

// Rapid updates collapse to the latest per symbol
eventBus.publishEvent(new Quote("AAPL", 190.10, 101));
eventBus.publishEvent(new Quote("AAPL", 190.15, 102));

eventBus.flushCoalescedEvents();
eventBus.close();
```

### Best Practices
- **Choose stable keys:** use identifiers like `(symbol, priceLevel, side)` to prevent unintended overwrites.
- **Flush strategically:** call `flushCoalescedEvents()` during idle windows or before shutdown to deliver pending data.
- **Handle null keys intentionally:** returning `null` from the extractor bypasses coalescing for critical events.
- **Combine with async delivery cautiously:** when using an executor, ensure subscribers are thread-safe.

### Key API Highlights
- `addCoalescingSubscriber(Class<E>, Function<E, K>, Consumer<E>)` — register coalescing handlers.
- `addCoalescingSubscriber(..., Predicate<E>, Consumer<E>)` — add filtering before coalescing.
- `configureCoalescing(Class<E>, Function<E, K>)` — enable global coalescing for an event type.
- `flushCoalescedEvents()` / `flushCoalescedEvents(Class<E>)` — push pending updates immediately.
- `getPendingCoalescedEventCount()` — inspect backlog size (optionally per class).
- `getDetailedMetrics()` — includes coalescing counters alongside base metrics.

Select `CoalescingThreadSafeEventBus` when you need thread safety plus intelligent conflation to keep subscribers focused on the latest state.


# Sliding Window Throttler

`SlidingWindowThrottler` is a production-quality rate limiter that enforces a maximum number of permits inside a moving time window. It lives under `throttler/src/main/java/com/hsbc/throttler/SlidingWindowThrottler.java` and implements the `Throttler` contract shared by the throttler module.

## Highlights

- **Sliding window enforcement**: Tracks the most recent timestamps and rejects work once `maxPermits` occur inside `period`. Old entries are evicted lazily on every decision.
- **Synchronous gating**: `shouldProceed()` returns `ThrottleResult.PROCEED` when a permit is available and updates the internal queue atomically.
- **Asynchronous callbacks**: `notifyWhenCanProceed(Runnable)` queues callbacks when permits are exhausted and replays them via a scheduled task once capacity returns.
- **Pluggable infrastructure**: Constructors accept custom `Clock`, `ScheduledExecutorService`, and callback `ExecutorService` for deterministic tests or integration into existing thread pools.

## Quick Start

```java
SlidingWindowThrottler throttler = new SlidingWindowThrottler(5, Duration.ofSeconds(1));

if (throttler.shouldProceed() == ThrottleResult.PROCEED) {
    callDownstreamService();
} else {
    throttler.notifyWhenCanProceed(() -> callDownstreamService());
}

// Shutdown when the component is no longer needed
throttler.shutdown();
```

## Configuration Options

- **Default executors**: The no-arg constructor provisions a daemon `ScheduledExecutorService` for housekeeping and a cached thread pool for callbacks.
- **Custom executors**: Supply your own scheduler and callback executor to integrate with container-managed thread pools or to enforce bounded concurrency.
- **Deterministic testing**: Inject a fixed `Clock` to simulate time progression and validate throttling behaviour without sleeping.

## API Reference

- **`shouldProceed()`**: Cleans up timestamps outside the window, attempts to consume a permit, and returns a `ThrottleResult` indicating whether to execute immediately.
- **`notifyWhenCanProceed(Runnable)`**: Enqueues the callback when permits are unavailable, then schedules `checkAndNotifyWaiters()` to retry after the oldest timestamp expires.
- **`shutdown()`**: Cancels scheduled tasks, rejects new work, and optionally shuts down the internally owned callback executor.

## Best Practices

- **Keep callbacks lightweight**: Submitted callbacks run on the provided executor. Offload blocking work or supply an executor that matches your workload profile.
- **Handle shutdown paths**: Expect `RejectedExecutionException` when submitting callbacks after shutdown and design callers to degrade gracefully.
- **Prefer reuse**: Instantiate one throttler per logical resource instead of per request to make the sliding window meaningful.
- **Monitor backlog**: Apply application-level metrics around pending callback queue size to detect saturation before it impacts user-facing latency.


# Probabilistic Random Generator

Robust Java library for sampling integers from deterministic probability distributions. Built with Maven, tested with JUnit 5 and AssertJ, and exercised through a rich example suite.

## Highlights

- **Clean contract**: `ProbabilisticRandomGen` exposes a single `nextFromSample()` method.
- **Immutable data model**: `NumAndProbability` validates `float` probabilities (NaN, ∞, <0, >1 rejected) and exposes safe getters.
- **Production-ready generator**: `WeightedRandomGenerator` pre-computes a cumulative distribution, filters zero-mass entries, and samples in O(log n) using `Arrays.binarySearch`.
- **Edge-case coverage**: Tests verify rounding tolerance, duplicate numbers, tiny probabilities down to `Float.MIN_VALUE`, boundary random values, and large (1000 item) distributions.
- **Examples included**: `Example` demonstrates weighted sampling, dice simulation, and statistical validation with deterministic seeds.

## Project Layout

```
probabilistic-random-gen/
├── pom.xml
├── README.md
├── src/
│   ├── main/java/com/hsbc/random/
│   │   ├── ProbabilisticRandomGen.java
│   │   ├── WeightedRandomGenerator.java
│   │   └── Example.java
│   └── test/java/com/hsbc/random/
│       ├── NumAndProbabilityTest.java
│       └── WeightedRandomGeneratorTest.java
```

## Getting Started

- **Prerequisites**
  - Java 11+
  - Maven 3.6+

- **Build & verify**
  ```bash
  mvn clean compile
  mvn test
  mvn package
  ```

- **Run bundled examples**
  ```bash
  mvn exec:java -Dexec.mainClass="com.hsbc.random.Example"
  ```

## Usage

- **Create a generator**
  ```java
  List<ProbabilisticRandomGen.NumAndProbability> distribution = List.of(
      new ProbabilisticRandomGen.NumAndProbability(1, 0.10f),
      new ProbabilisticRandomGen.NumAndProbability(2, 0.30f),
      new ProbabilisticRandomGen.NumAndProbability(3, 0.60f)
  );

  ProbabilisticRandomGen generator = new WeightedRandomGenerator(distribution);
  int sample = generator.nextFromSample();
  ```

- **Deterministic sampling for tests**
  ```java
  Random fixedSeed = new Random(12345L);
  ProbabilisticRandomGen generator = new WeightedRandomGenerator(distribution, fixedSeed);
  ```

## API Reference

- **`ProbabilisticRandomGen`** (`src/main/java/com/hsbc/random/ProbabilisticRandomGen.java`)
  - `int nextFromSample()`
  - Nested `NumAndProbability` with validated constructor, `getNumber()`, `getProbabilityOfSample()`, `equals()/hashCode()/toString()`

- **`WeightedRandomGenerator`** (`src/main/java/com/hsbc/random/WeightedRandomGenerator.java`)
  - Constructors accepting a distribution and optional `Random`
  - Filters out zero-probability entries and normalises cumulative array to `1.0f`
  - Defensive copies via `getNumbers()` and `getCumulativeProbabilities()`

## Implementation Notes

- **Validation pipeline**
  - Rejects null/empty lists.
  - Strips zero-probability entries (ensuring at least one positive weight remains).
  - Enforces probabilities sum to 1.0 within `1e-6`, then normalises and clamps final cumulative entry to `1.0f`.

- **Sampling semantics**
  - Random value drawn in `[0.0, 1.0)`.
  - `Arrays.binarySearch` locates the first cumulative boundary greater than the draw; tie-breaking ensures right-open intervals `[a, b)` are respected.
  - Duplicated numbers remain as separate buckets, allowing composite weights.

- **Threading**
  - `NumAndProbability` is immutable.
  - `WeightedRandomGenerator` uses a mutable `Random`; prefer per-thread instances or external synchronisation when sharing.

## Quality & Testing

- **Coverage focus** (`src/test/java/com/hsbc/random/WeightedRandomGeneratorTest.java`)
  - Parameterised checks for invalid sums, rounding tolerance, tiny probabilities, and complex distributions sampled 50k+ times with dynamic tolerances.
  - Deterministic custom `Random` subclasses verify boundary values (`0.0`, `0.25`, `0.99999`).
  - Performance guard ensures constructing 1000-entry distributions completes <1s and sampling 10k values <100ms.
- **Data-class tests**: `NumAndProbabilityTest` confirms validation, equality, hashCode, and `toString()` formatting.

## Examples Overview (`src/main/java/com/hsbc/random/Example.java`)

- **Simple weighted sampling**: 10 draws from a 3-value distribution.
- **Dice simulation**: Fair six-sided die rolled 20 times with seeded randomness.
- **Statistical validation**: 100,000 samples summarised with actual vs expected percentages.


# Sliding Window Statistics

[SlidingWindowStatisticsImpl](cci:2://file:///Users/tguinot/code/hsbc/window/src/main/java/com/hsbc/window/SlidingWindowStatisticsImpl.java:61:0-525:1) ([window/src/main/java/com/hsbc/window/SlidingWindowStatisticsImpl.java](cci:7://file:///Users/tguinot/code/hsbc/window/src/main/java/com/hsbc/window/SlidingWindowStatisticsImpl.java:0:0-0:0)) maintains a fixed-size FIFO of recent measurements and exposes rich descriptive analytics via the [SlidingWindowStatistics](cci:2://file:///Users/tguinot/code/hsbc/window/src/main/java/com/hsbc/window/SlidingWindowStatistics.java:2:0-7:1) API.

## Highlights

- **Comprehensive analytics**: Tracks mean, standard deviation, mode, median, arbitrary percentiles, kurtosis, skewness, and regularly spaced quantiles.
- **Real-time updates**: [add(long measurement)](cci:1://file:///Users/tguinot/code/hsbc/window/src/main/java/com/hsbc/window/SlidingWindowStatistics.java:3:4-3:31) is synchronized to keep the sliding window consistent and evicts the oldest value once the capacity is exceeded.
- **Cached snapshots**: [getLatestStatistics()](cci:1://file:///Users/tguinot/code/hsbc/window/src/main/java/com/hsbc/window/SlidingWindowStatistics.java:6:4-6:37) leverages memoization—statistics are recomputed only when the window mutates, giving O(1) reads in the steady state.
- **Subscriber notifications**: [subscribeForStatistics(StatisticsSubscriber)](cci:1://file:///Users/tguinot/code/hsbc/window/src/main/java/com/hsbc/window/SlidingWindowStatistics.java:4:4-4:65) registers listeners that are invoked asynchronously via a bounded single-thread executor whenever fresh statistics are available.

## Quick Start

```java
try (SlidingWindowStatistics stats = new SlidingWindowStatisticsImpl(100)) {
    stats.subscribeForStatistics(new StatisticsSubscriber() {
        @Override
        public boolean shouldNotify(Statistics snapshot) {
            return snapshot.getSize() == 100;
        }

        @Override
        public void onStatisticsUpdate(Statistics snapshot) {
            System.out.println("P95 latency = " + snapshot.getPctile(95));
        }
    });

    for (long latency : latencies) {
        stats.add(latency);
    }

    Statistics latest = stats.getLatestStatistics();
    System.out.println(\"Mean latency = \" + latest.getMean());
}