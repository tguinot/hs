package com.hsbc.eventbus;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for CoalescingThreadSafeEventBus.
 */
class CoalescingThreadSafeEventBusTest {
    
    private CoalescingThreadSafeEventBus eventBus;
    private List<Object> receivedEvents;
    private CountDownLatch latch;
    
    @BeforeEach
    void setUp() {
        eventBus = new CoalescingThreadSafeEventBus();
        receivedEvents = Collections.synchronizedList(new ArrayList<>());
        latch = null;
    }
    
    // Test event classes
    static class MarketDataUpdate {
        final String symbol;
        final double price;
        final double size;
        final Side side;
        final long timestamp;
        
        enum Side { BID, ASK }
        
        MarketDataUpdate(String symbol, double price, double size, Side side) {
            this.symbol = symbol;
            this.price = price;
            this.size = size;
            this.side = side;
            this.timestamp = System.nanoTime();
        }
        
        @Override
        public String toString() {
            return String.format("%s %s: %.2f@%.2f", symbol, side, size, price);
        }
    }
    
    // Composite key for market data coalescing
    static class PriceLevelKey {
        final String symbol;
        final double price;
        final MarketDataUpdate.Side side;
        
        PriceLevelKey(String symbol, double price, MarketDataUpdate.Side side) {
            this.symbol = symbol;
            this.price = price;
            this.side = side;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PriceLevelKey that = (PriceLevelKey) o;
            return Double.compare(that.price, price) == 0 &&
                   Objects.equals(symbol, that.symbol) &&
                   side == that.side;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(symbol, price, side);
        }
        
        @Override
        public String toString() {
            return String.format("%s:%s:%.2f", symbol, side, price);
        }
    }
    
    static class CoalescableMarketData implements Coalescable {
        final String symbol;
        final double price;
        final long timestamp;
        
        CoalescableMarketData(String symbol, double price) {
            this.symbol = symbol;
            this.price = price;
            this.timestamp = System.nanoTime();
        }
        
        @Override
        public Object getCoalescingKey() {
            return symbol;
        }
        
        @Override
        public String toString() {
            return String.format("%s@%.2f", symbol, price);
        }
    }
    
    static class OrderUpdate {
        final String orderId;
        final String status;
        final int version;
        
        OrderUpdate(String orderId, String status, int version) {
            this.orderId = orderId;
            this.status = status;
            this.version = version;
        }
        
        @Override
        public String toString() {
            return String.format("Order[%s]:%s(v%d)", orderId, status, version);
        }
    }

    static class ParentEvent {
        private final String id;

        ParentEvent(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }
    }

    static class ChildEvent extends ParentEvent {
        ChildEvent(String id) {
            super(id);
        }
    }
    
    @Test
    @DisplayName("Should coalesce updates that share the same composite key")
    void shouldCoalesceEventsWithSameCompositeKey() throws InterruptedException {
        latch = new CountDownLatch(2); // Expect 2 events (different price levels)
        
        // Add coalescing subscriber with composite key
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            Comparator.comparingLong(update -> update.timestamp),
            update -> {
                receivedEvents.add(update);
                latch.countDown();
            }
        );
        
