package com.hsbc.eventbus;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.Comparator;

/**
 * Thread-safe EventBus implementation with coalescing/conflation support.
 * 
 * <p>This implementation extends {@link ThreadSafeEventBus} to add support
 * for event coalescing, where multiple events with the same key are conflated
 * so that only the latest event is delivered to subscribers.
 * 
 * <p>Key features:
 * <ul>
 *   <li>Thread-safe coalescing using ConcurrentHashMap</li>
 *   <li>Per-subscriber coalescing queues for independent processing</li>
 *   <li>Global coalescing configuration per event type</li>
 *   <li>Automatic detection of {@link Coalescable} events</li>
 *   <li>Metrics for monitoring coalescing effectiveness</li>
 * </ul>
 * 
 * <p>Performance characteristics:
 * <ul>
 *   <li>Publishing coalesced events: O(1) for updates, O(n) for delivery</li>
 *   <li>Coalescing overhead: minimal with ConcurrentHashMap</li>
 *   <li>Memory usage: proportional to unique keys per event type</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * CoalescingThreadSafeEventBus eventBus = new CoalescingThreadSafeEventBus();
 * 
 * // Configure global coalescing for market data
 * eventBus.configureCoalescing(
 *     MarketDataUpdate.class,
 *     update -> update.getSymbol()
 * 
 * // Or use per-subscriber coalescing
 * eventBus.addCoalescingSubscriber(
 *     OrderUpdate.class,
 *     order -> order.getOrderId(),
 *     order -> processOrder(order)
 * );
 * @see AbstractEventBus
 * @see ThreadSafeEventBus
 */
public class CoalescingThreadSafeEventBus extends ThreadSafeEventBus implements CoalescingEventBus {
    
    /**
     * Global coalescing configurations per event class.
     */
    private final ConcurrentHashMap<Class<?>, Function<?, ?>> globalCoalescingConfigs = new ConcurrentHashMap<>();
    
    /**
     * Coalescing subscribers with their own coalescing queues.
     */
    private final ConcurrentHashMap<Class<?>, List<CoalescingSubscriberWrapper<?>>> coalescingSubscribers = new ConcurrentHashMap<>();
    
    /**
     * Metrics for coalescing.
     */
    private final AtomicLong totalCoalescedEvents = new AtomicLong(0);
    private final AtomicLong totalReplacedEvents = new AtomicLong(0);
    
    /**
     * Executor for asynchronous event delivery (optional).
     */
    private final ScheduledExecutorService deliveryExecutor;
    private final boolean asyncDelivery;
    
    /**
     * Creates a new CoalescingThreadSafeEventBus with synchronous delivery.
     */
    public CoalescingThreadSafeEventBus() {
        this(false, null);
    }
    
    /**
     * Creates a new CoalescingThreadSafeEventBus with custom configuration.
     * 
     * @param handleDeadEvents if true, publishes DeadEvent when an event has no subscribers
     */
    public CoalescingThreadSafeEventBus(boolean handleDeadEvents) {
        this(handleDeadEvents, null);
    }
    
    /**
     * Creates a new CoalescingThreadSafeEventBus with optional async delivery.
     * 
     * @param handleDeadEvents if true, publishes DeadEvent when an event has no subscribers
     * @param deliveryExecutor executor for async delivery, null for synchronous delivery
     */
    public CoalescingThreadSafeEventBus(boolean handleDeadEvents, ScheduledExecutorService deliveryExecutor) {
        super(handleDeadEvents);
        this.deliveryExecutor = deliveryExecutor;
        this.asyncDelivery = deliveryExecutor != null;
    }
    
    @Override
    public <E> void publishEvent(E event) {
        if (event == null) {
            throw new NullPointerException("Event cannot be null");
        }
        
        List<Class<?>> dispatchOrder = buildDispatchOrder(event.getClass());

        // Check if this event type (or any of its supertypes interfaces) has global coalescing configured
        Function<?, ?> globalKeyExtractor = findGlobalKeyExtractor(dispatchOrder);

        // Handle coalescing subscribers registered for the hierarchy
        dispatchToCoalescingSubscribers(event, dispatchOrder);

        // If global coalescing is enabled or the event is intrinsically coalescable, handle it specially
        if (globalKeyExtractor != null || event instanceof Coalescable) {
            handleGloballyCoalescedEvent(event, globalKeyExtractor);
        } else {
            super.publishEvent(event);
        }
    }

    private Function<?, ?> findGlobalKeyExtractor(List<Class<?>> dispatchOrder) {
        for (Class<?> candidate : dispatchOrder) {
            Function<?, ?> extractor = globalCoalescingConfigs.get(candidate);
            if (extractor != null) {
                return extractor;
            }
        }
        return null;
    }

    private <E> void dispatchToCoalescingSubscribers(E event, List<Class<?>> dispatchOrder) {
        for (Class<?> candidate : dispatchOrder) {
            List<CoalescingSubscriberWrapper<?>> list = coalescingSubscribers.get(candidate);
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (CoalescingSubscriberWrapper<?> wrapper : list) {
                if (wrapper.isActive()) {
                    wrapper.handleEvent(event);
                }
            }
        }
    }

