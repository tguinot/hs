Assignements

# Probabilistic Random Generator

## Overview
Robust Java library for sampling integers from deterministic probability distributions. Built with Maven, tested with JUnit 5 and AssertJ, and exercised through a rich example suite.

## Highlights

- **Clean contract**: `ProbabilisticRandomGen` exposes a single `nextFromSample()` method.
- **Immutable data model**: `NumAndProbability` validates `float` probabilities (NaN, ∞, <0, >1 rejected) and exposes safe getters.
- **Production-ready generator**: `WeightedRandomGenerator` pre-computes a cumulative distribution, filters zero-mass entries, and samples in O(log n) using `Arrays.binarySearch`.
- **Edge-case coverage**: Tests verify rounding tolerance, duplicate numbers, tiny probabilities down to `Float.MIN_VALUE`, boundary random values, and large (1000 item) distributions.
- **Examples included**: `Example` demonstrates weighted sampling, dice simulation, and statistical validation with deterministic seeds.


## Getting Started

- **Build & verify (run from repository root)**
  ```bash
  mvn clean compile
  mvn test
  mvn package
  ```

- **Run the demo**
  ```bash
  mvn -pl probabilistic-random-gen exec:java
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



# Event Bus

## Overview
The module provides three in-process EventBus implementations covering single-threaded, multi-threaded, and coalescing workloads.

## Highlights
- **ThreadUnsafeEventBus** for lightweight single-threaded dispatch.
- **ThreadSafeEventBus** for concurrent publishers/subscribers without external locking.
- **CoalescingThreadSafeEventBus** to collapse rapid updates by key so subscribers see only the latest values.

## Getting Started
```bash
mvn -pl event-bus exec:java
```

## Usage

### ThreadUnsafeEventBus

A lightweight option optimized for single-threaded scenarios. `ThreadUnsafeEventBus` trades synchronization for throughput by using basic collections (`HashMap`, `ArrayList`). Only one thread should interact with the bus at a time or you must guard access externally.

If multiple threads will publish or subscribe concurrently, prefer `ThreadSafeEventBus` instead.

#### Core Characteristics
- **Zero synchronization overhead:** relies on `HashMap` and `ArrayList` internally.
- **Type-safe subscriptions:** register handlers for concrete event classes.
- **Dead-event optionality:** emits `DeadEvent` when no subscribers handle an event (configurable via constructor).
- **Lightweight metrics:** tracks total published events and dead-event count for diagnostics.

#### Quick Start
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

#### Best Practices
- **Single-thread discipline:** publish, subscribe, and unsubscribe from the same thread.
- **Avoid manual list mutation:** let the bus manage subscriber lists to prevent `ConcurrentModificationException`.
- **Use try-with-resources:** take advantage of `AutoCloseable` for deterministic cleanup.
- **Inspect metrics in tests:** call `getDetailedMetrics()` to verify event flow during testing.

#### Key API Highlights
- `addSubscriber(Class<E>, Consumer<E>)` — register a handler.
- `addSubscriberForFilteredEvents(Class<E>, Predicate<E>, Consumer<E>)` — register with filtering.
- `publishEvent(E event)` — dispatch an event; throws `NullPointerException` on `null`.
- `getSubscriberCount(Class<?>)` / `getTotalSubscriberCount()` — inspect active subscriptions.
- `close()` — releases all subscribers and resets metrics.

Keep interactions confined to a single thread and `ThreadUnsafeEventBus` delivers minimal-overhead event routing with familiar APIs.


### ThreadSafeEventBus

Designed for concurrent environments, `ThreadSafeEventBus` uses `ConcurrentHashMap`, `CopyOnWriteArrayList`, and `AtomicLong` to guarantee safe access from multiple threads without requiring external locks. Subscriber callbacks execute on the publishing thread, so you control execution context.


#### Core Characteristics
- **Full thread safety:** all public APIs support concurrent calls.
- **Non-blocking collections:** `ConcurrentHashMap` registry with `CopyOnWriteArrayList` per event type.
- **Atomic metrics:** publish counts and dead-event totals tracked with `AtomicLong`.
- **Hierarchy-aware dispatch:** subscribers registered for supertypes/interfaces still receive subclass events.

#### Quick Start
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

#### Best Practices
- **Avoid long-running handlers:** callbacks execute on the publishing thread; offload heavy work if needed.
- **Use filters for hot topics:** reduce per-event work with `addSubscriberForFilteredEvents`.
- **Combine with `AutoCloseable`:** close the bus during shutdown to flush subscribers and log metrics.

#### Key API Highlights
- `addSubscriber(Class<E>, Consumer<E>)` / `addSubscriberForFilteredEvents(...)` — thread-safe registration.
- `publishEvent(E event)` — safe to call from any thread.
- `getEventClassesWithSubscribers()` — view registered event types.
- `getDetailedMetrics()` — synchronized snapshot with per-class subscriber counts.
- `close()` — removes subscribers and resets state while preventing further use.

Use `ThreadSafeEventBus` whenever concurrency is a requirement; it provides safe, predictable event delivery without manual locking.


### CoalescingThreadSafeEventBus

Extends the thread-safe core with event coalescing, ensuring subscribers only see the most recent update for a given key. `CoalescingThreadSafeEventBus` is ideal for high-frequency feeds—such as market data—where intermediate states can be dropped safely.


#### Core Characteristics
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

## Overview
`SlidingWindowThrottler` is a production-quality rate limiter that enforces a maximum number of permits inside a moving time window. The implementation lives in `throttler/src/main/java/com/hsbc/throttler/SlidingWindowThrottler.java` and implements the `Throttler` contract.

## Highlights
- **Sliding window enforcement**: Maintains recent timestamps and evicts expired entries before each decision.
- **Synchronous gating**: `shouldProceed()` atomically consumes permits when capacity is available.
- **Asynchronous callbacks**: `notifyWhenCanProceed(Runnable)` queues work and replays it once permits recycle.
- **Pluggable infrastructure**: Optional `Clock`, `ScheduledExecutorService`, and callback `ExecutorService` make deterministic tests and integrations straightforward.

## Getting Started
```bash
mvn -pl throttler exec:java
```

## Usage

### Synchronous throttling
```java
SlidingWindowThrottler throttler = new SlidingWindowThrottler(5, Duration.ofSeconds(1));

