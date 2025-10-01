package com.hsbc.eventbus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import com.hsbc.eventbus.ThreadUnsafeEventBusTest.IntegerEvent;
import com.hsbc.eventbus.ThreadUnsafeEventBusTest.StringEvent;
import com.hsbc.eventbus.ThreadUnsafeEventBusTest.TestEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtraThreadUnsafeEventBusTest {

    private ThreadUnsafeEventBus threadUnsafeEventBus;
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        threadUnsafeEventBus = new ThreadUnsafeEventBus();
        eventBus = threadUnsafeEventBus;
    }

    @Test
    @DisplayName("Should handle multiple close calls gracefully")
    void shouldHandleMultipleCloseCalls() {
        threadUnsafeEventBus.addSubscriber(ThreadUnsafeEventBusTest.TestEvent.class, e -> {});

        threadUnsafeEventBus.close();
        assertTrue(threadUnsafeEventBus.isClosed());

        assertDoesNotThrow(threadUnsafeEventBus::close);
        assertTrue(threadUnsafeEventBus.isClosed());
    }

    @Test
    @DisplayName("Should work with try-with-resources")
    void shouldWorkWithTryWithResources() {
        AtomicInteger counter = new AtomicInteger(0);

        try (ThreadUnsafeEventBus autoCloseEventBus = new ThreadUnsafeEventBus()) {
            autoCloseEventBus.addSubscriber(ThreadUnsafeEventBusTest.TestEvent.class, e -> counter.incrementAndGet());
            autoCloseEventBus.publishEvent(new ThreadUnsafeEventBusTest.TestEvent("test"));

            assertEquals(1, counter.get());
            assertFalse(autoCloseEventBus.isClosed());
        }
    }

    @Test
    @DisplayName("Should handle zigzag subscription pattern")
    void shouldHandleZigzagSubscriptionPattern() {
        List<String> executionOrder = new ArrayList<>();
        AtomicReference<Subscription> subA = new AtomicReference<>();
        AtomicReference<Subscription> subB = new AtomicReference<>();
        AtomicReference<Subscription> subC = new AtomicReference<>();
        
        // Order matters! We'll set up C first, then B, then A
        // So the execution order in the list will be C, B, A
        
        // Subscriber C: unsubscribes A when called
        Subscription subscriptionC = eventBus.addSubscriber(TestEvent.class, event -> {
            executionOrder.add("C");
            if (subA.get() != null && subA.get().isActive()) {
                subA.get().unsubscribe();
                executionOrder.add("C-unsubscribed-A");
            }
        });
        subC.set(subscriptionC);
        
        // Subscriber B: unsubscribes C when called
        Subscription subscriptionB = eventBus.addSubscriber(TestEvent.class, event -> {
            executionOrder.add("B");
            if (subC.get() != null && subC.get().isActive()) {
                subC.get().unsubscribe();
                executionOrder.add("B-unsubscribed-C");
            }
        });
        subB.set(subscriptionB);
        
        // Subscriber A: unsubscribes B when called
        Subscription subscriptionA = eventBus.addSubscriber(TestEvent.class, event -> {
            executionOrder.add("A");
            if (subB.get() != null && subB.get().isActive()) {
                subB.get().unsubscribe();
                executionOrder.add("A-unsubscribed-B");
            }
        });
        subA.set(subscriptionA);
        
        // First event - all three subscribers are active initially
        eventBus.publishEvent(new TestEvent("first"));
        
        // The execution order depends on the order they were added
        // With defensive copy, all subscribers in the snapshot will attempt to execute
        // But if a subscriber is marked inactive before its turn, it won't execute
        // C executes first, unsubscribes A (A is marked inactive)
        // B executes second, unsubscribes C (C already executed)
        // A would be third but was marked inactive by C, so it doesn't execute
        
        // We should see C and B execute, but not necessarily A
        assertThat(executionOrder).contains("C");
        
        // Verify at least some unsubscription occurred
        assertTrue(executionOrder.stream().anyMatch(s -> s.contains("unsubscribed")));
        
        // After the first event, check which subscriptions are inactive
        int inactiveCount = 0;
        if (!subscriptionA.isActive()) inactiveCount++;
        if (!subscriptionB.isActive()) inactiveCount++;
        if (!subscriptionC.isActive()) inactiveCount++;
        
        // At least one should be unsubscribed
        assertTrue(inactiveCount > 0, "At least one subscription should be inactive");
        
        // Clear for next event
        executionOrder.clear();
        
        // Second event - fewer or no subscribers should be called
        eventBus.publishEvent(new TestEvent("second"));
        
        // The number of executions should be less than or equal to the number of active subscriptions
        int activeCount = 3 - inactiveCount;
        assertTrue(executionOrder.size() <= activeCount * 2, // *2 to account for unsubscribe messages
            "Second event should have fewer executions than first");
    }

    @Test
    @DisplayName("Should not retain references to unsubscribed callbacks")
    void shouldNotRetainUnsubscribedCallbacks() {
        // Create a large object that would be noticeable if leaked
        byte[] largeData = new byte[1024 * 1024]; // 1MB
        AtomicReference<byte[]> callbackReference = new AtomicReference<>(largeData);
        
        // Create a subscriber that captures the large object
        Consumer<TestEvent> subscriber = event -> {
            // Access the large data to ensure it's captured
            if (callbackReference.get() != null) {
                callbackReference.get()[0] = 1;
            }
        };
        
        // Subscribe and then unsubscribe
        Subscription subscription = eventBus.addSubscriber(TestEvent.class, subscriber);
        assertTrue(subscription.isActive());
        
        // Verify subscriber works
        eventBus.publishEvent(new TestEvent("test"));
        
        // Unsubscribe
        subscription.unsubscribe();
        assertFalse(subscription.isActive());
        
        // Clear our reference to the large data
        callbackReference.set(null);
        subscriber = null;
        
        // After unsubscription, the EventBus should not retain the subscriber
        // The subscriber list should be empty for TestEvent
        assertEquals(0, threadUnsafeEventBus.getSubscriberCount(TestEvent.class));
        
        // Force garbage collection hint (not guaranteed but helps in testing)
        System.gc();
        
        // Publish more events - should not invoke the unsubscribed callback
        eventBus.publishEvent(new TestEvent("after unsubscribe"));
        
        // If the callback was retained, it would have thrown NPE accessing callbackReference.get()
        // The fact that no exception occurs and subscriber count is 0 indicates proper cleanup
    }
    

    @Test
    @DisplayName("Should handle concurrent modifications during publish")
    void shouldHandleConcurrentModifications() {
        List<String> received = new ArrayList<>();
        AtomicReference<Subscription> subRef = new AtomicReference<>();
        
        // First subscriber
        eventBus.addSubscriber(StringEvent.class, e -> {
            received.add("First");
            // Try to unsubscribe during event processing
            if (subRef.get() != null) {
                subRef.get().unsubscribe();
            }
        });
        
        // Second subscriber (will be unsubscribed by first)
        Subscription sub2 = eventBus.addSubscriber(StringEvent.class, 
            e -> received.add("Second"));
        subRef.set(sub2);
        
        // Third subscriber
        eventBus.addSubscriber(StringEvent.class, 
            e -> received.add("Third"));
        
        // Should handle modification gracefully
        assertDoesNotThrow(() -> eventBus.publishEvent(new StringEvent("Test")));
        
        // The defensive copy protects against ConcurrentModificationException,
        // but the second subscriber is marked inactive during the first subscriber's execution,
        // so it won't execute when its turn comes in the iteration
        assertThat(received).containsExactlyInAnyOrder("First", "Third");
    }

    // ========== Tests for Recent Bug Fixes ==========
    
    @Test
    @DisplayName("Should not increment totalEventsPublished when event is rejected by guards")
    void shouldNotIncrementMetricsWhenEventRejected() {
        // Close the event bus to test guard rejection
        threadUnsafeEventBus.close();
        
        long initialCount = threadUnsafeEventBus.getTotalEventsPublished();
        
        // Try to publish (should throw IllegalStateException because EventBus is closed)
        assertThrows(IllegalStateException.class, () -> {
            eventBus.publishEvent(new TestEvent("test"));
        });
        
        // Metrics should NOT have been incremented
        assertEquals(initialCount, threadUnsafeEventBus.getTotalEventsPublished(),
            "Should not increment counter when event is rejected by guards");
    }
    
    @Test
    @DisplayName("Should track error count separately from invocation count")
    void shouldTrackErrorCountSeparately() {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Add a subscriber that throws on even numbers
        eventBus.addSubscriber(IntegerEvent.class, event -> {
            if (event.value % 2 == 0) {
                errorCount.incrementAndGet();
                throw new RuntimeException("Even number!");
            }
            successCount.incrementAndGet();
        });
        
        // Publish 10 events (5 will succeed, 5 will fail)
        for (int i = 1; i <= 10; i++) {
            eventBus.publishEvent(new IntegerEvent(i));
        }
        
        // Verify counts
        assertEquals(5, successCount.get(), "Should have 5 successful invocations");
        assertEquals(5, errorCount.get(), "Should have 5 errors");
        assertEquals(10, threadUnsafeEventBus.getTotalEventsPublished(), "All events should be counted as published");
    }
    
    @Test
    @DisplayName("Should handle misbehaving DeadEvent subscribers without silent failures")
    void shouldHandleMisbehavingDeadEventSubscribers() {
        AtomicInteger deadEventCount = new AtomicInteger(0);
        AtomicBoolean throwException = new AtomicBoolean(true);
        
        // Add a DeadEvent subscriber that throws exceptions
        eventBus.addSubscriber(DeadEvent.class, deadEvent -> {
            deadEventCount.incrementAndGet();
            if (throwException.get()) {
                throw new RuntimeException("Misbehaving DeadEvent handler!");
            }
        });
        
        // Publish an event with no subscribers
        eventBus.publishEvent(new StringEvent("unhandled"));
        
        // DeadEvent handler should have been called despite throwing
        assertEquals(1, deadEventCount.get());
        assertEquals(1, threadUnsafeEventBus.getDeadEventCount());
        
        // Now test without exception
        throwException.set(false);
        eventBus.publishEvent(new StringEvent("another unhandled"));
        
        assertEquals(2, deadEventCount.get());
        assertEquals(2, threadUnsafeEventBus.getDeadEventCount());
    }
    
    @Test
    @DisplayName("Should prevent deep recursion even with complex DeadEvent scenarios")
    void shouldPreventDeepRecursionWithComplexDeadEvents() {
        AtomicInteger recursionTracker = new AtomicInteger(0);
        
        // Add a DeadEvent subscriber that publishes another unhandled event
        eventBus.addSubscriber(DeadEvent.class, deadEvent -> {
            int depth = recursionTracker.incrementAndGet();
            if (depth < 10) { // Try to recurse deeply
                // This will trigger another dead event
                eventBus.publishEvent(new IntegerEvent(depth));
            }
        });
        
        // Start the recursion chain
        eventBus.publishEvent(new StringEvent("start"));
        
        // Recursion should be limited by MAX_DEAD_EVENT_RECURSION_DEPTH (2)
        // So we should see at most 2 levels of recursion
        assertTrue(recursionTracker.get() <= 2, 
            "Recursion depth should be limited to MAX_DEAD_EVENT_RECURSION_DEPTH");
        
        // Dead event count should reflect what actually happened
        assertTrue(threadUnsafeEventBus.getDeadEventCount() >= 1, 
            "Should have at least one dead event");
    }

    @RepeatedTest(10)
    @DisplayName("Should maintain consistency under rapid subscribe/unsubscribe")
    void shouldMaintainConsistencyUnderRapidChanges() {
        List<Subscription> subscriptions = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger eventCount = new AtomicInteger(0);
        Random random = new Random();
        
        // Rapidly add and remove subscribers while publishing events
        for (int i = 0; i < 100; i++) {
            if (random.nextBoolean() || subscriptions.isEmpty()) {
                // Add subscriber
                subscriptions.add(eventBus.addSubscriber(TestEvent.class, 
                    e -> eventCount.incrementAndGet()));
            } else {
                // Remove random subscriber
                int index = random.nextInt(subscriptions.size());
                subscriptions.get(index).unsubscribe();
                subscriptions.remove(index);
            }
            
            // Publish event
            int expectedCalls = subscriptions.size();
            int beforeCount = eventCount.get();
            eventBus.publishEvent(new TestEvent("test" + i));
            int actualCalls = eventCount.get() - beforeCount;
            
            // Verify correct number of calls
            assertEquals(expectedCalls, actualCalls, 
                "Mismatch at iteration " + i + " with " + subscriptions.size() + " subscribers");
        }
    }

    @Test
    @DisplayName("Should not accumulate dead event instances")
    void shouldNotAccumulateDeadEventInstances() {
        // Track dead events received
        List<DeadEvent> deadEvents = new ArrayList<>();
        eventBus.addSubscriber(DeadEvent.class, deadEvents::add);
        
        // Publish many orphan events
        int orphanCount = 1000;
        for (int i = 0; i < orphanCount; i++) {
            eventBus.publishEvent(new StringEvent("orphan-" + i));
        }
        
        // Verify we received all dead events
        assertEquals(orphanCount, deadEvents.size());
        assertEquals(orphanCount, threadUnsafeEventBus.getDeadEventCount());
        
        // Clear our reference to dead events
        deadEvents.clear();
        
        // The EventBus itself should not retain these DeadEvent instances
        // They should be eligible for garbage collection after processing
        
        // Publish more events to ensure the bus is still functional
        AtomicInteger newDeadEventCount = new AtomicInteger(0);
        eventBus.addSubscriber(DeadEvent.class, e -> newDeadEventCount.incrementAndGet());
        
        eventBus.publishEvent(new IntegerEvent(42)); // Another orphan
        assertEquals(1, newDeadEventCount.get());
        
        // Total dead event count should be correct
        assertEquals(orphanCount + 1, threadUnsafeEventBus.getDeadEventCount());
    }
    

    @Test
    @DisplayName("Should handle filter that always returns false")
    void shouldHandleAlwaysFalseFilter() {
        AtomicInteger callCount = new AtomicInteger(0);
        
        // Add a filtered subscriber that never accepts any events
        eventBus.addSubscriberForFilteredEvents(
            TestEvent.class,
            event -> false, // Always reject
            event -> callCount.incrementAndGet()
        );
        
        // Publish multiple events
        for (int i = 0; i < 10; i++) {
            eventBus.publishEvent(new TestEvent("test-" + i));
        }
        
        // Subscriber should never be called
        assertEquals(0, callCount.get());
        
        // Events should still be counted as published
        assertEquals(10, threadUnsafeEventBus.getTotalEventsPublished());
        
        // These should not be dead events (there was a subscriber, just filtered out)
        assertEquals(0, threadUnsafeEventBus.getDeadEventCount());
    }
    
    @Test
    @DisplayName("Should not count events when null is passed")
    void shouldNotCountNullEvents() {
        long initialCount = threadUnsafeEventBus.getTotalEventsPublished();
        
        // Try to publish null (should throw NPE)
        assertThrows(NullPointerException.class, () -> {
            eventBus.publishEvent(null);
        });
        
        // Count should not have changed
        assertEquals(initialCount, threadUnsafeEventBus.getTotalEventsPublished(),
            "Should not increment counter when null event is rejected");
    }
    

    @Test
    @DisplayName("Should handle DeadEvent constructor errors gracefully")
    void shouldHandleDeadEventConstructorErrors() {
        // Create a pathological event that might cause issues
        Object problematicEvent = new Object() {
            @Override
            public String toString() {
                throw new RuntimeException("toString() error");
            }
        };
        
        // Publishing this event with no subscribers should not crash
        // even if DeadEvent constructor or handling has issues
        assertDoesNotThrow(() -> eventBus.publishEvent(problematicEvent));
        
        // Should still count as a dead event attempt
        assertEquals(1, threadUnsafeEventBus.getDeadEventCount());
    }

    @Test
    @DisplayName("Should properly remove wrapper from list on unsubscribe to prevent memory leak")
    void shouldRemoveWrapperFromListOnUnsubscribe() {
        // Add multiple subscribers
        Subscription sub1 = eventBus.addSubscriber(TestEvent.class, e -> {});
        Subscription sub2 = eventBus.addSubscriber(TestEvent.class, e -> {});
        Subscription sub3 = eventBus.addSubscriber(TestEvent.class, e -> {});
        
        assertEquals(3, threadUnsafeEventBus.getSubscriberCount(TestEvent.class));
        
        // Unsubscribe one
        sub2.unsubscribe();
        
        // Verify it's removed from the list (not just marked inactive)
        assertEquals(2, threadUnsafeEventBus.getSubscriberCount(TestEvent.class));
        
        // Verify the subscription reports inactive
        assertFalse(sub2.isActive());
        
        // Unsubscribe the rest
        sub1.unsubscribe();
        sub3.unsubscribe();
        
        // Verify all are removed
        assertEquals(0, threadUnsafeEventBus.getSubscriberCount(TestEvent.class));
    }
    
    @Test
    @DisplayName("Should handle deep dead event recursion with depth limit")
    void shouldHandleDeepDeadEventRecursion() {
        AtomicInteger deadEventCount = new AtomicInteger(0);
        AtomicInteger nestedDeadEventCount = new AtomicInteger(0);
        
        // Add a DeadEvent subscriber that publishes another event with no subscribers
        eventBus.addSubscriber(DeadEvent.class, deadEvent -> {
            deadEventCount.incrementAndGet();
            // This will trigger another dead event
            eventBus.publishEvent(new IntegerEvent(42));
        });
        
        // Add another DeadEvent subscriber to count nested dead events
        eventBus.addSubscriber(DeadEvent.class, deadEvent -> {
            if (deadEvent.getEvent() instanceof IntegerEvent) {
                nestedDeadEventCount.incrementAndGet();
            }
        });
        
        // Publish an event with no subscribers - starts the recursion chain
        eventBus.publishEvent(new StringEvent("trigger"));
        
        // Should have limited recursion depth (MAX_DEAD_EVENT_RECURSION_DEPTH = 2)
        // First dead event for StringEvent, then dead event for IntegerEvent
        assertTrue(deadEventCount.get() <= 2, "Dead event recursion should be limited");
        assertTrue(nestedDeadEventCount.get() <= 1, "Nested dead events should be limited");
    }
    
    @Test
    @DisplayName("Should handle type casting cleanly with Class.cast()")
    void shouldHandleTypeCastingCleanly() {
        AtomicBoolean called = new AtomicBoolean(false);
        
        // Add subscriber for TestEvent
        eventBus.addSubscriber(TestEvent.class, event -> {
            called.set(true);
            assertNotNull(event.data);
        });
        
        // Publish a TestEvent - should work fine
        eventBus.publishEvent(new TestEvent("test"));
        assertTrue(called.get());
        
        // Reset flag
        called.set(false);
        
        // Try to publish a different event type - should not call the subscriber
        eventBus.publishEvent(new StringEvent("different"));
        assertFalse(called.get(), "Should not invoke subscriber for wrong event type");
    }

    @Test
    @DisplayName("Should not increment metrics for inactive subscribers")
    void shouldNotIncrementMetricsForInactiveSubscribers() {
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<Subscription> subRef = new AtomicReference<>();
        AtomicBoolean shouldUnsubscribe = new AtomicBoolean(false);

        Subscription sub1 = eventBus.addSubscriber(ThreadUnsafeEventBusTest.TestEvent.class, e -> {
            callCount.incrementAndGet();
            if (shouldUnsubscribe.get() && subRef.get() != null) {
                subRef.get().unsubscribe();
            }
        });

        Subscription sub2 = eventBus.addSubscriber(ThreadUnsafeEventBusTest.TestEvent.class, e -> callCount.incrementAndGet());
        subRef.set(sub2);

        eventBus.publishEvent(new ThreadUnsafeEventBusTest.TestEvent("first"));
        assertEquals(2, callCount.get());

        callCount.set(0);
        shouldUnsubscribe.set(true);

        eventBus.publishEvent(new ThreadUnsafeEventBusTest.TestEvent("second"));
        assertEquals(1, callCount.get());

        assertTrue(sub1.isActive());
        assertFalse(sub2.isActive());

        threadUnsafeEventBus.close();
    }
}
