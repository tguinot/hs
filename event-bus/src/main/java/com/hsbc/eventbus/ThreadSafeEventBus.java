package com.hsbc.eventbus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe EventBus implementation suitable for concurrent environments.
 * 
 * <p>This implementation uses:
 * <ul>
 *   <li>ConcurrentHashMap for the subscriber registry</li>
 *   <li>CopyOnWriteArrayList for subscriber lists (optimized for many reads, few writes)</li>
 *   <li>AtomicLong for metrics counters</li>
 *   <li>ThreadLocal for recursion depth tracking</li>
 * </ul>
 * 
 * <p>Thread-safety guarantees:
 * <ul>
 *   <li>All public methods are thread-safe</li>
 *   <li>Events can be published concurrently from multiple threads</li>
 *   <li>Subscribers can be added/removed concurrently</li>
 *   <li>Subscriber callbacks are invoked on the publishing thread</li>
 * </ul>
 * 
 * <p>Performance characteristics:
 * <ul>
 *   <li>Publishing events: O(n) where n is the number of subscribers</li>
 *   <li>Removing subscribers: O(n) where n is the number of subscribers for that event type</li>
 * </ul>
 * 
 * <p>Usage example:
 * <pre>{@code
 * ThreadSafeEventBus eventBus = new ThreadSafeEventBus();
 * 
 * // Can be called from multiple threads safely
 * eventBus.addSubscriber(OrderEvent.class, order -> processOrder(order));
 * 
 * // Can publish from any thread
 * eventBus.publishEvent(new OrderEvent(...));
 * }</pre>
 * 
 * @see AbstractEventBus
 * @see ThreadUnsafeEventBus
 */
public class ThreadSafeEventBus extends AbstractEventBus {
    
    /**
     * Thread-safe map of event classes to their subscribers.
     * Uses ConcurrentHashMap for lock-free reads and fine-grained locking on writes.
     */
    private final ConcurrentHashMap<Class<?>, List<SubscriberWrapper<?>>> subscribers = new ConcurrentHashMap<>();
    
    /**
     * Thread-safe metrics counters.
     */
    private final AtomicLong totalEventsPublished = new AtomicLong(0);
    private final AtomicLong deadEventCount = new AtomicLong(0);
    
    /**
     * Creates a new ThreadSafeEventBus with default configuration.
     */
    public ThreadSafeEventBus() {
        super();
    }
    
    /**
     * Creates a new ThreadSafeEventBus with custom configuration.
     * 
     * @param handleDeadEvents if true, publishes DeadEvent when an event has no subscribers
     */
    public ThreadSafeEventBus(boolean handleDeadEvents) {
        super(handleDeadEvents);
    }
    
    @Override
    protected List<SubscriberWrapper<?>> getSubscriberList(Class<?> eventClass) {
        return subscribers.get(eventClass);
    }
    
    @Override
    protected List<SubscriberWrapper<?>> createSubscriberList() {
        // CopyOnWriteArrayList is ideal for event bus scenarios:
        // - Many reads (publishing events)
        // - Few writes (adding/removing subscribers)
        // - Thread-safe iteration without explicit synchronization
        return new CopyOnWriteArrayList<>();
    }
    
    @Override
    protected List<SubscriberWrapper<?>> getSubscribersSnapshot(Class<?> eventClass) {
        List<SubscriberWrapper<?>> list = subscribers.get(eventClass);
        // CopyOnWriteArrayList already provides a snapshot for iteration,
        // so we can return it directly
        return list;
    }
    
    @Override
    protected void addSubscriberToList(Class<?> eventClass, SubscriberWrapper<?> wrapper) {
        // computeIfAbsent is atomic - ensures only one thread creates the list
        subscribers.computeIfAbsent(eventClass, k -> createSubscriberList()).add(wrapper);
    }
    
    @Override
    protected void removeSubscriberFromList(Class<?> eventClass, SubscriberWrapper<?> wrapper) {
        List<SubscriberWrapper<?>> list = subscribers.get(eventClass);
        if (list != null) {
            list.remove(wrapper);
            // Atomically remove the list if it's empty to prevent a race condition
            // where a new subscriber is added after the isEmpty() check but before the remove().
            subscribers.computeIfPresent(eventClass, (key, currentList) -> 
                currentList.isEmpty() ? null : currentList);
        }
    }
    
    @Override
    protected List<SubscriberWrapper<?>> removeAllSubscribersForClass(Class<?> eventClass) {
        return subscribers.remove(eventClass);
    }
    
    @Override
    protected Collection<List<SubscriberWrapper<?>>> clearAllSubscriberLists() {
        // Get all values before clearing
        Collection<List<SubscriberWrapper<?>>> allLists = new ArrayList<>(subscribers.values());
        subscribers.clear();
        return allLists;
    }
    
    @Override
    protected int getSubscriberCountForClass(Class<?> eventClass) {
        List<SubscriberWrapper<?>> list = subscribers.get(eventClass);
        if (list == null) return 0;
        // Count only active subscribers
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
                totalEventsPublished.incrementAndGet();
                break;
            case DEAD_EVENT_COUNT:
                deadEventCount.incrementAndGet();
                break;
        }
    }
    
    @Override
    protected long getMetricValue(MetricType metric) {
        switch (metric) {
            case TOTAL_EVENTS_PUBLISHED:
                return totalEventsPublished.get();
            case DEAD_EVENT_COUNT:
                return deadEventCount.get();
            default:
                return 0;
        }
    }
    
    /**
     * Gets a snapshot of all event classes that have subscribers.
     * This method is thread-safe and returns a new set.
     * 
     * @return a set of event classes with active subscribers
     */
    public Set<Class<?>> getEventClassesWithSubscribers() {
        return subscribers.entrySet().stream()
            .filter(entry -> entry.getValue().stream().anyMatch(SubscriberWrapper::isActive))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }
    
    /**
     * Gets detailed metrics about the event bus state.
     * This method is thread-safe and provides a consistent snapshot.
     * 
     * @return a map of metric names to their values
     */
    public synchronized Map<String, Object> getDetailedMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalEventsPublished", getTotalEventsPublished());
        metrics.put("deadEventCount", getDeadEventCount());
        metrics.put("totalSubscriberCount", getTotalSubscriberCount());
        metrics.put("eventClassCount", subscribers.size());
        
        // Add per-class subscriber counts
        Map<String, Integer> perClassCounts = new HashMap<>();
        for (Map.Entry<Class<?>, List<SubscriberWrapper<?>>> entry : subscribers.entrySet()) {
            int activeCount = (int) entry.getValue().stream()
                .filter(SubscriberWrapper::isActive)
                .count();
            if (activeCount > 0) {
                perClassCounts.put(entry.getKey().getSimpleName(), activeCount);
            }
        }
        metrics.put("subscribersPerClass", perClassCounts);
        
        return metrics;
    }
}
