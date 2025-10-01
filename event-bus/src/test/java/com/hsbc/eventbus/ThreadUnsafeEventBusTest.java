package com.hsbc.eventbus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for ThreadUnsafeEventBus.
 * Tests both interface compliance and implementation-specific features.
 */
class ThreadUnsafeEventBusTest {
    
    private EventBus eventBus;
    private ThreadUnsafeEventBus threadUnsafeEventBus;
    
    // Test event classes
    static class TestEvent {
        final String data;
        TestEvent(String data) { this.data = data; }
    }
    
    static class StringEvent {
        final String message;
        StringEvent(String message) { this.message = message; }
    }
    
    static class IntegerEvent {
        final int value;
        IntegerEvent(int value) { this.value = value; }
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
        List<String> invocationOrder = new ArrayList<>();

        eventBus.addSubscriber(ChildEvent.class, event -> invocationOrder.add("child"));
        eventBus.addSubscriber(ParentEvent.class, event -> invocationOrder.add("parent"));
        eventBus.addSubscriber(Object.class, event -> invocationOrder.add("object"));

        eventBus.publishEvent(new ChildEvent("ordering"));

        assertThat(invocationOrder).containsExactly("child", "parent", "object");
    }
    
    @BeforeEach
    void setUp() {
        threadUnsafeEventBus = new ThreadUnsafeEventBus();
        eventBus = threadUnsafeEventBus; // Test through the interface
    }
    
    // ========== Basic Functionality Tests ==========
    
    @Test
    @DisplayName("Should create event bus successfully")
    void shouldCreateEventBus() {
        assertNotNull(eventBus);
        assertEquals(0, threadUnsafeEventBus.getTotalSubscriberCount());
        assertEquals(0, threadUnsafeEventBus.getTotalEventsPublished());
    }
    
    @Test
    @DisplayName("Should publish event to single subscriber")
    void shouldPublishToSingleSubscriber() {
        AtomicReference<String> received = new AtomicReference<>();
        
        eventBus.addSubscriber(StringEvent.class, event -> 
            received.set(event.message));
        
        eventBus.publishEvent(new StringEvent("Hello"));
        
        assertEquals("Hello", received.get());
        assertEquals(1, threadUnsafeEventBus.getTotalEventsPublished());
    }
    
    @Test
    @DisplayName("Should publish event to multiple subscribers")
    void shouldPublishToMultipleSubscribers() {
        List<String> received = new ArrayList<>();
        
        eventBus.addSubscriber(StringEvent.class, e -> received.add("Sub1: " + e.message));
        eventBus.addSubscriber(StringEvent.class, e -> received.add("Sub2: " + e.message));
        eventBus.addSubscriber(StringEvent.class, e -> received.add("Sub3: " + e.message));
        
        eventBus.publishEvent(new StringEvent("Test"));
        
        assertThat(received).containsExactlyInAnyOrder(
            "Sub1: Test", "Sub2: Test", "Sub3: Test"
        );
    }
    
    // ========== Filtering Tests ==========
    
    @Test
    @DisplayName("Should handle filtered events correctly")
    void shouldHandleFilteredEvents() {
        List<Integer> received = new ArrayList<>();
        
        // Only receive even numbers
        eventBus.addSubscriberForFilteredEvents(
            IntegerEvent.class,
            event -> event.value % 2 == 0,
            event -> received.add(event.value)
        );
        
        // Publish both even and odd numbers
        for (int i = 1; i <= 10; i++) {
            eventBus.publishEvent(new IntegerEvent(i));
        }
        
        assertThat(received).containsExactly(2, 4, 6, 8, 10);
    }
    
    @Test
    @DisplayName("Should handle subscriber that throws exception in filter")
    void shouldHandleFilterException() {
        AtomicInteger received = new AtomicInteger(0);
        
        // Add subscriber with filter that throws
        eventBus.addSubscriberForFilteredEvents(
            TestEvent.class,
            event -> {
                if (event.data.equals("bad")) {
                    throw new RuntimeException("Filter error");
                }
                return true;
            },
            event -> received.incrementAndGet()
        );
        
        // Should handle exception gracefully
        assertDoesNotThrow(() -> eventBus.publishEvent(new TestEvent("bad")));
        assertEquals(0, received.get()); // Subscriber not called due to filter exception
        
        // Should work normally for non-throwing case
        eventBus.publishEvent(new TestEvent("good"));
        assertEquals(1, received.get());
    }
    
