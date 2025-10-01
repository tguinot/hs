
# A. Probabilistic Random Generator

📚 **[API Documentation](https://tguinot.github.io/hs/probabilistic-random-gen/com/hsbc/random/package-summary.html)**

## Overview
Samples integers from probability distributions.

## Highlights

- **Immutable data model**: `NumAndProbability` validates probabilities (rejects NaN, ∞, <0, >1).
- **Weighted random generator**: `WeightedRandomGenerator` pre-computes cumulative distribution and samples in O(log n).
- **Thread-safe**: Uses `ThreadLocalRandom` by default (lock-free, no synchronization).
- **Constructor-based initialization**: All fields are `final` and initialized once in the constructor, making the class immutable.


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
  - Strips zero-probability entries.
  - Enforces probabilities sum to 1.0 ± 1e-6, then normalizes.

- **Sampling**
  - Draws random value in `[0.0, 1.0)`.
  - Binary search finds matching cumulative boundary.
  - Duplicate numbers supported (composite weights).

- **Threading**
  - Fully thread-safe using `ThreadLocalRandom`.
  - For testing, pass custom `Random` to two-argument constructor.


# B. Event Bus

📚 **[API Documentation](https://tguinot.github.io/hs/event-bus/com/hsbc/eventbus/package-summary.html)**

## Overview
Three EventBus implementations: single-threaded, multi-threaded, and coalescing.

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

### ThreadUnsafeEventBus

Optimized for single-threaded use with `HashMap` and `ArrayList`. No synchronization overhead. Use `ThreadSafeEventBus` for concurrent access.

#### Core Characteristics
- **Zero synchronization overhead:** uses `HashMap` and `ArrayList`.
- **Type-safe subscriptions:** handlers registered per event class.
- **Dead-event handling:** optional `DeadEvent` for unhandled events.
- **Metrics:** tracks published events and dead-event count.


### ThreadSafeEventBus

Thread-safe using `ConcurrentHashMap`, `CopyOnWriteArrayList`, and `AtomicLong`. Callbacks execute on the publishing thread.


#### Core Characteristics
- **Thread-safe:** all APIs support concurrent calls.
- **Non-blocking:** uses concurrent collections.
- **Atomic metrics:** counts tracked with `AtomicLong`.
- **Hierarchy-aware:** supertype subscribers receive subclass events.


### CoalescingThreadSafeEventBus

Coalesces events by key so subscribers receive only the latest update. Ideal for high-frequency feeds like market data.


#### Core Characteristics
- **Per-subscriber coalescing:** custom key extractor and filter per handler.
- **Global configuration:** coalesce entire event classes.
- **Null-key bypass:** immediate delivery for null keys.
- **Metrics:** tracks coalesced, replaced, and pending events.
- **Async support:** optional executor for batched delivery.


# C. Sliding Window Throttler

📚 **[API Documentation](https://tguinot.github.io/hs/throttler/com/hsbc/throttler/package-summary.html)**

## Overview
Rate limiter enforcing maximum permits within a sliding time window.

## Highlights
- **Sliding window:** evicts expired timestamps before each check.
- **Synchronous:** `shouldProceed()` atomically consumes permits.
- **Asynchronous:** `notifyWhenCanProceed()` queues callbacks until permits available.
- **Pluggable:** inject `Clock` and executors for testing.

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

### Configuration
- **Default:** daemon scheduler and callback executors.
- **Custom:** provide your own executors.
- **Testing:** inject fixed `Clock` for deterministic tests.


# D. Sliding Window Statistics

📚 **[API Documentation](https://tguinot.github.io/hs/window/com/hsbc/window/package-summary.html)**

## Overview
Fixed-size FIFO window with descriptive statistics.

## Highlights
- **Analytics:** mean, std dev, percentiles, kurtosis, skewness, quantiles.
- **Real-time:** synchronized `add()` evicts oldest when full.
- **Cached:** `getLatestStatistics()` returns O(1) snapshot.
- **Async subscribers:** notified via single-thread executor.

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