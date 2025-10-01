package com.hsbc.eventbus;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for EventBus implementations providing shared functionality.
 * 
 * <p>This class contains the common logic for event publishing, subscriber management,
 * dead event handling, and metrics tracking. Concrete implementations must provide
 * thread-safety guarantees appropriate to their use case.
 * 
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #getSubscriberList(Class)} - to provide the appropriate collection type</li>
 *   <li>{@link #createSubscriberList()} - to create new subscriber lists</li>
 *   <li>{@link #getSubscribersSnapshot(Class)} - to provide a safe iteration snapshot</li>
 *   <li>{@link #incrementMetric(MetricType)} - for thread-safe metric updates</li>
 *   <li>{@link #getMetricValue(MetricType)} - for thread-safe metric reads</li>
 * </ul>
 */
public abstract class AbstractEventBus implements EventBus, AutoCloseable {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractEventBus.class);
    
    /**
     * Types of metrics tracked by the event bus.
     */
    protected enum MetricType {
        TOTAL_EVENTS_PUBLISHED,
        DEAD_EVENT_COUNT
    }

    /**
     * Resolves all subscribers that should handle the given event type, including those registered
     * for any of its superclasses or interfaces. The returned list preserves registration order
     * with more specific types being invoked before their supertypes.
     *
     * @param eventClass the concrete event type being published
     * @return ordered list of subscriber wrappers to invoke (never {@code null})
     */
    private List<SubscriberWrapper<?>> resolveSubscribers(Class<?> eventClass) {
        Objects.requireNonNull(eventClass, "Event class must not be null");

        LinkedHashSet<SubscriberWrapper<?>> resolved = new LinkedHashSet<>();
        for (Class<?> candidate : buildDispatchOrder(eventClass)) {
            List<SubscriberWrapper<?>> snapshot = getSubscribersSnapshot(candidate);
            if (snapshot == null || snapshot.isEmpty()) {
                continue;
            }
            resolved.addAll(snapshot);
        }

        if (resolved.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(resolved);
    }

    /**
     * Builds the dispatch order for a given event type. This includes the event's concrete class,
     * its interfaces (depth-first), and its superclass hierarchy up to {@link Object}.
     *
     * @param eventClass the concrete event type being published
     * @return ordered list of classes to inspect for subscribers
     */
    protected final List<Class<?>> buildDispatchOrder(Class<?> eventClass) {
        Objects.requireNonNull(eventClass, "Event class must not be null");

        LinkedHashSet<Class<?>> order = new LinkedHashSet<>();
        Class<?> current = eventClass;
        while (current != null) {
            if (order.add(current)) {
                addInterfacesInDeclarationOrder(current.getInterfaces(), order);
            }
            current = current.getSuperclass();
        }
        return new ArrayList<>(order);
    }

    private void addInterfacesInDeclarationOrder(Class<?>[] interfaces, LinkedHashSet<Class<?>> accumulator) {
        for (Class<?> iface : interfaces) {
            if (accumulator.add(iface)) {
                addInterfacesInDeclarationOrder(iface.getInterfaces(), accumulator);
            }
        }
    }
    
    /**
     * Subscriber wrapper that includes metadata and error handling.
     * This class is thread-safe as all mutable state is either volatile or synchronized.
     */
    protected class SubscriberWrapper<E> implements Subscription {
        private final Class<E> eventClass;
        private final Consumer<E> callback;
        private final Predicate<E> filter;
        private final String identifier;
        private volatile boolean active = true;
        private volatile long invocationCount = 0;
        private volatile long errorCount = 0;
        
        protected SubscriberWrapper(Class<E> eventClass, Consumer<E> callback, Predicate<E> filter, String identifier) {
            this.eventClass = Objects.requireNonNull(eventClass);
            this.callback = Objects.requireNonNull(callback);
            this.filter = filter; // can be null
            this.identifier = identifier;
        }
        
        protected void invoke(E event) {
            // Check active state before doing anything
            if (!active) return;
            
            // Increment invocation count atomically for thread-safety
            invocationCount++;
            
            try {
                if (filter == null || filter.test(event)) {
                    callback.accept(event);
                }
            } catch (RuntimeException e) {
                errorCount++;
                LOGGER.warn("Subscriber '{}' threw exception while handling event of type {}", 
                    identifier, event.getClass().getName(), e);
                // Swallow RuntimeException to isolate subscriber errors
            } catch (Error e) {
                errorCount++;
                LOGGER.error("Subscriber '{}' threw Error while handling event of type {}", 
                    identifier, event.getClass().getName(), e);
                throw e;  // Propagate Errors - these are serious JVM issues
            }
        }
        
        /**
         * Type-safe invocation that validates the event type before casting.
         */
        protected boolean tryInvoke(Object event) {
            if (!active) return false;
            
            // Type check before casting - defensive programming
            if (!eventClass.isInstance(event)) {
                LOGGER.debug("Event type mismatch: expected {}, got {}", 
                    eventClass.getName(), event.getClass().getName());
                return false;
            }
            
            // Use Class.cast() for cleaner type-safe casting
            E typedEvent = eventClass.cast(event);
            invoke(typedEvent);
            return true;
        }
        
        @Override
        public void unsubscribe() {
            // Only proceed if still active
            if (active) {
                active = false;
                // Delegate to the event bus to handle removal
                removeSubscriber(eventClass, this);
            }
        }
        
        @Override
        public boolean isActive() {
            return active;
        }
        
        public long getInvocationCount() {
            return invocationCount;
        }
        
        public long getErrorCount() {
            return errorCount;
        }
        
        public Class<E> getEventClass() {
            return eventClass;
        }
    }
    
    // Configuration options
    private final boolean handleDeadEvents;
    
    // Recursion depth counter to prevent infinite dead event recursion
    private final ThreadLocal<Integer> deadEventRecursionDepth = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_DEAD_EVENT_RECURSION_DEPTH = 2;
    
    // Track if we're currently processing a dead event to avoid silent failures
    private final ThreadLocal<Boolean> processingDeadEvent = ThreadLocal.withInitial(() -> false);
    
    // Track if the EventBus has been closed
    private volatile boolean closed = false;
    
    /**
     * Creates a new AbstractEventBus with default configuration.
     */
    protected AbstractEventBus() {
        this(true);
    }
    
    /**
     * Creates a new AbstractEventBus with custom configuration.
     * 
     * @param handleDeadEvents if true, publishes DeadEvent when an event has no subscribers
     */
    protected AbstractEventBus(boolean handleDeadEvents) {
        this.handleDeadEvents = handleDeadEvents;
    }
    
    /**
     * Gets the subscriber list for a given event class.
     * Implementations must provide appropriate thread-safety.
     * 
     * @param eventClass the event class
     * @return the list of subscribers, or null if none exist
     */
    protected abstract List<SubscriberWrapper<?>> getSubscriberList(Class<?> eventClass);
    
    /**
     * Creates a new subscriber list with appropriate thread-safety characteristics.
     * 
     * @return a new list for storing subscribers
     */
    protected abstract List<SubscriberWrapper<?>> createSubscriberList();
    
    /**
     * Gets a snapshot of subscribers for safe iteration.
     * This method must return a collection that can be safely iterated
     * even if the underlying subscriber list is modified concurrently.
     * 
     * @param eventClass the event class
     * @return a snapshot of subscribers for iteration, or null if none exist
     */
    protected abstract List<SubscriberWrapper<?>> getSubscribersSnapshot(Class<?> eventClass);
    
    /**
     * Adds a subscriber to the list for a given event class.
     * Implementations must provide appropriate thread-safety.
     * 
     * @param eventClass the event class
     * @param wrapper the subscriber wrapper to add
     */
    protected abstract void addSubscriberToList(Class<?> eventClass, SubscriberWrapper<?> wrapper);
    
    /**
     * Removes a subscriber from the list for a given event class.
     * Implementations must provide appropriate thread-safety.
     * 
     * @param eventClass the event class
     * @param wrapper the subscriber wrapper to remove
     */
    protected abstract void removeSubscriberFromList(Class<?> eventClass, SubscriberWrapper<?> wrapper);
    
    /**
     * Removes all subscribers for a specific event class.
     * Implementations must provide appropriate thread-safety.
     * 
     * @param eventClass the event class
     * @return the list of removed subscribers, or null if none existed
     */
    protected abstract List<SubscriberWrapper<?>> removeAllSubscribersForClass(Class<?> eventClass);
    
    /**
     * Clears all subscribers from all event classes.
     * Implementations must provide appropriate thread-safety.
     * 
     * @return a collection of all removed subscribers
     */
    protected abstract Collection<List<SubscriberWrapper<?>>> clearAllSubscriberLists();
    
    /**
     * Gets the count of subscribers for a specific event class.
     * 
     * @param eventClass the event class
     * @return the number of active subscribers
     */
    protected abstract int getSubscriberCountForClass(Class<?> eventClass);
    
    /**
     * Gets the total count of subscribers across all event classes.
     * 
     * @return the total number of active subscribers
     */
    protected abstract int getTotalSubscriberCountInternal();
    
    /**
     * Increments a metric in a thread-safe manner.
     * 
     * @param metric the metric to increment
     */
    protected abstract void incrementMetric(MetricType metric);
    
    /**
     * Gets the value of a metric in a thread-safe manner.
     * 
     * @param metric the metric to get
     * @return the current value of the metric
     */
    protected abstract long getMetricValue(MetricType metric);
    
    @Override
    public <E> void publishEvent(E event) {
        Objects.requireNonNull(event, "Event must not be null");
        ensureNotClosed();
        
        // Track if this is a top-level call for ThreadLocal cleanup
        boolean isTopLevelCall = deadEventRecursionDepth.get() == 0;
        
        try {
            // Increment counter only after all guards pass to avoid metrics drift
            incrementMetric(MetricType.TOTAL_EVENTS_PUBLISHED);
            
            // Resolve all subscribers that should handle this event, including supertypes/interfaces
            List<SubscriberWrapper<?>> callbacks = resolveSubscribers(event.getClass());
            boolean hasSubscribers = !callbacks.isEmpty();
            
            if (hasSubscribers) {
                for (SubscriberWrapper<?> wrapper : callbacks) {
                    // Use type-safe invocation that validates event type
                    wrapper.tryInvoke(event);
                }
            }
            
            // Handle dead events with recursion depth guard
            if (!hasSubscribers && handleDeadEvents && !(event instanceof DeadEvent)) {
                handleDeadEvent(event);
            }
        } finally {
            // Clean up ThreadLocal values when we're back at the top level
            // This prevents memory leaks in thread pool scenarios
            if (isTopLevelCall) {
                deadEventRecursionDepth.remove();
                processingDeadEvent.remove();
            }
        }
    }
    
    /**
     * Handles dead events with proper recursion control and error tracking.
     * 
     * @param event the original event that had no subscribers
     * @param <E> the type of the event
     */
    private <E> void handleDeadEvent(E event) {
        int currentDepth = deadEventRecursionDepth.get();
        
        // Check recursion depth to prevent stack overflow
        if (currentDepth >= MAX_DEAD_EVENT_RECURSION_DEPTH) {
            // Log that we've reached max recursion depth
            LOGGER.warn("Max dead event recursion depth ({}) reached for event: {}", 
                MAX_DEAD_EVENT_RECURSION_DEPTH, event.getClass().getName());
            incrementMetric(MetricType.DEAD_EVENT_COUNT);
            return;
        }
        
        deadEventRecursionDepth.set(currentDepth + 1);
        boolean deadEventHandled = false;
        
        try {
            incrementMetric(MetricType.DEAD_EVENT_COUNT);
            
            // Track that we're processing a dead event
            boolean wasProcessingDeadEvent = processingDeadEvent.get();
            processingDeadEvent.set(true);
            
            try {
                // Create DeadEvent first to catch any constructor errors
                DeadEvent deadEvent = new DeadEvent(event);
                
                // Always publish the DeadEvent - this ensures it gets counted
                publishEvent(deadEvent);
                deadEventHandled = true;
            } finally {
                processingDeadEvent.set(wasProcessingDeadEvent);
            }
        } catch (RuntimeException e) {
            // Log the error but don't propagate
            LOGGER.error("Failed to publish dead event for {}: {}", 
                event.getClass().getName(), e.getMessage(), e);
        } finally {
            deadEventRecursionDepth.set(currentDepth);
            
            // If we're back at recursion depth 0 and the dead event wasn't handled,
            // log a warning about the unhandled event
            if (currentDepth == 0 && !deadEventHandled && !processingDeadEvent.get()) {
                LOGGER.debug("Event {} had no subscribers and DeadEvent handling did not complete successfully", 
                    event.getClass().getName());
            }
            
            // Note: ThreadLocal cleanup is handled in publishEvent() when isTopLevelCall is true
        }
    }
    
    @Override
    public <E> Subscription addSubscriber(Class<E> clazz, Consumer<E> callback) {
        ensureNotClosed();
        return addSubscriberInternal(clazz, callback, null, null);
    }
    
    @Override
    public <E> Subscription addSubscriberForFilteredEvents(Class<E> clazz, 
                                                           Predicate<E> filter, 
                                                           Consumer<E> callback) {
        Objects.requireNonNull(filter, "Filter must not be null");
        ensureNotClosed();
        return addSubscriberInternal(clazz, callback, filter, null);
    }
    
    /**
     * Internal method to add a subscriber.
     * 
     * @param clazz the event class to subscribe to
     * @param callback the callback to invoke when an event is published
     * @param filter optional predicate to filter events (null for no filtering)
     * @param identifier optional identifier for debugging
     * @param <E> the type of the event
     * @return a Subscription that can be used to unsubscribe
     */
    private <E> Subscription addSubscriberInternal(Class<E> clazz, 
                                                   Consumer<E> callback,
                                                   Predicate<E> filter,
                                                   String identifier) {
        Objects.requireNonNull(clazz, "Event class must not be null");
        Objects.requireNonNull(callback, "Subscriber callback must not be null");
        
        // Validate and sanitize identifier
        String id = (identifier != null && !identifier.trim().isEmpty()) ? identifier.trim() : 
            String.format("%s@%s", callback.getClass().getSimpleName(), 
                         Integer.toHexString(callback.hashCode()));
        
        SubscriberWrapper<E> wrapper = new SubscriberWrapper<>(clazz, callback, filter, id);
        
        addSubscriberToList(clazz, wrapper);
        
        return wrapper;
    }
    
    /**
     * Internal helper method to remove a specific subscriber wrapper.
     * 
     * @param eventClass the event class the subscriber is registered for
     * @param wrapper the wrapper to remove
     */
    protected void removeSubscriber(Class<?> eventClass, SubscriberWrapper<?> wrapper) {
        removeSubscriberFromList(eventClass, wrapper);
    }
    
    /**
     * Removes all subscribers for a specific event class.
     * 
     * @param clazz the event class
     * @return the number of subscribers removed
     */
    public int removeAllSubscribers(Class<?> clazz) {
        ensureNotClosed();
        List<SubscriberWrapper<?>> removed = removeAllSubscribersForClass(clazz);
        if (removed != null) {
            // Mark all as inactive for any held Subscription references
            removed.forEach(SubscriberWrapper::unsubscribe);
            return removed.size();
        }
        return 0;
    }
    
    /**
     * Removes all subscribers from the event bus.
     * 
     * @return the total number of subscribers removed
     */
    public int clearAllSubscribers() {
        Collection<List<SubscriberWrapper<?>>> allLists = clearAllSubscriberLists();
        int count = 0;
        for (List<SubscriberWrapper<?>> list : allLists) {
            // Mark all as inactive
            list.forEach(SubscriberWrapper::unsubscribe);
            count += list.size();
        }
        return count;
    }
    
    // Metrics and debugging methods
    
    /**
     * Gets the total number of events published.
     * 
     * @return the event count
     */
    public long getTotalEventsPublished() {
        return getMetricValue(MetricType.TOTAL_EVENTS_PUBLISHED);
    }
    
    /**
     * Gets the number of dead events encountered.
     * 
     * @return the dead event count
     */
    public long getDeadEventCount() {
        return getMetricValue(MetricType.DEAD_EVENT_COUNT);
    }
    
    /**
     * Gets the number of active subscribers for a specific event class.
     * 
     * @param clazz the event class
     * @return the subscriber count
     */
    public int getSubscriberCount(Class<?> clazz) {
        return getSubscriberCountForClass(clazz);
    }
    
    /**
     * Gets the total number of active subscribers across all event types.
     * 
     * @return the total subscriber count
     */
    public int getTotalSubscriberCount() {
        return getTotalSubscriberCountInternal();
    }
    
    @Override
    public void close() {
        if (closed) {
            LOGGER.debug("EventBus already closed");
            return;
        }
        
        LOGGER.info("Closing EventBus - Total events published: {}, Dead events: {}, Active subscribers: {}", 
            getTotalEventsPublished(), getDeadEventCount(), getTotalSubscriberCount());
        
        // Clear all subscribers and mark as closed
        int removedCount = clearAllSubscribers();
        closed = true;
        
        // Clean up ThreadLocal variables to prevent memory leaks
        deadEventRecursionDepth.remove();
        processingDeadEvent.remove();
        
        LOGGER.info("EventBus closed - Removed {} subscribers", removedCount);
    }
    
    /**
     * Checks if this EventBus has been closed.
     * 
     * @return true if the EventBus has been closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }
    
    /**
     * Ensures the EventBus is not closed before performing operations.
     * 
     * @throws IllegalStateException if the EventBus has been closed
     */
    protected void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("EventBus has been closed");
        }
    }
}