    // ========== Subscription Management Tests ==========
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsubscribeScenarios")
    @DisplayName("Should stop receiving events after unsubscribe")
    void shouldStopReceivingEventsAfterUnsubscribe(String description, Consumer<EventBus> scenario) {
        scenario.accept(eventBus);
    }
    
    @Test
    @DisplayName("Should handle multiple unsubscribe calls gracefully")
    void shouldHandleMultipleUnsubscribeCalls() {
        Subscription subscription = eventBus.addSubscriber(
            StringEvent.class, e -> {});
        
        assertTrue(subscription.isActive());
        
        subscription.unsubscribe();
        assertFalse(subscription.isActive());
        
        // Should not throw on second call
        assertDoesNotThrow(() -> subscription.unsubscribe());
        assertFalse(subscription.isActive());
    }
    
    @Test
    @DisplayName("Should handle subscriber that unsubscribes itself")
    void shouldHandleSelfUnsubscribe() {
        AtomicInteger counter = new AtomicInteger(0);
        AtomicReference<Subscription> subRef = new AtomicReference<>();
        
        Subscription subscription = eventBus.addSubscriber(TestEvent.class, e -> {
            counter.incrementAndGet();
            if (counter.get() == 2) {
                subRef.get().unsubscribe(); // Unsubscribe itself
            }
        });
        subRef.set(subscription);
        
        // Publish 5 events
        for (int i = 0; i < 5; i++) {
            eventBus.publishEvent(new TestEvent("test" + i));
        }
        
        // Should only receive first 2 events
        assertEquals(2, counter.get());
    }
    
    @Test
    @DisplayName("Single subscriber should handle multiple event types correctly")
    void shouldHandleMultipleEventTypesInSingleSubscriber() {
        List<String> eventLog = new ArrayList<>();
        Consumer<TestEvent> testHandler = e -> eventLog.add("TestEvent: " + e.data);
        Consumer<IntegerEvent> integerHandler = e -> eventLog.add("IntegerEvent: " + e.value);
        
        Subscription testSubscription = eventBus.addSubscriber(TestEvent.class, testHandler);
        Subscription integerSubscription = eventBus.addSubscriber(IntegerEvent.class, integerHandler);
        
        eventBus.publishEvent(new TestEvent("hello"));
        eventBus.publishEvent(new IntegerEvent(42));
        eventBus.publishEvent(new TestEvent("world"));
        eventBus.publishEvent(new IntegerEvent(99));
        
        assertThat(eventLog).containsExactly(
            "TestEvent: hello",
            "IntegerEvent: 42",
            "TestEvent: world",
            "IntegerEvent: 99"
        );
        assertTrue(testSubscription.isActive());
        assertTrue(integerSubscription.isActive());
    }
    
    @Test
    @DisplayName("Should handle re-subscribing after unsubscribe cleanly")
    void shouldHandleResubscribeAfterUnsubscribe() {
        AtomicInteger counter = new AtomicInteger();
        List<Integer> executionOrder = new ArrayList<>();
        
        Subscription firstSub = eventBus.addSubscriber(TestEvent.class, e -> {
            executionOrder.add(1);
            counter.incrementAndGet();
        });
        
        eventBus.publishEvent(new TestEvent("event1"));
        assertThat(counter.get()).isEqualTo(1);
        assertThat(executionOrder).containsExactly(1);
        
        firstSub.unsubscribe();
        assertFalse(firstSub.isActive());
        
        Subscription secondSub = eventBus.addSubscriber(TestEvent.class, e -> {
            executionOrder.add(2);
            counter.addAndGet(10);
        });
        
        eventBus.publishEvent(new TestEvent("event3"));
        assertThat(counter.get()).isEqualTo(11); // 1 + 10
        assertThat(executionOrder).containsExactly(1, 2);
        assertTrue(secondSub.isActive());
        
        Subscription thirdSub = eventBus.addSubscriber(TestEvent.class, e -> {
            executionOrder.add(3);
            counter.addAndGet(100);
        });
        
        eventBus.publishEvent(new TestEvent("event4"));
        assertThat(counter.get()).isEqualTo(121); // 11 + 10 + 100
        assertThat(executionOrder).containsExactly(1, 2, 2, 3);
        assertTrue(secondSub.isActive());
        assertTrue(thirdSub.isActive());
        
        assertThat(threadUnsafeEventBus.getSubscriberCount(TestEvent.class)).isEqualTo(2);
    }
    