if (throttler.shouldProceed() == ThrottleResult.PROCEED) {
    callDownstreamService();
} else {
    throttler.notifyWhenCanProceed(() -> callDownstreamService());
}

throttler.shutdown();
```

### Configuration notes
- **Default executors**: The no-arg constructor provisions daemon scheduler and callback executors.
- **Custom executors**: Supply your own scheduler/executor pair to integrate with container-managed pools or to bound concurrency.
- **Deterministic testing**: Inject a fixed `Clock` to simulate time progression without sleeps.

### API reference
- `shouldProceed()` cleans up timestamps, attempts to consume a permit, and returns `ThrottleResult`.
- `notifyWhenCanProceed(Runnable)` queues callbacks and schedules `checkAndNotifyWaiters()` when capacity returns.
- `shutdown()` cancels scheduled tasks and closes owned executors.

### Best practices
- **Keep callbacks lightweight** to avoid starving the callback executor.
- **Handle shutdown paths** by guarding against `RejectedExecutionException` after close.
# Sliding Window Statistics

## Overview
`SlidingWindowStatisticsImpl` maintains a fixed-size FIFO of recent measurements and provides descriptive analytics via the `SlidingWindowStatistics` API.

## Highlights
- **Comprehensive analytics**: Tracks mean, standard deviation, percentiles, kurtosis, skewness, and quantiles.
- **Real-time updates**: `add(long measurement)` synchronizes access and evicts the oldest value once capacity is exceeded.
- **Cached snapshots**: `getLatestStatistics()` uses memoization for O(1) reads between updates.
- **Asynchronous subscribers**: `subscribeForStatistics(StatisticsSubscriber)` notifies listeners via a bounded single-thread executor.

## Getting Started
```bash
mvn -pl window exec:java
```

## Usage
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
    System.out.println("Mean latency = " + latest.getMean());
}