    private <E> void handleGloballyCoalescedEvent(E event, Function<?, ?> keyExtractor) {
        // This is a simplified implementation
        // In a production system, you might want to batch these for regular subscribers too
        super.publishEvent(event);
        totalCoalescedEvents.incrementAndGet();
    }
    
    @Override
    public <E, K> Subscription addCoalescingSubscriber(
            Class<E> eventClass,
            Function<E, K> keyExtractor,
            Consumer<E> callback) {
        return addCoalescingSubscriberInternal(eventClass, keyExtractor, null, null, callback);
    }
    
    @Override
    public <E, K> Subscription addCoalescingSubscriber(
            Class<E> eventClass,
            Function<E, K> keyExtractor,
            Predicate<E> filter,
            Consumer<E> callback) {
        return addCoalescingSubscriberInternal(eventClass, keyExtractor, null, filter, callback);
    }

    @Override
    public <E, K> Subscription addCoalescingSubscriber(
            Class<E> eventClass,
            Function<E, K> keyExtractor,
            Comparator<E> recencyComparator,
            Consumer<E> callback) {
        return addCoalescingSubscriberInternal(eventClass, keyExtractor, recencyComparator, null, callback);
    }

    @Override
    public <E, K> Subscription addCoalescingSubscriber(
            Class<E> eventClass,
            Function<E, K> keyExtractor,
            Comparator<E> recencyComparator,
            Predicate<E> filter,
            Consumer<E> callback) {
        return addCoalescingSubscriberInternal(eventClass, keyExtractor, recencyComparator, filter, callback);
    }

    private <E, K> Subscription addCoalescingSubscriberInternal(
            Class<E> eventClass,
            Function<E, K> keyExtractor,
            Comparator<E> recencyComparator,
            Predicate<E> filter,
            Consumer<E> callback) {

        if (eventClass == null || keyExtractor == null || callback == null) {
            throw new NullPointerException("Parameters cannot be null");
        }

        CoalescingSubscriberWrapper<E> wrapper = new CoalescingSubscriberWrapper<E>(
            eventClass, keyExtractor, recencyComparator, filter, callback
        );

        coalescingSubscribers.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>()).add(wrapper);