    @Test
    @DisplayName("Should maintain correct count after multiple subscribe/unsubscribe cycles")
    void shouldMaintainCorrectCountAfterCycles() {
        List<Subscription> subscriptions = new ArrayList<>();
        
        // Add 100 subscribers
        for (int i = 0; i < 100; i++) {
            subscriptions.add(eventBus.addSubscriber(TestEvent.class, e -> {}));
        }
        assertEquals(100, threadUnsafeEventBus.getTotalSubscriberCount());
        
        // Unsubscribe half
        for (int i = 0; i < 50; i++) {
            subscriptions.get(i).unsubscribe();
        }
        assertEquals(50, threadUnsafeEventBus.getTotalSubscriberCount());
        
        // Add 25 more
        for (int i = 0; i < 25; i++) {
            subscriptions.add(eventBus.addSubscriber(TestEvent.class, e -> {}));
        }
        assertEquals(75, threadUnsafeEventBus.getTotalSubscriberCount());
        
        // Clear all
        threadUnsafeEventBus.clearAllSubscribers();
        assertEquals(0, threadUnsafeEventBus.getTotalSubscriberCount());
    }
    
    // ========== Dead Event Tests ==========
    
    @Test
    @DisplayName("Should handle dead events")
    void shouldHandleDeadEvents() {
        AtomicReference<DeadEvent> deadEventReceived = new AtomicReference<>();
        
        // Subscribe to dead events
        eventBus.addSubscriber(DeadEvent.class, deadEventReceived::set);
        
        // Publish event with no subscribers
        StringEvent orphanEvent = new StringEvent("No one listening");
        eventBus.publishEvent(orphanEvent);
        
        assertNotNull(deadEventReceived.get());
        assertEquals(orphanEvent, deadEventReceived.get().getEvent());
        assertEquals(1, threadUnsafeEventBus.getDeadEventCount());
    }
    
    @Test
    @DisplayName("Should prevent stack overflow with recursive dead events")
    void shouldPreventDeadEventStackOverflow() {
        // Subscribe to DeadEvent and throw exception
        eventBus.addSubscriber(DeadEvent.class, event -> {
            throw new RuntimeException("DeadEvent handler error");
        });
        
        // This should not cause stack overflow
        assertDoesNotThrow(() -> eventBus.publishEvent(new TestEvent("orphan")));
        
        // Should count only one dead event (not recursive)
        assertEquals(1, threadUnsafeEventBus.getDeadEventCount());
    }
    
    // ========== Error Handling Tests ==========
    
    @Test
    @DisplayName("Should isolate subscriber exceptions")
    void shouldIsolateSubscriberExceptions() {
        List<String> received = new ArrayList<>();
        
        // First subscriber throws exception
        eventBus.addSubscriber(StringEvent.class, e -> {
            throw new RuntimeException("Subscriber error");
        });
        
        // Second subscriber should still receive event
        eventBus.addSubscriber(StringEvent.class, e -> 
            received.add(e.message));
        
        // Should not throw
        assertDoesNotThrow(() -> 
            eventBus.publishEvent(new StringEvent("Test")));
        
        assertThat(received).containsExactly("Test");
    }
    
    
    // ========== Null Validation Tests ==========
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("nullInputScenarios")
    @DisplayName("Should reject null inputs")
    void shouldRejectNullInputs(String description, Consumer<EventBus> action) {
        assertThrows(NullPointerException.class, () -> action.accept(eventBus));
    }