        MarketDataUpdate level150Initial = new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID);
        MarketDataUpdate level150Replacement = new MarketDataUpdate("AAPL", 150.00, 200.0, MarketDataUpdate.Side.BID);
        MarketDataUpdate level15005 = new MarketDataUpdate("AAPL", 150.05, 300.0, MarketDataUpdate.Side.BID);
        MarketDataUpdate level150Latest = new MarketDataUpdate("AAPL", 150.00, 400.0, MarketDataUpdate.Side.BID);
        
        // Publish multiple updates for same symbol but different price levels. Replay the original
        // update after the newer ones to show the comparator rejecting the stale version.
        eventBus.publishEvent(level150Initial);
        eventBus.publishEvent(level150Replacement); // Same key - coalesced
        eventBus.publishEvent(level15005); // Different price
        eventBus.publishEvent(level150Latest); // Newest for price 150.00
        eventBus.publishEvent(level150Initial); // Older update arrives late but should be ignored
        
        // Flush to ensure delivery
        eventBus.flushCoalescedEvents();
        
        // Wait for events
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        
        // Should receive 2 events (one per price level)
        assertThat(receivedEvents).hasSize(2);
        
        // Verify we got the latest size for each price level
        MarketDataUpdate level1 = (MarketDataUpdate) receivedEvents.stream()
            .filter(e -> ((MarketDataUpdate)e).price == 150.00)
            .findFirst().orElse(null);
        MarketDataUpdate level2 = (MarketDataUpdate) receivedEvents.stream()
            .filter(e -> ((MarketDataUpdate)e).price == 150.05)
            .findFirst().orElse(null);
            
        assertThat(level1).isNotNull();
        assertThat(level1.size).isEqualTo(400.0); // Latest update for 150.00
        assertThat(level2).isNotNull();
        assertThat(level2.size).isEqualTo(300.0); // Only update for 150.05
    }
    
    @Test
    @DisplayName("Should deliver all updates with distinct composite keys")
    void shouldNotCoalesceEventsWithDifferentCompositeKeys() throws InterruptedException {
        latch = new CountDownLatch(4);
        
        // Add coalescing subscriber with composite key
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            update -> {
                receivedEvents.add(update);
                latch.countDown();
            }
        );
        
        // Publish updates with different composite keys
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 200.0, MarketDataUpdate.Side.ASK)); // Different side
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.05, 300.0, MarketDataUpdate.Side.BID)); // Different price
        eventBus.publishEvent(new MarketDataUpdate("GOOGL", 150.00, 400.0, MarketDataUpdate.Side.BID)); // Different symbol
        
        // Flush to ensure delivery
        eventBus.flushCoalescedEvents();
        
        // Wait for events
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        
        // Should receive all four updates (all have different composite keys)
        assertThat(receivedEvents).hasSize(4);
    }

    @Test
    @DisplayName("Should deliver subclass events via coalescing subscriber")
    void shouldDeliverSubclassEventsToCoalescingSubscriber() throws InterruptedException {
        List<ParentEvent> capturedEvents = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch localLatch = new CountDownLatch(1);

        eventBus.addCoalescingSubscriber(
            ParentEvent.class,
            ParentEvent::getId,
            event -> {
                capturedEvents.add(event);
                localLatch.countDown();
            }
        );

        eventBus.publishEvent(new ChildEvent("child-1"));

        eventBus.flushCoalescedEvents(ParentEvent.class);

        assertThat(localLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0)).isInstanceOf(ChildEvent.class);
    }

    @Test
    @DisplayName("Should respect global coalescing configuration for subclass events")
    void shouldRespectGlobalCoalescingForSubclassEvents() {
        eventBus.configureCoalescing(ParentEvent.class, ParentEvent::getId);

        List<ParentEvent> capturedEvents = Collections.synchronizedList(new ArrayList<>());
        eventBus.addSubscriber(ParentEvent.class, capturedEvents::add);

        eventBus.publishEvent(new ChildEvent("child-global"));

        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0)).isInstanceOf(ChildEvent.class);
        assertThat(eventBus.isCoalescingEnabled(ChildEvent.class)).isTrue();
    }
    
    @Test
    @DisplayName("Should apply subscriber filter before coalescing")
    void shouldApplyFilterBeforeCoalescing() throws InterruptedException {
        latch = new CountDownLatch(2);
        
        // Add coalescing subscriber with filter for large sizes only
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            Comparator.comparingLong(update -> update.timestamp),
            update -> update.size > 200.0,  // Filter for large sizes
            update -> {
                receivedEvents.add(update);
                latch.countDown();
            }
        );
        
        MarketDataUpdate small150 = new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID);  // Filtered out
        MarketDataUpdate large150First = new MarketDataUpdate("AAPL", 150.00, 300.0, MarketDataUpdate.Side.BID);  // Accepted
        MarketDataUpdate filtered150 = new MarketDataUpdate("AAPL", 150.00, 150.0, MarketDataUpdate.Side.BID);  // Filtered out
        MarketDataUpdate large150Latest = new MarketDataUpdate("AAPL", 150.00, 500.0, MarketDataUpdate.Side.BID);  // Accepted, replaces 300
        MarketDataUpdate large15005 = new MarketDataUpdate("AAPL", 150.05, 250.0, MarketDataUpdate.Side.BID);  // Accepted, different price
        
        // Publish updates with varying sizes at same and different price levels. Replay the earlier
        // large update to demonstrate the comparator refusing the stale version that arrives late.
        eventBus.publishEvent(small150);
        eventBus.publishEvent(large150First);
        eventBus.publishEvent(filtered150);
        eventBus.publishEvent(large150Latest);
        eventBus.publishEvent(large150First);  // Older large update arrives after the latest
        eventBus.publishEvent(large15005);
        
        // Flush to ensure delivery
        eventBus.flushCoalescedEvents();
        
        // Wait for events
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        
        // Should receive 2 events (latest for each price level that passed filter)
        assertThat(receivedEvents).hasSize(2);
        
        MarketDataUpdate level1 = (MarketDataUpdate) receivedEvents.stream()
            .filter(e -> ((MarketDataUpdate)e).price == 150.00)
            .findFirst().orElse(null);
        MarketDataUpdate level2 = (MarketDataUpdate) receivedEvents.stream()
            .filter(e -> ((MarketDataUpdate)e).price == 150.05)
            .findFirst().orElse(null);
            
        assertThat(level1).isNotNull();
        assertThat(level1.size).isEqualTo(500.0); // Latest large update for 150.00
        assertThat(level2).isNotNull();
        assertThat(level2.size).isEqualTo(250.0); // Only large update for 150.05
    }

    @Test
    @DisplayName("Should combine comparator and filter when coalescing")
    void shouldUseComparatorWithFilter() {
        List<OrderUpdate> delivered = Collections.synchronizedList(new ArrayList<>());

        eventBus.addCoalescingSubscriber(
            OrderUpdate.class,
            update -> update.orderId,
            Comparator.comparingInt(update -> update.version),
            update -> !"REJECTED".equals(update.status),
            delivered::add
        );

        eventBus.publishEvent(new OrderUpdate("ORD-2", "REJECTED", 10)); // filtered out
        eventBus.publishEvent(new OrderUpdate("ORD-2", "PARTIAL", 2));
        eventBus.publishEvent(new OrderUpdate("ORD-2", "FILLED", 7));
        eventBus.publishEvent(new OrderUpdate("ORD-2", "PARTIAL", 5));   // should not replace version 7

        eventBus.flushCoalescedEvents(OrderUpdate.class);

        assertThat(delivered).hasSize(1);
        OrderUpdate latest = delivered.get(0);
        assertThat(latest.status).isEqualTo("FILLED");
        assertThat(latest.version).isEqualTo(7);
    }
    
    @Test
    @DisplayName("Should handle Coalescable interface without explicit configuration")
    void shouldHandleCoalescableInterface() throws InterruptedException {
        latch = new CountDownLatch(1);
        
        // Regular subscriber for Coalescable events
        eventBus.addSubscriber(
            CoalescableMarketData.class,
            data -> {
                receivedEvents.add(data);
                latch.countDown();
            }
        );
        
        // Publish multiple updates
        eventBus.publishEvent(new CoalescableMarketData("AAPL", 150.00));
        eventBus.publishEvent(new CoalescableMarketData("AAPL", 150.10));
        
        // Wait for events
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        
        // Without coalescing subscriber, should receive first event immediately
        // (Note: full coalescing for regular subscribers would need more implementation)
        assertThat(receivedEvents).isNotEmpty();
    }
    
    @Test
    @DisplayName("Should enable and disable global coalescing statefully")
    void shouldSupportGlobalCoalescing() {
        // Configure global coalescing for MarketDataUpdate
        eventBus.configureCoalescing(
            MarketDataUpdate.class,
            (MarketDataUpdate update) -> update.symbol
        );
        
        // Check configuration
        assertThat(eventBus.isCoalescingEnabled(MarketDataUpdate.class)).isTrue();
        assertThat(eventBus.isCoalescingEnabled(OrderUpdate.class)).isFalse();
        
        // Disable coalescing
        eventBus.disableCoalescing(MarketDataUpdate.class);
        assertThat(eventBus.isCoalescingEnabled(MarketDataUpdate.class)).isFalse();
    }
    
    @Test
    @DisplayName("Should track pending coalesced events accurately")
    void shouldTrackPendingCoalescedEvents() {
        // Add coalescing subscriber with composite key
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            update -> {
                // Don't process immediately
            }
        );
        
        // Publish events at different price levels
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.05, 200.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 300.0, MarketDataUpdate.Side.BID)); // Replaces first
        eventBus.publishEvent(new MarketDataUpdate("GOOGL", 2800.00, 400.0, MarketDataUpdate.Side.ASK));
        
        // Check pending count - should be 3 unique composite keys
        assertThat(eventBus.getPendingCoalescedEventCount()).isEqualTo(3); // AAPL@150.00, AAPL@150.05, GOOGL@2800.00
        assertThat(eventBus.getPendingCoalescedEventCount(MarketDataUpdate.class)).isEqualTo(3);
        
        // Flush events
        eventBus.flushCoalescedEvents();
        
        // Should have no pending events after flush
        assertThat(eventBus.getPendingCoalescedEventCount()).isEqualTo(0);
    }
    
    @Test
    @DisplayName("Should flush coalesced events for a specific event class")
    void shouldFlushSpecificEventClass() {
        AtomicInteger marketDataCount = new AtomicInteger(0);
        AtomicInteger orderCount = new AtomicInteger(0);
        
        // Add coalescing subscribers for different event types with composite keys
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            update -> marketDataCount.incrementAndGet()
        );
        
        eventBus.addCoalescingSubscriber(
            OrderUpdate.class,
            order -> order.orderId,
            order -> orderCount.incrementAndGet()
        );
        
        // Publish events
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.05, 200.0, MarketDataUpdate.Side.BID)); // Different price
        eventBus.publishEvent(new OrderUpdate("ORD001", "NEW", 1));
        
        // Flush only MarketDataUpdate events
        eventBus.flushCoalescedEvents(MarketDataUpdate.class);
        
        // Both market data events should be delivered (different composite keys)
        assertThat(marketDataCount.get()).isEqualTo(2);
        assertThat(orderCount.get()).isEqualTo(0);
        
        // Flush remaining
        eventBus.flushCoalescedEvents(OrderUpdate.class);
        assertThat(orderCount.get()).isEqualTo(1);
    }
    
    @Test
    @DisplayName("Should stop delivering coalesced events after unsubscribe")
    void shouldHandleUnsubscribe() {
        AtomicInteger eventCount = new AtomicInteger(0);
        
        // Add coalescing subscriber with composite key
        Subscription subscription = eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            update -> eventCount.incrementAndGet()
        );
        
        // Publish events
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.05, 200.0, MarketDataUpdate.Side.BID));
        eventBus.flushCoalescedEvents();
        assertThat(eventCount.get()).isEqualTo(2); // Two different price levels
        
        // Unsubscribe
        subscription.unsubscribe();
        assertThat(subscription.isActive()).isFalse();
        
        // Publish more events
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 300.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.10, 400.0, MarketDataUpdate.Side.BID));
        eventBus.flushCoalescedEvents();
        
        // Should not receive events after unsubscribe
        assertThat(eventCount.get()).isEqualTo(2);
    }
    
    @Test
    @Timeout(5)
    @DisplayName("Should coalesce correctly under concurrent publishing load")
    void shouldHandleConcurrentPublishing() throws InterruptedException {
        int threadCount = 10;
        int pricesPerSymbol = 5; // Each thread publishes to 5 different price levels
        int updatesPerPrice = 20; // Multiple updates per price level
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger receivedCount = new AtomicInteger(0);
        
        // Add coalescing subscriber with composite key
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            update -> receivedCount.incrementAndGet()
        );
        
        // Create threads that publish events
        for (int i = 0; i < threadCount; i++) {
            final String symbol = "SYM" + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int price = 0; price < pricesPerSymbol; price++) {
                        double priceLevel = 100.0 + (price * 0.05); // Price levels: 100.00, 100.05, 100.10, etc.
                        for (int update = 0; update < updatesPerPrice; update++) {
                            double size = (update + 1) * 100.0;
                            eventBus.publishEvent(new MarketDataUpdate(symbol, priceLevel, size, MarketDataUpdate.Side.BID));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }
        
        // Start all threads simultaneously
        startLatch.countDown();
        
        // Wait for all threads to complete
        assertThat(endLatch.await(3, TimeUnit.SECONDS)).isTrue();
        
        // Flush events
        eventBus.flushCoalescedEvents();
        
        // Should receive one event per unique composite key (symbol + price + side)
        // = threadCount * pricesPerSymbol
        assertThat(receivedCount.get()).isEqualTo(threadCount * pricesPerSymbol);
    }
    
    @Test
    @DisplayName("Should expose detailed coalescing metrics")
    void shouldProvideDetailedMetrics() {
        // Add coalescing subscriber
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> update.symbol,
            update -> { /* no-op */ }
        );
        
        // Publish events
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 151.00, 110.0, MarketDataUpdate.Side.BID)); // Replaces previous
        eventBus.publishEvent(new MarketDataUpdate("GOOGL", 2800.00, 200.0, MarketDataUpdate.Side.ASK));
        
        // Get metrics
        Map<String, Object> metrics = eventBus.getDetailedMetrics();
        
        // Verify metrics
        assertThat(metrics).containsKey("totalCoalescedEvents");
        assertThat(metrics).containsKey("totalReplacedEvents");
        assertThat(metrics).containsKey("pendingCoalescedEvents");
        assertThat(metrics).containsKey("coalescingSubscriberCount");

        assertThat(metrics.get("coalescingSubscriberCount")).isEqualTo(1);
        assertThat((int) metrics.get("pendingCoalescedEvents")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Should isolate exceptions thrown by coalescing subscribers")
    void shouldIsolateExceptionsFromCoalescingSubscribers() throws InterruptedException {
        latch = new CountDownLatch(1);

        // First subscriber throws, second should still receive the event
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            update -> { throw new IllegalStateException("boom"); }
        );

        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> new PriceLevelKey(update.symbol, update.price, update.side),
            update -> {
                receivedEvents.add(update);
                latch.countDown();
            }
        );

        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
        eventBus.flushCoalescedEvents();

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedEvents).hasSize(1);
    }

    @Test
    @DisplayName("Should throw when publishing a null event")
    void shouldThrowWhenPublishingNullEvent() {
        assertThatNullPointerException()
            .isThrownBy(() -> eventBus.publishEvent(null))
            .withMessage("Event cannot be null");
    }

    @Test
    @DisplayName("Should bypass coalescing when key extractor returns null")
    void shouldHandleNullKeyGracefully() throws InterruptedException {
        latch = new CountDownLatch(3);  // 3 events expected
        CountDownLatch immediateDelivery = new CountDownLatch(1);

        // Add coalescing subscriber that returns null for some events
        eventBus.addCoalescingSubscriber(
            MarketDataUpdate.class,
            update -> update.symbol.equals("SPECIAL") ? null : 
                     new PriceLevelKey(update.symbol, update.price, update.side),
            update -> {
                receivedEvents.add(update);
                if ("SPECIAL".equals(update.symbol)) {
                    immediateDelivery.countDown();
                }
                latch.countDown();
            }
        );

        // Publish events
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
        eventBus.publishEvent(new MarketDataUpdate("SPECIAL", 999.00, 200.0, MarketDataUpdate.Side.BID)); // Null key - delivered immediately

        assertThat(immediateDelivery.await(100, TimeUnit.MILLISECONDS)).isTrue();

        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 300.0, MarketDataUpdate.Side.BID)); // Coalesced with first AAPL@150.00
        eventBus.publishEvent(new MarketDataUpdate("AAPL", 150.05, 400.0, MarketDataUpdate.Side.BID)); // Different price level

        // Flush
        eventBus.flushCoalescedEvents();

        // Wait for events
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        
        // Should receive SPECIAL immediately and two AAPL events (different price levels)
        assertThat(receivedEvents).hasSize(3);
        assertThat(receivedEvents.stream()
            .filter(event -> event instanceof MarketDataUpdate)
            .map(event -> (MarketDataUpdate) event)
            .anyMatch(update -> "SPECIAL".equals(update.symbol))).isTrue();
    }
    @Test
    @DisplayName("Should coalesce using composite keys without cross-level replacement")
    void shouldCoalesceWithCompositeKeys() throws InterruptedException {
        // Test that events with composite keys are coalesced correctly
        // Different components of the key should NOT coalesce together
        class CompositeKeyEvent {
            final String symbol;
            final double price;
            final double size;
            
            CompositeKeyEvent(String symbol, double price, double size) {
                this.symbol = symbol;
                this.price = price;
                this.size = size;
            }
            
            @Override
            public String toString() {
                return String.format("%s:%.2f:%.2f", symbol, price, size);
            }
        }
        
        class CompositeKey {
            final String symbol;
            final double price;
            
            CompositeKey(String symbol, double price) {
                this.symbol = symbol;
                this.price = price;
            }
            
            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                CompositeKey that = (CompositeKey) o;
                return Double.compare(that.price, price) == 0 && 
                       Objects.equals(symbol, that.symbol);
            }
            
            @Override
            public int hashCode() {
                return Objects.hash(symbol, price);
            }
        }
        
        Map<CompositeKey, CompositeKeyEvent> receivedByKey = new ConcurrentHashMap<>();
        latch = new CountDownLatch(3); // Expect 3 different price levels
        
        // Add coalescing subscriber with composite key
        eventBus.addCoalescingSubscriber(
            CompositeKeyEvent.class,
            event -> new CompositeKey(event.symbol, event.price),
            event -> {
                receivedByKey.put(new CompositeKey(event.symbol, event.price), event);
                latch.countDown();
            }
        );
        
        // Publish updates at different price levels - they should NOT override each other
        eventBus.publishEvent(new CompositeKeyEvent("AAPL", 150.00, 100.0));
        eventBus.publishEvent(new CompositeKeyEvent("AAPL", 150.00, 200.0)); // Same price - coalesced
        eventBus.publishEvent(new CompositeKeyEvent("AAPL", 150.05, 300.0)); // Different price - NOT coalesced
        eventBus.publishEvent(new CompositeKeyEvent("AAPL", 150.10, 400.0)); // Different price - NOT coalesced
        eventBus.publishEvent(new CompositeKeyEvent("AAPL", 150.05, 500.0)); // Update existing level
        
        // Flush events
        eventBus.flushCoalescedEvents();
        
        // Wait for events
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        
        // Should have 3 different price levels
        assertThat(receivedByKey).hasSize(3);
        
        // Verify the latest size for each price level
        assertThat(receivedByKey.get(new CompositeKey("AAPL", 150.00)).size).isEqualTo(200.0);
        assertThat(receivedByKey.get(new CompositeKey("AAPL", 150.05)).size).isEqualTo(500.0);
        assertThat(receivedByKey.get(new CompositeKey("AAPL", 150.10)).size).isEqualTo(400.0);
    }
    
    @Test
    @DisplayName("Should cleanup resources when closed with external executor")
    void shouldCleanupResourcesOnClose() throws Exception {
        // Create event bus with async delivery
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        try (CoalescingThreadSafeEventBus asyncBus = 
                new CoalescingThreadSafeEventBus(false, executor)) {
            
            // Add subscriber
            asyncBus.addCoalescingSubscriber(
                MarketDataUpdate.class,
                update -> new PriceLevelKey(update.symbol, update.price, update.side),
                update -> receivedEvents.add(update)
            );
            
            // Publish events
            asyncBus.publishEvent(new MarketDataUpdate("AAPL", 150.00, 100.0, MarketDataUpdate.Side.BID));
            
            // Close should flush pending events
        } // Auto-close
        
        // Executor should be shutdown
        assertThat(executor.isShutdown()).isTrue();
    }
}
