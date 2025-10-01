package com.hsbc.eventbus;

import java.util.*;


/**
 * Thread-unsafe EventBus implementation optimized for non-concurrent environments.
 * 
 * <p>This implementation uses simple, non-thread-safe data structures for maximum
 * performance in single-threaded scenarios. It's ideal for:
 * <ul>
 *   <li>UI event handling in single-threaded GUI frameworks</li>
 *   <li>Sequential processing pipelines</li>
 *   <li>Test environments</li>
 *   <li>Scenarios where external synchronization is provided</li>
 * </ul>
 * 
 * <p><b>Thread Safety:</b> This implementation is NOT thread-safe. All access must be
 * from a single thread or externally synchronized. For concurrent access, use
 * {@link ThreadSafeEventBus} instead.
 * 
 * <p><b>Performance Characteristics:</b>
 * <ul>
 *   <li>Publishing events: O(n) where n is the number of subscribers</li>
 *   <li>Adding subscribers: O(1) amortized</li>
 *   <li>Removing subscribers: O(n) where n is the number of subscribers for that event type</li>
 *   <li>No synchronization overhead</li>
 * </ul>
 * 
 * <p>Usage example:
 * <pre>{@code
 * ThreadUnsafeEventBus eventBus = new ThreadUnsafeEventBus();
 * 
 * // All calls must be from the same thread
 * eventBus.addSubscriber(OrderEvent.class, order -> processOrder(order));
 * eventBus.publishEvent(new OrderEvent(...));
 * }</pre>
 * 
 * @see AbstractEventBus
 * @see ThreadSafeEventBus
 */
public class ThreadUnsafeEventBus extends AbstractEventBus {
    
    /**
     * Simple HashMap for single-threaded access.
     * No synchronization needed as all access is from one thread.
     */
    private final Map<Class<?>, List<SubscriberWrapper<?>>> subscribers = new HashMap<>();
    
    // Metrics - simple primitives since we're single-threaded
    private long totalEventsPublished = 0;
    private long deadEventCount = 0;
    
    // Flag to prevent concurrent modification during bulk operations
    private boolean isBulkOperationInProgress = false;
    
    /**
     * Creates a new ThreadUnsafeEventBus with default configuration.
     */
    public ThreadUnsafeEventBus() {
        super();
    }
    
    /**
     * Creates a new ThreadUnsafeEventBus with custom configuration.
     * 
     * @param handleDeadEvents if true, publishes DeadEvent when an event has no subscribers
     */
    public ThreadUnsafeEventBus(boolean handleDeadEvents) {
        super(handleDeadEvents);
    }
    
    // Override abstract methods to provide single-threaded implementations
    
    @Override
    protected List<SubscriberWrapper<?>> getSubscriberList(Class<?> eventClass) {
        return subscribers.get(eventClass);
    }
    
    @Override
    protected List<SubscriberWrapper<?>> createSubscriberList() {
        // Simple ArrayList for single-threaded use
        return new ArrayList<>();
    }
    
    @Override
    protected List<SubscriberWrapper<?>> getSubscribersSnapshot(Class<?> eventClass) {
        List<SubscriberWrapper<?>> list = subscribers.get(eventClass);
        if (list == null || list.isEmpty()) {
            return null;
        }
        // Create a defensive copy to avoid ConcurrentModificationException
        // if subscribers modify the list during event handling
        return new ArrayList<>(list);
    }
    
    @Override
    protected void addSubscriberToList(Class<?> eventClass, SubscriberWrapper<?> wrapper) {
        subscribers.computeIfAbsent(eventClass, k -> createSubscriberList()).add(wrapper);
    }
    
    @Override
    protected void removeSubscriberFromList(Class<?> eventClass, SubscriberWrapper<?> wrapper) {
        if (isBulkOperationInProgress) {
            // During bulk operations, just mark as inactive
            return;
        }
        
        List<SubscriberWrapper<?>> list = subscribers.get(eventClass);
        if (list != null) {
            list.remove(wrapper);
            if (list.isEmpty()) {
                subscribers.remove(eventClass);
            }
        }
    }
    
    @Override
    protected List<SubscriberWrapper<?>> removeAllSubscribersForClass(Class<?> eventClass) {
        isBulkOperationInProgress = true;
        try {
            return subscribers.remove(eventClass);
        } finally {
            isBulkOperationInProgress = false;
        }
    }
    
    @Override
    protected Collection<List<SubscriberWrapper<?>>> clearAllSubscriberLists() {
        isBulkOperationInProgress = true;
        try {
            Collection<List<SubscriberWrapper<?>>> allLists = new ArrayList<>(subscribers.values());
            subscribers.clear();
            return allLists;
        } finally {
            isBulkOperationInProgress = false;
        }
    }
    
    @Override
    protected int getSubscriberCountForClass(Class<?> eventClass) {
        List<SubscriberWrapper<?>> list = subscribers.get(eventClass);
        if (list == null) return 0;
        return (int) list.stream().filter(SubscriberWrapper::isActive).count();
    }
    
    @Override
    protected int getTotalSubscriberCountInternal() {
        return subscribers.values().stream()
            .mapToInt(list -> (int) list.stream().filter(SubscriberWrapper::isActive).count())
            .sum();
    }
    
    @Override
    protected void incrementMetric(MetricType metric) {
        switch (metric) {
            case TOTAL_EVENTS_PUBLISHED:
                totalEventsPublished++;
                break;
            case DEAD_EVENT_COUNT:
                deadEventCount++;
                break;
        }
    }
    
    @Override
    protected long getMetricValue(MetricType metric) {
        switch (metric) {
            case TOTAL_EVENTS_PUBLISHED:
                return totalEventsPublished;
            case DEAD_EVENT_COUNT:
                return deadEventCount;
            default:
                return 0;
        }
    }

}