    private static Stream<Arguments> unsubscribeScenarios() {
        return Stream.of(
            Arguments.of("Regular subscriber unsubscribe", (Consumer<EventBus>) bus -> {
                AtomicInteger counter = new AtomicInteger(0);
                Subscription subscription = bus.addSubscriber(StringEvent.class, e -> counter.incrementAndGet());

                assertTrue(subscription.isActive());
                bus.publishEvent(new StringEvent("First"));
                assertEquals(1, counter.get());

                subscription.unsubscribe();
                assertFalse(subscription.isActive());

                bus.publishEvent(new StringEvent("Second"));
                assertEquals(1, counter.get());
            }),
            Arguments.of("Filtered subscriber unsubscribe", (Consumer<EventBus>) bus -> {
                List<Integer> received = new ArrayList<>();
                Subscription subscription = bus.addSubscriberForFilteredEvents(
                    IntegerEvent.class,
                    event -> event.value > 10,
                    event -> received.add(event.value)
                );

                bus.publishEvent(new IntegerEvent(5));
                bus.publishEvent(new IntegerEvent(15));
                assertThat(received).containsExactly(15);
                assertTrue(subscription.isActive());

                subscription.unsubscribe();
                assertFalse(subscription.isActive());

                bus.publishEvent(new IntegerEvent(20));
                bus.publishEvent(new IntegerEvent(25));
                assertThat(received).containsExactly(15);
            })
        );
    }

    private static Stream<Arguments> nullInputScenarios() {
        return Stream.of(
            Arguments.of("publishEvent(null)", (Consumer<EventBus>) bus -> bus.publishEvent(null)),
            Arguments.of("addSubscriber with null callback", (Consumer<EventBus>) bus -> bus.addSubscriber(StringEvent.class, null)),
            Arguments.of("addSubscriber with null event class", (Consumer<EventBus>) bus -> bus.addSubscriber(null, (Consumer<TestEvent>) e -> {})),
            Arguments.of(
                "addSubscriberForFilteredEvents with null filter",
                (Consumer<EventBus>) bus -> bus.addSubscriberForFilteredEvents(TestEvent.class, null, e -> {})
            )
        );
    }
    
    // ========== Bulk Operations Tests ==========
    
    @Test
    @DisplayName("Should clear all subscribers")
    void shouldClearAllSubscribers() {
        eventBus.addSubscriber(StringEvent.class, e -> {});
        eventBus.addSubscriber(IntegerEvent.class, e -> {});
        eventBus.addSubscriber(ParentEvent.class, e -> {});
        
        assertEquals(3, threadUnsafeEventBus.getTotalSubscriberCount());
        
        int removed = threadUnsafeEventBus.clearAllSubscribers();
        
        assertEquals(3, removed);
        assertEquals(0, threadUnsafeEventBus.getTotalSubscriberCount());
    }
    
    @Test
    @DisplayName("Should remove subscribers by type")
    void shouldRemoveSubscribersByType() {
        eventBus.addSubscriber(StringEvent.class, e -> {});
        eventBus.addSubscriber(StringEvent.class, e -> {});
        eventBus.addSubscriber(IntegerEvent.class, e -> {});
        
        assertEquals(3, threadUnsafeEventBus.getTotalSubscriberCount());
        
        int removed = threadUnsafeEventBus.removeAllSubscribers(StringEvent.class);
        
        assertEquals(2, removed);
        assertEquals(1, threadUnsafeEventBus.getTotalSubscriberCount());
        assertEquals(1, threadUnsafeEventBus.getSubscriberCount(IntegerEvent.class));
        assertEquals(0, threadUnsafeEventBus.getSubscriberCount(StringEvent.class));
    }
    
    
    @Test
    @DisplayName("Should handle very large number of subscribers efficiently")
    void shouldHandleManySubscribersPerformance() {
        AtomicInteger totalReceived = new AtomicInteger(0);
        
        // Add 10,000 subscribers
        for (int i = 0; i < 10_000; i++) {
            eventBus.addSubscriber(TestEvent.class, e -> totalReceived.incrementAndGet());
        }
        
        long startTime = System.currentTimeMillis();
        eventBus.publishEvent(new TestEvent("test"));
        long duration = System.currentTimeMillis() - startTime;
        
        assertEquals(10_000, totalReceived.get());
        // Should complete in reasonable time (< 1 second)
        assertThat(duration).isLessThan(1000);
    }    

    
    @Test
    @DisplayName("Should handle events published during event handling")
    void shouldHandleNestedEventPublication() {
        List<String> events = new ArrayList<>();
        
        eventBus.addSubscriber(TestEvent.class, e -> {
            events.add("Test: " + e.data);
            if (e.data.equals("first")) {
                // Publish another event during handling
                eventBus.publishEvent(new TestEvent("nested"));
            }
        });
        
        eventBus.publishEvent(new TestEvent("first"));
        
        assertThat(events).containsExactly("Test: first", "Test: nested");
    }
    

    
    // ========== Duplicate Subscriber Tests ==========
    
