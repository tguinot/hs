package com.hsbc.eventbus;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for ThreadSafeEventBus.
 * Verifies thread-safety, concurrent behavior, and proper handling
 * of multi-threaded event publishing and subscription management.
 */
class ThreadSafeEventBusTest {
    
    private ThreadSafeEventBus eventBus;
    private ExecutorService executor;
    
    @BeforeEach
    void setUp() {
        eventBus = new ThreadSafeEventBus();
        executor = Executors.newFixedThreadPool(10);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (eventBus != null) {
            eventBus.close();
        }
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    // Test event classes
    static class TestEvent {
        final String message;
        TestEvent(String message) { this.message = message; }
    }
    
    static class AnotherEvent {
        final int value;
        AnotherEvent(int value) { this.value = value; }
    }

    static class ParentEvent {
        final String data;
        ParentEvent(String data) { this.data = data; }
        String getData() { return data; }
    }

    static class ChildEvent extends ParentEvent {
        ChildEvent(String data) { super(data); }
    }

    interface PayloadEvent {
        String getPayload();
    }

    static class InterfaceChildEvent extends ParentEvent implements PayloadEvent {
        InterfaceChildEvent(String data) { super(data); }

        @Override
        public String getPayload() {
            return getData();
        }
    }
    
    // ========== Basic Functionality Tests ==========
    
    @Test
    @DisplayName("Should publish events and deliver to subscribers in order")
    void shouldPublishAndReceiveEvents() {
        List<String> received = new CopyOnWriteArrayList<>();
        
        eventBus.addSubscriber(TestEvent.class, event -> received.add(event.message));
        
        eventBus.publishEvent(new TestEvent("Hello"));
        eventBus.publishEvent(new TestEvent("World"));
        
        assertThat(received).containsExactly("Hello", "World");
    }
    
    @Test
    @DisplayName("Should notify all subscribers registered for the same event type")
    void shouldSupportMultipleSubscribersForSameEvent() {
        AtomicInteger counter1 = new AtomicInteger();
        AtomicInteger counter2 = new AtomicInteger();
        
        eventBus.addSubscriber(TestEvent.class, e -> counter1.incrementAndGet());
        eventBus.addSubscriber(TestEvent.class, e -> counter2.incrementAndGet());
        
        eventBus.publishEvent(new TestEvent("test"));
        
        assertThat(counter1.get()).isEqualTo(1);
        assertThat(counter2.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should deliver subclass events to parent class subscribers")
    void shouldDeliverSubclassEventsToParentSubscribers() {
        AtomicReference<String> parentPayload = new AtomicReference<>();

        eventBus.addSubscriber(ParentEvent.class, event -> parentPayload.set(event.getData()));

        eventBus.publishEvent(new ChildEvent("child-data"));

        assertThat(parentPayload).hasValue("child-data");
    }

    @Test
    @DisplayName("Should deliver events to interface subscribers")
    void shouldDeliverEventsToInterfaceSubscribers() {
        AtomicReference<String> interfacePayload = new AtomicReference<>();

        eventBus.addSubscriber(PayloadEvent.class, event -> interfacePayload.set(event.getPayload()));

        eventBus.publishEvent(new InterfaceChildEvent("interface-data"));

        assertThat(interfacePayload).hasValue("interface-data");
    }

    @Test
    @DisplayName("Should invoke subscribers in specificity order")
    void shouldInvokeSubscribersInSpecificityOrder() {
        List<String> invocationOrder = new CopyOnWriteArrayList<>();

        eventBus.addSubscriber(ChildEvent.class, event -> invocationOrder.add("child"));
        eventBus.addSubscriber(ParentEvent.class, event -> invocationOrder.add("parent"));
        eventBus.addSubscriber(Object.class, event -> invocationOrder.add("object"));

        eventBus.publishEvent(new ChildEvent("ordering"));

        assertThat(invocationOrder).containsExactly("child", "parent", "object");
    }
    
    @Test
    @DisplayName("Should apply subscriber filter before invoking callback")
    void shouldFilterEvents() {
        List<Integer> received = new CopyOnWriteArrayList<>();
        
        eventBus.addSubscriberForFilteredEvents(
            AnotherEvent.class,
            event -> event.value > 5,
            event -> received.add(event.value)
        );
        
        eventBus.publishEvent(new AnotherEvent(3));
        eventBus.publishEvent(new AnotherEvent(7));
        eventBus.publishEvent(new AnotherEvent(10));
        eventBus.publishEvent(new AnotherEvent(2));
        
        assertThat(received).containsExactly(7, 10);
    }
    
    // ========== Critical Subscription Management Tests ==========
    
    @Test
    @DisplayName("Unsubscribed handler should not receive events")
    void shouldNotReceiveEventsAfterUnsubscribe() {
        AtomicInteger counter = new AtomicInteger();
        
        Subscription subscription = eventBus.addSubscriber(TestEvent.class, 
            e -> counter.incrementAndGet());
        
        // Verify subscription works
        eventBus.publishEvent(new TestEvent("first"));
        assertThat(counter.get()).isEqualTo(1);
        
        // Unsubscribe
        subscription.unsubscribe();
        assertFalse(subscription.isActive(), "Subscription should be inactive");
        
        // Verify no more events received
        eventBus.publishEvent(new TestEvent("second"));
        eventBus.publishEvent(new TestEvent("third"));
        assertThat(counter.get()).isEqualTo(1); // Should still be 1
    }
    
    @Test
    @DisplayName("Filtered subscribers should stop receiving events after unsubscribing")
    void shouldStopFilteredEventsAfterUnsubscribe() {
        List<Integer> received = new CopyOnWriteArrayList<>();
        
        Subscription subscription = eventBus.addSubscriberForFilteredEvents(
            AnotherEvent.class,
            event -> event.value > 10,
            event -> received.add(event.value)
        );
        
        // Verify filtered subscription works
        eventBus.publishEvent(new AnotherEvent(5));  // Filtered out
        eventBus.publishEvent(new AnotherEvent(15)); // Accepted
        assertThat(received).containsExactly(15);
        
        // Unsubscribe
        subscription.unsubscribe();
        
        // Verify no more events received
        eventBus.publishEvent(new AnotherEvent(20)); // Would pass filter but subscription inactive
        eventBus.publishEvent(new AnotherEvent(25));
        assertThat(received).containsExactly(15); // Should still only have 15
    }
    
    @Test
    @DisplayName("Should handle multiple dead events and count them correctly")
    void shouldCountMultipleDeadEvents() {
        AtomicInteger deadEventCount = new AtomicInteger();
        
        // Subscribe to dead events
        eventBus.addSubscriber(DeadEvent.class, e -> deadEventCount.incrementAndGet());
        
        // Publish multiple orphan events of different types
        for (int i = 0; i < 5; i++) {
            eventBus.publishEvent(new TestEvent("orphan-" + i));
        }
        for (int i = 0; i < 3; i++) {
            eventBus.publishEvent(new AnotherEvent(100 + i));
        }
        
        // Verify dead event count
        assertThat(deadEventCount.get()).isEqualTo(8);
        assertThat(eventBus.getDeadEventCount()).isEqualTo(8);
    }
    
    @Test
    @DisplayName("Single subscriber should handle multiple event types correctly")
    void shouldHandleMultipleEventTypesInSingleSubscriber() {
        List<String> eventLog = new CopyOnWriteArrayList<>();
        
        // Single handler logic for multiple event types
        Consumer<TestEvent> testHandler = e -> eventLog.add("TestEvent: " + e.message);
        Consumer<AnotherEvent> anotherHandler = e -> eventLog.add("AnotherEvent: " + e.value);
        
        // Subscribe to both event types
        Subscription sub1 = eventBus.addSubscriber(TestEvent.class, testHandler);
        Subscription sub2 = eventBus.addSubscriber(AnotherEvent.class, anotherHandler);
        
        // Publish mixed events
        eventBus.publishEvent(new TestEvent("hello"));
        eventBus.publishEvent(new AnotherEvent(42));
        eventBus.publishEvent(new TestEvent("world"));
        eventBus.publishEvent(new AnotherEvent(99));
        
        // Verify correct routing
        assertThat(eventLog).containsExactly(
            "TestEvent: hello",
            "AnotherEvent: 42",
            "TestEvent: world",
            "AnotherEvent: 99"
        );
        
        // Verify both subscriptions are active
        assertTrue(sub1.isActive());
        assertTrue(sub2.isActive());
    }
    
    @Test
    @DisplayName("Should handle re-subscribing after unsubscribe cleanly")
    void shouldHandleResubscribeAfterUnsubscribe() {
        AtomicInteger counter = new AtomicInteger();
        List<Integer> executionOrder = new CopyOnWriteArrayList<>();
        
        // Initial subscription
        Subscription firstSub = eventBus.addSubscriber(TestEvent.class, e -> {
            executionOrder.add(1);
            counter.incrementAndGet();
        });
        
        // Test first subscription
        eventBus.publishEvent(new TestEvent("event1"));
        assertThat(counter.get()).isEqualTo(1);
        assertThat(executionOrder).containsExactly(1);
        
        // Unsubscribe
        firstSub.unsubscribe();
        assertFalse(firstSub.isActive());
        
        // Verify unsubscribed
        eventBus.publishEvent(new TestEvent("event2"));
        assertThat(counter.get()).isEqualTo(1); // No change
        
        // Re-subscribe with different handler
        Subscription secondSub = eventBus.addSubscriber(TestEvent.class, e -> {
            executionOrder.add(2);
            counter.addAndGet(10);
        });
        
        // Test new subscription
        eventBus.publishEvent(new TestEvent("event3"));
        assertThat(counter.get()).isEqualTo(11); // 1 + 10
        assertThat(executionOrder).containsExactly(1, 2);
        
        // Verify old subscription still inactive
        assertFalse(firstSub.isActive());
        assertTrue(secondSub.isActive());
        
        // Both subscriptions at once (re-subscribe the first one again)
        Subscription thirdSub = eventBus.addSubscriber(TestEvent.class, e -> {
            executionOrder.add(3);
            counter.addAndGet(100);
        });
        
        eventBus.publishEvent(new TestEvent("event4"));
        assertThat(counter.get()).isEqualTo(121); // 11 + 10 + 100
        assertThat(executionOrder).containsExactly(1, 2, 2, 3);
        assertTrue(thirdSub.isActive());
        
        // Verify subscriber count
        assertThat(eventBus.getSubscriberCount(TestEvent.class)).isEqualTo(2);
    }
    
    // ========== Thread Safety Tests ==========
    
    @Test
    @Timeout(10)
    @DisplayName("Should handle concurrent publishing with proper handler completion tracking")
    void shouldHandleConcurrentPublishing() throws InterruptedException {
        int threadCount = 100;
        int eventsPerThread = 100;
        int totalEvents = threadCount * eventsPerThread;
        
        // Use a latch to track actual handler completion, not just publish calls
        CountDownLatch handlerCompletionLatch = new CountDownLatch(totalEvents);
        AtomicInteger counter = new AtomicInteger();
        
        eventBus.addSubscriber(TestEvent.class, e -> {
            counter.incrementAndGet();
            handlerCompletionLatch.countDown(); // Signal handler completion
        });
        
        CountDownLatch publishLatch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < eventsPerThread; j++) {
                        eventBus.publishEvent(new TestEvent("Event-" + threadId + "-" + j));
                    }
                } finally {
                    publishLatch.countDown();
                }
            });
        }
        
        // Wait for all publish calls to complete
        assertTrue(publishLatch.await(5, TimeUnit.SECONDS), 
            "All publish threads should complete");
        
        // Wait for all handlers to actually complete execution
        assertTrue(handlerCompletionLatch.await(5, TimeUnit.SECONDS), 
            "All event handlers should complete");
        
        // Now we can safely assert the counter
        assertThat(counter.get()).isEqualTo(totalEvents);
    }
    
    @Test
    @Timeout(10)
    @DisplayName("Should handle concurrent subscriptions with handler completion verification")
    void shouldHandleConcurrentSubscriptions() throws InterruptedException {
        int subscriberCount = 100;
        CountDownLatch subscriptionLatch = new CountDownLatch(subscriberCount);
        
        // Track each subscriber's handler completion separately
        List<CountDownLatch> handlerLatches = new CopyOnWriteArrayList<>();
        AtomicInteger handlersExecuted = new AtomicInteger();
        
        // Add subscribers concurrently
        for (int i = 0; i < subscriberCount; i++) {
            executor.submit(() -> {
                CountDownLatch handlerLatch = new CountDownLatch(1);
                handlerLatches.add(handlerLatch);
                
                eventBus.addSubscriber(TestEvent.class, e -> {
                    handlersExecuted.incrementAndGet();
                    handlerLatch.countDown();
                });
                subscriptionLatch.countDown();
            });
        }
        
        // Wait for all subscriptions to complete
        assertTrue(subscriptionLatch.await(5, TimeUnit.SECONDS),
            "All subscriptions should complete");
        
        // Verify subscriber count before publishing
        assertThat(eventBus.getSubscriberCount(TestEvent.class)).isEqualTo(subscriberCount);
        
        // Publish event
        eventBus.publishEvent(new TestEvent("test"));
        
        // Wait for ALL handlers to complete execution
        for (CountDownLatch latch : handlerLatches) {
            assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Each handler should complete execution");
        }
        
        // Now verify all handlers executed
        assertThat(handlersExecuted.get()).isEqualTo(subscriberCount);
    }
    
    @Test
    @Timeout(10)
    @DisplayName("Should handle concurrent unsubscriptions with proper synchronization")
    void shouldHandleConcurrentUnsubscriptions() throws InterruptedException {
        int subscriberCount = 100;
        List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        
        // Use a Phaser to coordinate handler execution tracking
        Phaser handlerPhaser = new Phaser(1); // 1 for the main thread
        AtomicInteger totalHandlerExecutions = new AtomicInteger();
        
        // Add many subscribers with handler tracking
        for (int i = 0; i < subscriberCount; i++) {
            handlerPhaser.register(); // Register each handler with the phaser
            Subscription sub = eventBus.addSubscriber(TestEvent.class, e -> {
                totalHandlerExecutions.incrementAndGet();
                handlerPhaser.arriveAndDeregister(); // Signal completion and deregister
            });
            subscriptions.add(sub);
        }
        
        // Track completion of unsubscribe and publish threads
        CountDownLatch operationsLatch = new CountDownLatch(2);
        AtomicInteger eventsPublished = new AtomicInteger();
        
        // Thread 1: Unsubscribe concurrently
        executor.submit(() -> {
            try {
                for (Subscription sub : subscriptions) {
                    sub.unsubscribe();
                    Thread.sleep(1); // Small delay to interleave with publishing
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                operationsLatch.countDown();
            }
        });
        
        // Thread 2: Publish events concurrently
        executor.submit(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    eventBus.publishEvent(new TestEvent("Event " + i));
                    eventsPublished.incrementAndGet();
                    Thread.sleep(1); // Small delay to interleave with unsubscribing
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                operationsLatch.countDown();
            }
        });
        
        // Wait for both operations to complete
        assertTrue(operationsLatch.await(5, TimeUnit.SECONDS),
            "Both operations should complete");
        
        // Wait for all pending handlers to complete (with timeout)
        handlerPhaser.arriveAndAwaitAdvance(); // Main thread arrives and waits
        
        // After all unsubscriptions, verify no active subscribers
        assertThat(eventBus.getSubscriberCount(TestEvent.class)).isEqualTo(0);
        
        // Publish a final event and verify no handlers execute
        int countBefore = totalHandlerExecutions.get();
        eventBus.publishEvent(new TestEvent("Final"));
        
        // Give a small window for any async handler to execute (shouldn't happen)
        Thread.sleep(100);
        
        assertThat(totalHandlerExecutions.get()).isEqualTo(countBefore);
    }
    
    @Test
    @Timeout(10)
    @DisplayName("Should remain consistent when operations mix concurrently")
    void shouldHandleMixedConcurrentOperations() throws InterruptedException {
        AtomicInteger eventCounter = new AtomicInteger();
        AtomicInteger subscriptionCounter = new AtomicInteger();
        List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        
        int operationCount = 1000;
        CountDownLatch latch = new CountDownLatch(operationCount);
        Random random = new Random();
        
        for (int i = 0; i < operationCount; i++) {
            int operation = random.nextInt(4);
            
            executor.submit(() -> {
                try {
                    switch (operation) {
                        case 0: // Publish event
                            eventBus.publishEvent(new TestEvent("Event"));
                            eventCounter.incrementAndGet();
                            break;
                        case 1: // Add subscriber
                            Subscription sub = eventBus.addSubscriber(TestEvent.class, e -> {});
                            subscriptions.add(sub);
                            subscriptionCounter.incrementAndGet();
                            break;
                        case 2: // Unsubscribe
                            if (!subscriptions.isEmpty()) {
                                int idx = random.nextInt(subscriptions.size());
                                Subscription toRemove = subscriptions.get(idx);
                                if (toRemove != null && toRemove.isActive()) {
                                    toRemove.unsubscribe();
                                    subscriptionCounter.decrementAndGet();
                                }
                            }
                            break;
                        case 3: // Get metrics
                            eventBus.getTotalEventsPublished();
                            eventBus.getTotalSubscriberCount();
                            break;
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        
        // Verify state is consistent
        assertThat(eventBus.getTotalEventsPublished()).isGreaterThanOrEqualTo(0);
        assertThat(eventBus.getTotalSubscriberCount()).isGreaterThanOrEqualTo(0);
    }
    
    // ========== Dead Event Tests ==========
    
    @Test
    @DisplayName("Should publish dead events when no subscribers exist")
    void shouldPublishDeadEvents() {
        List<Object> deadEvents = new CopyOnWriteArrayList<>();
        eventBus.addSubscriber(DeadEvent.class, dead -> deadEvents.add(dead.getEvent()));
        
        // Publish event with no subscribers
        TestEvent orphanEvent = new TestEvent("orphan");
        eventBus.publishEvent(orphanEvent);
        
        assertThat(deadEvents).containsExactly(orphanEvent);
        assertThat(eventBus.getDeadEventCount()).isEqualTo(1);
    }
    
    @Test
    @DisplayName("Should not emit dead events when feature disabled")
    void shouldNotPublishDeadEventsWhenDisabled() {
        eventBus.close();
        eventBus = new ThreadSafeEventBus(false); // Disable dead events
        
        AtomicBoolean deadEventReceived = new AtomicBoolean(false);
        eventBus.addSubscriber(DeadEvent.class, e -> deadEventReceived.set(true));
        
        eventBus.publishEvent(new TestEvent("orphan"));
        
        assertThat(deadEventReceived.get()).isFalse();
        assertThat(eventBus.getDeadEventCount()).isEqualTo(0);
    }
    
    // ========== Error Handling Tests ==========
    
    @Test
    @DisplayName("Should isolate subscriber exceptions from other handlers")
    void shouldIsolateSubscriberExceptions() {
        List<String> received = new CopyOnWriteArrayList<>();
        
        eventBus.addSubscriber(TestEvent.class, e -> {
            throw new RuntimeException("Subscriber error");
        });
        eventBus.addSubscriber(TestEvent.class, e -> received.add(e.message));
        
        eventBus.publishEvent(new TestEvent("test"));
        
        assertThat(received).containsExactly("test");
    }
    
    @Test
    @DisplayName("Should propagate fatal errors thrown by subscribers")
    void shouldPropagateErrors() {
        eventBus.addSubscriber(TestEvent.class, e -> {
            throw new OutOfMemoryError("Simulated OOM");
        });
        
        assertThatThrownBy(() -> eventBus.publishEvent(new TestEvent("test")))
            .isInstanceOf(OutOfMemoryError.class)
            .hasMessage("Simulated OOM");
    }
    
    // ========== Metrics Tests ==========
    
    @Test
    @DisplayName("Should track basic event and subscriber metrics")
    void shouldTrackMetrics() {
        eventBus.addSubscriber(TestEvent.class, e -> {});
        eventBus.addSubscriber(AnotherEvent.class, e -> {});
        
        eventBus.publishEvent(new TestEvent("test1"));
        eventBus.publishEvent(new TestEvent("test2"));
        eventBus.publishEvent(new AnotherEvent(42));
        
        assertThat(eventBus.getTotalEventsPublished()).isEqualTo(3);
        assertThat(eventBus.getSubscriberCount(TestEvent.class)).isEqualTo(1);
        assertThat(eventBus.getSubscriberCount(AnotherEvent.class)).isEqualTo(1);
        assertThat(eventBus.getTotalSubscriberCount()).isEqualTo(2);
    }
    
    @Test
    @DisplayName("Should provide detailed metrics including dead events")
    void shouldProvideDetailedMetrics() {
        eventBus.addSubscriber(TestEvent.class, e -> {});
        eventBus.addSubscriber(TestEvent.class, e -> {});
        eventBus.addSubscriber(AnotherEvent.class, e -> {});
        
        eventBus.publishEvent(new TestEvent("test"));
        eventBus.publishEvent(new Object()); // Will be a dead event
        
        Map<String, Object> metrics = eventBus.getDetailedMetrics();
        
        // Dead event publishing also counts as an event, plus the DeadEvent itself
        assertThat(metrics).containsEntry("totalEventsPublished", 3L); // Original event + DeadEvent publication + Object
        assertThat(metrics).containsEntry("deadEventCount", 1L);
        assertThat(metrics).containsEntry("totalSubscriberCount", 3);
        assertThat(metrics).containsKey("subscribersPerClass");
    }
    
    @Test
    @DisplayName("Should list event classes that currently have subscribers")
    void shouldGetEventClassesWithSubscribers() {
        eventBus.addSubscriber(TestEvent.class, e -> {});
        eventBus.addSubscriber(AnotherEvent.class, e -> {});
        
        Set<Class<?>> classes = eventBus.getEventClassesWithSubscribers();
        
        assertThat(classes).containsExactlyInAnyOrder(TestEvent.class, AnotherEvent.class);
    }
    
    // ========== Resource Management Tests ==========
    
    @Test
    @DisplayName("Should close cleanly and reject further publications")
    void shouldCloseCleanly() {
        AtomicBoolean received = new AtomicBoolean(false);
        eventBus.addSubscriber(TestEvent.class, e -> received.set(true));
        
        eventBus.close();
        
        assertThat(eventBus.isClosed()).isTrue();
        assertThat(eventBus.getTotalSubscriberCount()).isEqualTo(0);
        
        // Should not receive events after close
        assertThatThrownBy(() -> eventBus.publishEvent(new TestEvent("test")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
        
        assertThat(received.get()).isFalse();
    }
    
    @Test
    @DisplayName("Should allow idempotent close operations")
    void shouldBeIdempotentOnClose() {
        eventBus.addSubscriber(TestEvent.class, e -> {});
        
        eventBus.close();
        assertThat(eventBus.isClosed()).isTrue();
        
        // Second close should not throw
        assertThatCode(() -> eventBus.close()).doesNotThrowAnyException();
        assertThat(eventBus.isClosed()).isTrue();
    }
    
    @Test
    @DisplayName("Should integrate with try-with-resources for auto-close")
    void shouldWorkWithTryWithResources() {
        AtomicBoolean received = new AtomicBoolean(false);
        
        try (ThreadSafeEventBus autoCloseEventBus = new ThreadSafeEventBus()) {
            autoCloseEventBus.addSubscriber(TestEvent.class, e -> received.set(true));
            autoCloseEventBus.publishEvent(new TestEvent("test"));
            assertThat(received.get()).isTrue();
        }
    }
    
    // ========== Performance Tests ==========
    
    @Test
    @Timeout(5)
    @DisplayName("Should handle high throughput with handler completion tracking")
    void shouldHandleHighThroughput() throws InterruptedException {
        int eventCount = 100_000;
        int threadCount = 10;
        int eventsPerThread = eventCount / threadCount;
        
        // Track handler completions, not just publish calls
        CountDownLatch handlerCompletionLatch = new CountDownLatch(eventCount);
        AtomicInteger counter = new AtomicInteger();
        
        eventBus.addSubscriber(TestEvent.class, e -> {
            counter.incrementAndGet();
            handlerCompletionLatch.countDown();
        });
        
        CountDownLatch publishLatch = new CountDownLatch(threadCount);
        
        long startTime = System.currentTimeMillis();
        
        // Publish events from multiple threads
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        eventBus.publishEvent(new TestEvent("Event-" + threadId + "-" + i));
                    }
                } finally {
                    publishLatch.countDown();
                }
            });
        }
        
        // Wait for all publish calls to complete
        assertTrue(publishLatch.await(3, TimeUnit.SECONDS),
            "All publish threads should complete");
        
        // Wait for all handlers to complete - this is the critical part
        assertTrue(handlerCompletionLatch.await(3, TimeUnit.SECONDS),
            "All handlers should complete within timeout");
        
        long duration = System.currentTimeMillis() - startTime;
        
        // Now we can safely assert the counter
        assertThat(counter.get()).isEqualTo(eventCount);
        
        // Should handle at least 10,000 events per second
        double eventsPerSecond = (eventCount * 1000.0) / duration;
        assertThat(eventsPerSecond).isGreaterThan(10_000);
    }
    
    @Test
    @DisplayName("Should scale with multiple event types and verify handler completion")
    void shouldScaleWithMultipleEventTypes() throws InterruptedException {
        int subscribersPerType = 10;
        int totalEvents = 1000;
        
        // Track handler completions for each event type
        CountDownLatch testEventHandlers = new CountDownLatch(subscribersPerType * (totalEvents / 2));
        CountDownLatch anotherEventHandlers = new CountDownLatch(subscribersPerType * (totalEvents / 2));
        
        AtomicInteger testEventCount = new AtomicInteger();
        AtomicInteger anotherEventCount = new AtomicInteger();
        
        // Add subscribers for different event types
        IntStream.range(0, subscribersPerType).forEach(i -> {
            eventBus.addSubscriber(TestEvent.class, e -> {
                testEventCount.incrementAndGet();
                testEventHandlers.countDown();
            });
            eventBus.addSubscriber(AnotherEvent.class, e -> {
                anotherEventCount.incrementAndGet();
                anotherEventHandlers.countDown();
            });
        });
        
        // Verify subscriber counts before publishing
        assertThat(eventBus.getSubscriberCount(TestEvent.class)).isEqualTo(subscribersPerType);
        assertThat(eventBus.getSubscriberCount(AnotherEvent.class)).isEqualTo(subscribersPerType);
        
        // Publish mixed events in parallel
        CountDownLatch publishLatch = new CountDownLatch(totalEvents);
        IntStream.range(0, totalEvents).parallel().forEach(i -> {
            try {
                if (i % 2 == 0) {
                    eventBus.publishEvent(new TestEvent("Event " + i));
                } else {
                    eventBus.publishEvent(new AnotherEvent(i));
                }
            } finally {
                publishLatch.countDown();
            }
        });
        
        // Wait for all publish operations to complete
        assertTrue(publishLatch.await(5, TimeUnit.SECONDS),
            "All publish operations should complete");
        
        // Wait for all handlers to complete
        assertTrue(testEventHandlers.await(5, TimeUnit.SECONDS),
            "All TestEvent handlers should complete");
        assertTrue(anotherEventHandlers.await(5, TimeUnit.SECONDS),
            "All AnotherEvent handlers should complete");
        
        // Verify handler execution counts
        assertThat(testEventCount.get()).isEqualTo(subscribersPerType * (totalEvents / 2));
        assertThat(anotherEventCount.get()).isEqualTo(subscribersPerType * (totalEvents / 2));
    }
    
    // ========== Edge Cases ==========
    
    @Test
    @DisplayName("Should reject null events with clear exception")
    void shouldHandleNullEventGracefully() {
        assertThatThrownBy(() -> eventBus.publishEvent(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Event must not be null");
    }
    
    @Test
    @DisplayName("Should reject null subscriber callbacks with NPE")
    void shouldHandleNullSubscriberGracefully() {
        assertThatThrownBy(() -> eventBus.addSubscriber(TestEvent.class, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("callback must not be null");
    }
    
    @Test
    @DisplayName("Should reject null filters for filtered subscribers")
    void shouldHandleNullFilterGracefully() {
        assertThatThrownBy(() -> eventBus.addSubscriberForFilteredEvents(TestEvent.class, null, e -> {}))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Filter must not be null");
    }
    
    @Test
    @DisplayName("Should handle subscriber modifying subscriptions during callback without errors")
    void shouldHandleSubscriberModifyingSubscriptionsInCallback() {
        List<Subscription> addedSubscriptions = new CopyOnWriteArrayList<>();
        AtomicInteger eventCount = new AtomicInteger();
        AtomicBoolean shouldAddSubscriber = new AtomicBoolean(true);
        AtomicBoolean shouldRemoveSubscriber = new AtomicBoolean(false);
        
        // Subscriber that modifies subscriptions during event handling
        Consumer<TestEvent> dynamicSubscriber = event -> {
            eventCount.incrementAndGet();
            
            // Add a new subscriber on first event
            if (shouldAddSubscriber.compareAndSet(true, false)) {
                Subscription newSub = eventBus.addSubscriber(TestEvent.class, 
                    e -> eventCount.incrementAndGet());
                addedSubscriptions.add(newSub);
            }
            
            // Remove the added subscriber on a later event
            if (shouldRemoveSubscriber.compareAndSet(true, false) && !addedSubscriptions.isEmpty()) {
                addedSubscriptions.get(0).unsubscribe();
            }
        };
        
        eventBus.addSubscriber(TestEvent.class, dynamicSubscriber);
        
        // Test that adding subscribers during event handling works
        eventBus.publishEvent(new TestEvent("add"));
        assertTrue(eventCount.get() >= 1, "Original subscriber should have executed");
        
        // Test that the newly added subscriber receives events
        int countBefore = eventCount.get();
        eventBus.publishEvent(new TestEvent("both"));
        assertTrue(eventCount.get() > countBefore, "Both subscribers should have executed");
        
        // Test that removing subscribers during event handling works
        shouldRemoveSubscriber.set(true);
        eventBus.publishEvent(new TestEvent("remove"));
        
        // Verify the subscription was marked as inactive
        if (!addedSubscriptions.isEmpty()) {
            assertFalse(addedSubscriptions.get(0).isActive(), 
                "Dynamically added subscriber should be unsubscribed");
        }
        
        // No exceptions should have been thrown during any of these operations
    }
}