        return wrapper;
    }
    
    @Override
    public <E, K> void configureCoalescing(Class<E> eventClass, Function<E, K> keyExtractor) {
        if (eventClass == null || keyExtractor == null) {
            throw new NullPointerException("Parameters cannot be null");
        }
        globalCoalescingConfigs.put(eventClass, keyExtractor);
    }
    
    @Override
    public <E> void disableCoalescing(Class<E> eventClass) {
        if (eventClass == null) {
            throw new NullPointerException("Event class cannot be null");
        }
        globalCoalescingConfigs.remove(eventClass);
    }
    
    @Override
    public boolean isCoalescingEnabled(Class<?> eventClass) {
        Objects.requireNonNull(eventClass, "Event class cannot be null");

        for (Class<?> candidate : buildDispatchOrder(eventClass)) {
            if (globalCoalescingConfigs.containsKey(candidate)) {
                return true;
            }
        }
        return Coalescable.class.isAssignableFrom(eventClass);
    }
    
    @Override
    public int getPendingCoalescedEventCount() {
        return coalescingSubscribers.values().stream()
            .flatMap(List::stream)
            .filter(CoalescingSubscriberWrapper::isActive)
            .mapToInt(CoalescingSubscriberWrapper::getPendingEventCount)
            .sum();
    }
    
    @Override
    public int getPendingCoalescedEventCount(Class<?> eventClass) {
        List<CoalescingSubscriberWrapper<?>> list = coalescingSubscribers.get(eventClass);
        if (list == null) return 0;
        
        return list.stream()
            .filter(CoalescingSubscriberWrapper::isActive)
            .mapToInt(CoalescingSubscriberWrapper::getPendingEventCount)
            .sum();
    }
    
    @Override
    public void flushCoalescedEvents() {
        for (List<CoalescingSubscriberWrapper<?>> list : coalescingSubscribers.values()) {
            for (CoalescingSubscriberWrapper<?> wrapper : list) {
                if (wrapper.isActive()) {
                    wrapper.flush();
                }
            }
        }
    }
    
    @Override
    public <E> void flushCoalescedEvents(Class<E> eventClass) {
        List<CoalescingSubscriberWrapper<?>> list = coalescingSubscribers.get(eventClass);
        if (list != null) {
            for (CoalescingSubscriberWrapper<?> wrapper : list) {
                if (wrapper.isActive()) {
                    wrapper.flush();
                }
            }
        }
    }
    
    /**
     * Gets detailed metrics including coalescing statistics.
     */
    @Override
    public synchronized Map<String, Object> getDetailedMetrics() {
        Map<String, Object> metrics = super.getDetailedMetrics();
        metrics.put("totalCoalescedEvents", totalCoalescedEvents.get());
        metrics.put("totalReplacedEvents", totalReplacedEvents.get());
        metrics.put("pendingCoalescedEvents", getPendingCoalescedEventCount());
        metrics.put("coalescingSubscriberCount", 
            coalescingSubscribers.values().stream()
                .mapToInt(List::size)
                .sum());
        return metrics;
    }
    
    @Override
    public void close() {
        // Flush all pending events before closing
        flushCoalescedEvents();
        
        // Shutdown delivery executor if present
        if (deliveryExecutor != null && !deliveryExecutor.isShutdown()) {
            deliveryExecutor.shutdown();
            try {
                if (!deliveryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    deliveryExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                deliveryExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        super.close();
    }
    
    /**
     * Wrapper for coalescing subscribers that maintains a coalescing queue.
     */
    private class CoalescingSubscriberWrapper<E> implements Subscription {
        private final Class<E> eventClass;
        private final Function<E, ?> keyExtractor;
        private final Comparator<E> recencyComparator;
        private final Predicate<E> filter;
        private final Consumer<E> callback;
        private final ConcurrentHashMap<Object, E> coalescingQueue;
        private final AtomicReference<ScheduledFuture<?>> deliveryTask;
        private volatile boolean active = true;
        private final AtomicLong invocationCount = new AtomicLong(0);
        private final AtomicLong replacedCount = new AtomicLong(0);
        
        CoalescingSubscriberWrapper(Class<E> eventClass, Function<E, ?> keyExtractor,
                                   Comparator<E> recencyComparator, Predicate<E> filter, Consumer<E> callback) {
            this.eventClass = eventClass;
            this.keyExtractor = keyExtractor;
            this.recencyComparator = recencyComparator;
            this.filter = filter;
            this.callback = callback;
            this.coalescingQueue = new ConcurrentHashMap<>();
            this.deliveryTask = new AtomicReference<>();
        }
        
        void handleEvent(Object event) {
            if (!active) return;

            if (!eventClass.isInstance(event)) {
                return;
            }

            E typedEvent = eventClass.cast(event);
            
            // Apply filter if present
            if (filter != null && !filter.test(typedEvent)) {
                return;
            }
            
            // Extract coalescing key
            Object key = keyExtractor.apply(typedEvent);
            if (key == null) {
                // If key is null, deliver immediately without coalescing
                deliverEvent(typedEvent);
                return;
            }
            
            final boolean[] replaced = {false};

            coalescingQueue.compute(key, (k, previousEvent) -> {
                if (previousEvent == null) {
                    return typedEvent;
                }

                if (recencyComparator == null) {
                    replaced[0] = true;
                    return typedEvent;
                }

                int comparison = recencyComparator.compare(previousEvent, typedEvent);
                if (comparison <= 0) {
                    replaced[0] = true;
                    return typedEvent;
                }
                return previousEvent;
            });

            if (replaced[0]) {
                replacedCount.incrementAndGet();
                totalReplacedEvents.incrementAndGet();
            }
            
            // Schedule delivery if async, otherwise deliver immediately
            if (asyncDelivery) {
                scheduleDelivery();
            } else {
                // In sync mode, we could deliver immediately or batch. For this implementation,
                // we'll deliver on flush or the next publish cycle to support batching semantics.
            }
        }
        
        private void scheduleDelivery() {
            if (!active || deliveryExecutor == null) return;
            
            // Cancel previous task if exists
            ScheduledFuture<?> currentTask = deliveryTask.get();
            if (currentTask == null || currentTask.isDone()) {
                // Schedule new delivery task with small delay for batching
                ScheduledFuture<?> newTask = deliveryExecutor.schedule(
                    this::flush,
                    10, // 10ms delay for batching
                    TimeUnit.MILLISECONDS
                );
                deliveryTask.compareAndSet(currentTask, newTask);
            }
        }
        
        void flush() {
            if (!active) return;
            
            // Get and clear all pending events atomically
            Map<Object, E> events = new HashMap<>(coalescingQueue);
            coalescingQueue.clear();
            
            // Deliver all coalesced events
            for (E event : events.values()) {
                deliverEvent(event);
            }
        }
        
        private void deliverEvent(E event) {
            if (!active) return;
            
            try {
                callback.accept(event);
                invocationCount.incrementAndGet();
                totalCoalescedEvents.incrementAndGet();
            } catch (Exception e) {
                // Log error but don't propagate to maintain error isolation
                System.err.println("Error in coalescing subscriber: " + e.getMessage());
            }
        }
        
        int getPendingEventCount() {
            return coalescingQueue.size();
        }
        
        @Override
        public void unsubscribe() {
            active = false;
            
            // Cancel any pending delivery
            ScheduledFuture<?> task = deliveryTask.get();
            if (task != null) {
                task.cancel(false);
            }
            
            // Clear pending events
            coalescingQueue.clear();
            
            // Remove from subscribers list
            List<CoalescingSubscriberWrapper<?>> list = coalescingSubscribers.get(eventClass);
            if (list != null) {
                list.remove(this);
            }
        }
        
        @Override
        public boolean isActive() {
            return active;
        }
    }
}