    @Test
    @DisplayName("Should handle same subscriber added multiple times")
    void shouldHandleDuplicateSubscribers() {
        AtomicInteger counter = new AtomicInteger(0);
        Consumer<TestEvent> subscriber = e -> counter.incrementAndGet();
        
        // Add same subscriber 3 times
        eventBus.addSubscriber(TestEvent.class, subscriber);
        eventBus.addSubscriber(TestEvent.class, subscriber);
        eventBus.addSubscriber(TestEvent.class, subscriber);
        
        eventBus.publishEvent(new TestEvent("test"));
        
        // Should be called 3 times (no deduplication)
        assertEquals(3, counter.get());
        assertEquals(3, threadUnsafeEventBus.getSubscriberCount(TestEvent.class));
    }
    
    // ========== AutoCloseable Tests ==========
    
    @Test
    @DisplayName("Should properly close EventBus")
    void shouldCloseEventBus() throws Exception {
        // Add some subscribers
        eventBus.addSubscriber(TestEvent.class, e -> {});
        eventBus.addSubscriber(StringEvent.class, e -> {});
        
        assertEquals(2, threadUnsafeEventBus.getTotalSubscriberCount());
        assertFalse(threadUnsafeEventBus.isClosed());
        
        // Close the EventBus
        threadUnsafeEventBus.close();
        
        assertTrue(threadUnsafeEventBus.isClosed());
        assertEquals(0, threadUnsafeEventBus.getTotalSubscriberCount());
    }
    
    @Test
    @DisplayName("Should reject operations after close")
    void shouldRejectOperationsAfterClose() throws Exception {
        threadUnsafeEventBus.close();
        
        // Should throw IllegalStateException for all operations
        assertThrows(IllegalStateException.class, 
            () -> eventBus.publishEvent(new TestEvent("test")));
        
        assertThrows(IllegalStateException.class, 
            () -> eventBus.addSubscriber(TestEvent.class, e -> {}));
        
        assertThrows(IllegalStateException.class, 
            () -> eventBus.addSubscriberForFilteredEvents(TestEvent.class, e -> true, e -> {}));
    }
    
    // ========== Consistency Tests ==========
    
    @Test
    @DisplayName("Should handle removeAllSubscribers with held Subscription reference correctly")
    void shouldHandleRemoveAllWithHeldSubscription() {
        Subscription subscription = eventBus.addSubscriber(TestEvent.class, e -> {});
        
        assertTrue(subscription.isActive());
        
        // Remove all subscribers (sets wrapper.active = false)
        threadUnsafeEventBus.removeAllSubscribers(TestEvent.class);
        
        // Subscription should report inactive (single source of truth)
        assertFalse(subscription.isActive());
        
        // Calling unsubscribe on already removed subscription should be safe
        assertDoesNotThrow(() -> subscription.unsubscribe());
        
        // Should still be inactive
        assertFalse(subscription.isActive());
    }
    
