
# A. Probabilistic Random Generator

📚 **[API Documentation](https://tguinot.github.io/hs/probabilistic-random-gen/com/hsbc/random/package-summary.html)**

## Overview
Java library for sampling integers from deterministic probability distributions. Built with Maven.

## Highlights

- **Immutable data model**: `NumAndProbability` validates `float` probabilities (NaN, ∞, <0, >1 rejected) and exposes safe getters.
- **Production-ready generator**: `WeightedRandomGenerator` pre-computes a cumulative distribution, filters zero-mass entries, and samples in O(log n) using `Arrays.binarySearch`.
- **Constructor-based initialization**: All fields are `final` and initialized once in the constructor, enabling thread-safety without synchronization for concurrent reads.
- **Edge-case coverage**: Tests verify rounding tolerance, duplicate numbers, tiny probabilities down to `Float.MIN_VALUE`, boundary random values, and large (1000 item) distributions.


## Getting Started

- **Build & verify (run from repository root)**
  ```bash
  mvn clean verify
  ```

- **Run the demo**
  ```bash
  mvn -pl probabilistic-random-gen exec:java
  ```

- **Generate coverage report**
  ```bash
  mvn -pl probabilistic-random-gen verify
  open probabilistic-random-gen/target/site/jacoco/index.html
  ```

## Usage

See [`Example.java`](probabilistic-random-gen/src/main/java/com/hsbc/random/examples/Example.java) for runnable samples.


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


# B. Event Bus

📚 **[API Documentation](https://tguinot.github.io/hs/event-bus/com/hsbc/eventbus/package-summary.html)**

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

```bash
mvn -pl event-bus verify
open event-bus/target/site/jacoco/index.html
```

## Usage

See [`Example.java`](event-bus/src/main/java/com/hsbc/eventbus/examples/Example.java) for runnable samples.

### ThreadUnsafeEventBus ([`Example.java`](event-bus/src/main/java/com/hsbc/eventbus/examples/Example.java))

A lightweight option optimized for single-threaded scenarios. `ThreadUnsafeEventBus` trades synchronization for throughput by using basic collections (`HashMap`, `ArrayList`). Only one thread should interact with the bus at a time or you must guard access externally.

If multiple threads will publish or subscribe concurrently, prefer `ThreadSafeEventBus` instead.

#### Core Characteristics
- **Zero synchronization overhead:** relies on `HashMap` and `ArrayList` internally.
- **Type-safe subscriptions:** register handlers for concrete event classes.
- **Dead-event optionality:** emits `DeadEvent` when no subscribers handle an event (configurable via constructor).
- **Lightweight metrics:** tracks total published events and dead-event count for diagnostics.


### ThreadSafeEventBus

Designed for concurrent environments, `ThreadSafeEventBus` uses `ConcurrentHashMap`, `CopyOnWriteArrayList`, and `AtomicLong` to guarantee safe access from multiple threads without requiring external locks. Subscriber callbacks execute on the publishing thread, so you control execution context.


#### Core Characteristics
- **Full thread safety:** all public APIs support concurrent calls.
- **Non-blocking collections:** `ConcurrentHashMap` registry with `CopyOnWriteArrayList` per event type.
- **Atomic metrics:** publish counts and dead-event totals tracked with `AtomicLong`.
- **Hierarchy-aware dispatch:** subscribers registered for supertypes/interfaces still receive subclass events.


### CoalescingThreadSafeEventBus

Extends the thread-safe core with event coalescing, ensuring subscribers only see the most recent update for a given key. `CoalescingThreadSafeEventBus` is ideal for high-frequency feeds—such as market data—where intermediate states can be dropped safely.


#### Core Characteristics
- **Per-subscriber coalescing:** each handler can specify its own key extractor and optional filter.
- **Global configuration:** enable coalescing for an entire event class via `configureCoalescing`.
- **Null-key bypass:** events with `null` keys skip coalescing and are delivered immediately.
- **Detailed metrics:** exposes counts for coalesced, replaced, and pending events.
- **Async-friendly:** optional `ScheduledExecutorService` batches deliveries in background threads.


# C. Sliding Window Throttler

📚 **[API Documentation](https://tguinot.github.io/hs/throttler/com/hsbc/throttler/package-summary.html)**

## Overview
`SlidingWindowThrottler` is a rate limiter that enforces a maximum number of permits inside a moving time window. The implementation lives in `throttler/src/main/java/com/hsbc/throttler/SlidingWindowThrottler.java` and implements the `Throttler` contract.

## Highlights
- **Sliding window enforcement**: Maintains recent timestamps and evicts expired entries before each decision.
- **Synchronous gating**: `shouldProceed()` atomically consumes permits when capacity is available.
- **Asynchronous callbacks**: `notifyWhenCanProceed(Runnable)` queues work and replays it once permits recycle.
- **Pluggable infrastructure**: Optional `Clock`, `ScheduledExecutorService`, and callback `ExecutorService` make deterministic tests and integrations straightforward.

## Getting Started
```bash
mvn -pl throttler exec:java
```

```bash
mvn -pl throttler verify
open throttler/target/site/jacoco/index.html
```

## Usage

See [`Example.java`](throttler/src/main/java/com/hsbc/throttler/examples/Example.java) for runnable samples.

### Configuration notes
- **Default executors**: The no-arg constructor provisions daemon scheduler and callback executors.
- **Custom executors**: Supply your own scheduler/executor pair to integrate with container-managed pools or to bound concurrency.
- **Deterministic testing**: Inject a fixed `Clock` to simulate time progression without sleeps.


# D. Sliding Window Statistics

📚 **[API Documentation](https://tguinot.github.io/hs/window/com/hsbc/window/package-summary.html)**

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

```bash
mvn -pl window verify
open window/target/site/jacoco/index.html
```

## Usage

See [`Example.java`](window/src/main/java/com/hsbc/window/examples/Example.java) for an end-to-end statistics demo.