    @Test
    @DisplayName("Should maintain consistency when clearAllSubscribers is called")
    void shouldMaintainConsistencyWithClearAll() {
        Subscription sub1 = eventBus.addSubscriber(TestEvent.class, e -> {});
        Subscription sub2 = eventBus.addSubscriber(StringEvent.class, e -> {});
        
        assertTrue(sub1.isActive());
        assertTrue(sub2.isActive());
        
        // Clear all subscribers
        threadUnsafeEventBus.clearAllSubscribers();
        
        // Both subscriptions should report inactive
        assertFalse(sub1.isActive());
        assertFalse(sub2.isActive());
        
        // Calling unsubscribe should be safe but do nothing
        assertDoesNotThrow(() -> sub1.unsubscribe());
        assertDoesNotThrow(() -> sub2.unsubscribe());
    }
    
    @Test
    @DisplayName("Should handle dead event publishing errors gracefully")
    void shouldHandleDeadEventPublishingErrors() {
        AtomicBoolean deadEventHandlerCalled = new AtomicBoolean(false);
        AtomicBoolean originalEventProcessed = new AtomicBoolean(false);
        
        // Add a DeadEvent subscriber that throws an exception
        eventBus.addSubscriber(DeadEvent.class, event -> {
            deadEventHandlerCalled.set(true);
            throw new RuntimeException("DeadEvent handler error");
        });
        
        // Publish an event with no subscribers - should trigger dead event
        // The dead event handler will throw, but original publish should complete
        assertDoesNotThrow(() -> eventBus.publishEvent(new TestEvent("orphan")));
        
        // Verify dead event handler was attempted
        assertTrue(deadEventHandlerCalled.get());
        
        // Verify metrics still updated despite error
        assertEquals(1, threadUnsafeEventBus.getDeadEventCount());
        
        // Now add a regular subscriber and verify normal operation continues
        eventBus.addSubscriber(TestEvent.class, e -> originalEventProcessed.set(true));
        eventBus.publishEvent(new TestEvent("normal"));
        
        assertTrue(originalEventProcessed.get());
    }
    

    
    @Test
    @DisplayName("Should propagate Error types from subscribers")
    void shouldPropagateErrorsFromSubscribers() {
        // Add a subscriber that throws an Error
        eventBus.addSubscriber(TestEvent.class, e -> {
            throw new OutOfMemoryError("Simulated OOM");
        });
        
        // Errors should be propagated, not caught
        assertThrows(OutOfMemoryError.class, 
            () -> eventBus.publishEvent(new TestEvent("test")),
            "Errors like OutOfMemoryError should be propagated");
    }
    
    @Test
    @DisplayName("Should handle RuntimeException from subscribers gracefully")
    void shouldHandleRuntimeExceptionFromSubscribers() {
        AtomicBoolean secondSubscriberCalled = new AtomicBoolean(false);
        
        // First subscriber throws RuntimeException
        eventBus.addSubscriber(TestEvent.class, e -> {
            throw new RuntimeException("Subscriber error");
        });
        
        // Second subscriber should still be called
        eventBus.addSubscriber(TestEvent.class, e -> {
            secondSubscriberCalled.set(true);
        });
        
        // Should not throw - RuntimeExceptions are caught
        assertDoesNotThrow(() -> eventBus.publishEvent(new TestEvent("test")));
        
        // Second subscriber should have been called
        assertTrue(secondSubscriberCalled.get());
    }
    
    @Test
    @DisplayName("Should support unsubscribe-from-callback behavior")
    void shouldSupportUnsubscribeFromCallback() {
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<Subscription> sub1Ref = new AtomicReference<>();
        AtomicReference<Subscription> sub2Ref = new AtomicReference<>();
        AtomicReference<Subscription> sub3Ref = new AtomicReference<>();
        
        // First subscriber unsubscribes itself after first call
        Subscription sub1 = eventBus.addSubscriber(TestEvent.class, e -> {
            callCount.incrementAndGet();
            sub1Ref.get().unsubscribe(); // Unsubscribe self
        });
        sub1Ref.set(sub1);
        
        // Second subscriber - normal subscriber
        Subscription sub2 = eventBus.addSubscriber(TestEvent.class, e -> {
            callCount.incrementAndGet();
        });
        sub2Ref.set(sub2);
        
        // Third subscriber - unsubscribes sub2 after being called
        Subscription sub3 = eventBus.addSubscriber(TestEvent.class, e -> {
            callCount.incrementAndGet();
            if (sub2Ref.get() != null) {
                sub2Ref.get().unsubscribe(); // Unsubscribe another during callback
            }
        });
        sub3Ref.set(sub3);
        
        // First publish - all three should be called (sub1 unsubscribes itself, sub3 unsubscribes sub2)
        eventBus.publishEvent(new TestEvent("first"));
        assertEquals(3, callCount.get(), "All three subscribers should be called on first publish");
        
        // Reset counter
        callCount.set(0);
        
        // Second publish - only sub3 should be called (sub1 unsubscribed itself, sub2 was unsubscribed by sub3)
        eventBus.publishEvent(new TestEvent("second"));
        assertEquals(1, callCount.get(), "Only sub3 should be called on second publish");
        
        // Verify subscription states
        assertFalse(sub1.isActive(), "Sub1 should be inactive (self-unsubscribed)");
        assertFalse(sub2.isActive(), "Sub2 should be inactive (unsubscribed by sub3)");
        assertTrue(sub3.isActive(), "Sub3 should still be active");
    }
    
    @Test
    @DisplayName("Should not allow operations after close except clearAllSubscribers")
    void shouldNotAllowOperationsAfterClose() {
        // Add a subscriber
        eventBus.addSubscriber(TestEvent.class, e -> {});
        
        // Close the EventBus
        threadUnsafeEventBus.close();
        assertTrue(threadUnsafeEventBus.isClosed());
        
        // These operations should throw IllegalStateException
        assertThrows(IllegalStateException.class, 
            () -> eventBus.publishEvent(new TestEvent("test")),
            "publishEvent should throw after close");
        
        assertThrows(IllegalStateException.class,
            () -> eventBus.addSubscriber(TestEvent.class, e -> {}),
            "addSubscriber should throw after close");
        
        assertThrows(IllegalStateException.class,
            () -> threadUnsafeEventBus.removeAllSubscribers(TestEvent.class),
            "removeAllSubscribers should throw after close");
        
        // clearAllSubscribers should still work for cleanup
        assertDoesNotThrow(() -> threadUnsafeEventBus.clearAllSubscribers(),
            "clearAllSubscribers should be allowed after close for cleanup");
    }
        

    
    @Test
    @DisplayName("Should handle bulk operations without concurrent modification")
    void shouldHandleBulkOperationsWithoutConcurrentModification() {
        // Add many subscribers
        List<Subscription> subscriptions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            subscriptions.add(eventBus.addSubscriber(TestEvent.class, e -> {}));
            subscriptions.add(eventBus.addSubscriber(StringEvent.class, e -> {}));
            subscriptions.add(eventBus.addSubscriber(IntegerEvent.class, e -> {}));
        }
        
        assertEquals(300, threadUnsafeEventBus.getTotalSubscriberCount());
        
        // Clear all subscribers - should not throw ConcurrentModificationException
        assertDoesNotThrow(() -> threadUnsafeEventBus.clearAllSubscribers());
        
        // Verify all subscriptions are inactive
        for (Subscription sub : subscriptions) {
            assertFalse(sub.isActive());
        }
        
        assertEquals(0, threadUnsafeEventBus.getTotalSubscriberCount());
    }
    
    
    
    @Test
    @DisplayName("Should not count events when EventBus is closed")
    void shouldNotCountEventsWhenClosed() {
        eventBus.addSubscriber(TestEvent.class, e -> {});
        long initialCount = threadUnsafeEventBus.getTotalEventsPublished();
        
        // Close the EventBus
        threadUnsafeEventBus.close();
        
        // Try to publish (should throw IllegalStateException)
        assertThrows(IllegalStateException.class, () -> {
            eventBus.publishEvent(new TestEvent("test"));
        });
        
        // Count should not have changed
        assertEquals(initialCount, threadUnsafeEventBus.getTotalEventsPublished(),
            "Should not increment counter when EventBus is closed");
    }

    
    @Test
    @DisplayName("Should handle multiple filtered subscribers with overlapping criteria")
    void shouldHandleOverlappingFilters() {
        List<String> evenResults = new ArrayList<>();
        List<String> divisibleByThree = new ArrayList<>();
        List<String> primeResults = new ArrayList<>();
        List<String> allResults = new ArrayList<>();
        
        // Even numbers filter
        eventBus.addSubscriberForFilteredEvents(
            IntegerEvent.class,
            event -> event.value % 2 == 0,
            event -> evenResults.add("even:" + event.value)
        );
        
        // Divisible by 3 filter
        eventBus.addSubscriberForFilteredEvents(
            IntegerEvent.class,
            event -> event.value % 3 == 0,
            event -> divisibleByThree.add("div3:" + event.value)
        );
        
        // Prime numbers filter (simplified: 2, 3, 5, 7)
        Set<Integer> primes = Set.of(2, 3, 5, 7);
        eventBus.addSubscriberForFilteredEvents(
            IntegerEvent.class,
            event -> primes.contains(event.value),
            event -> primeResults.add("prime:" + event.value)
        );
        
        // Accept all filter
        eventBus.addSubscriberForFilteredEvents(
            IntegerEvent.class,
            event -> true,
            event -> allResults.add("all:" + event.value)
        );
        
        // Publish numbers 1-10
        for (int i = 1; i <= 10; i++) {
            eventBus.publishEvent(new IntegerEvent(i));
        }
        
        // Verify each filter worked independently
        assertThat(evenResults).containsExactly("even:2", "even:4", "even:6", "even:8", "even:10");
        assertThat(divisibleByThree).containsExactly("div3:3", "div3:6", "div3:9");
        assertThat(primeResults).containsExactly("prime:2", "prime:3", "prime:5", "prime:7");
        assertEquals(10, allResults.size());
        
        // Number 6 should appear in both even and divisibleByThree
        assertTrue(evenResults.contains("even:6"));
        assertTrue(divisibleByThree.contains("div3:6"));
        
        // Number 2 should appear in even, prime, and all
        assertTrue(evenResults.contains("even:2"));
        assertTrue(primeResults.contains("prime:2"));
        assertTrue(allResults.contains("all:2"));
    }
    
    @Test
    @DisplayName("Should handle subscriber that adds new subscribers during event")
    void shouldHandleSubscriberAddingNewSubscribers() {
        List<String> executionOrder = new ArrayList<>();
        AtomicInteger cascadeCount = new AtomicInteger(0);
        
        // First subscriber that adds more subscribers during event processing
        eventBus.addSubscriber(TestEvent.class, event -> {
            executionOrder.add("original");
            
            // Add a new subscriber during event processing
            if (cascadeCount.get() < 3) { // Limit cascade depth
                int currentCascade = cascadeCount.incrementAndGet();
                eventBus.addSubscriber(TestEvent.class, e -> {
                    executionOrder.add("cascade-" + currentCascade);
                });
            }
        });
        
        // Publish first event - only original subscriber exists
        eventBus.publishEvent(new TestEvent("first"));
        assertThat(executionOrder).containsExactly("original");
        
        // Clear for next event
        executionOrder.clear();
        
        // Publish second event - original + first cascade subscriber
        eventBus.publishEvent(new TestEvent("second"));
        assertThat(executionOrder).containsExactly("original", "cascade-1");
        
        // Clear for next event
        executionOrder.clear();
        
        // Publish third event - original + two cascade subscribers
        eventBus.publishEvent(new TestEvent("third"));
        assertThat(executionOrder).containsExactly("original", "cascade-1", "cascade-2");
        
        // Clear for next event
        executionOrder.clear();
        
        // Publish fourth event - original + three cascade subscribers (max reached)
        eventBus.publishEvent(new TestEvent("fourth"));
        assertThat(executionOrder).containsExactly("original", "cascade-1", "cascade-2", "cascade-3");
        
        // Verify total subscriber count
        assertEquals(4, threadUnsafeEventBus.getSubscriberCount(TestEvent.class));
    }
    
    
    
}
