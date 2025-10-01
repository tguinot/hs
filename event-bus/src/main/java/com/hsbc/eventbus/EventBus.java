package com.hsbc.eventbus;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * EventBus interface for publishing and subscribing to events.
 * 
 * <p>This interface defines the core contract for event bus implementations.
 * Different implementations may provide different threading models:
 * <ul>
 *   <li>{@link ThreadUnsafeEventBus} - for single-threaded environments</li>
 *   <li>{@link ThreadSafeEventBus} - for concurrent environments</li>
 * </ul>
 * 
 * <p><b>Design Decisions:</b>
 * <ul>
 *   <li><b>Event Type:</b> We use Object (via generics) for maximum flexibility</li>
 *   <li><b>Subscriber Type:</b> We use Consumer&lt;E&gt; as it's a functional interface</li>
 *   <li><b>Filtering:</b> We use Predicate&lt;E&gt; for type-safe, composable filters</li>
 * </ul>
 */
public interface EventBus {
    
    /**
     * Publishes an event to all registered subscribers.
     * 
     * <p>We use generic type &lt;E&gt; instead of a specific event type to allow
     * any object to be an event. This provides maximum flexibility without
     * forcing events to extend a base class or implement an interface.
     * 
     * @param event the event to publish (can be any Object type)
     * @param <E> the type of the event
     * @throws NullPointerException if event is null
     */
    <E> void publishEvent(E event);
    
    /**
     * Adds a subscriber for the specified event class.
     * 
     * <p>We use Consumer&lt;E&gt; to denote the subscriber because:
     * <ul>
     *   <li>It's a functional interface enabling lambda expressions</li>
     *   <li>Semantically correct - subscribers consume events</li>
     *   <li>No need for custom subscriber interfaces</li>
     * </ul>
     * 
     * @param clazz the event class to subscribe to
     * @param callback the callback to invoke when an event is published
     * @param <E> the type of the event
     * @return a Subscription that can be used to unsubscribe
     * @throws NullPointerException if clazz or callback is null
     */
    <E> Subscription addSubscriber(Class<E> clazz, Consumer<E> callback);
    
    /**
     * Adds a subscriber with filtering support.
     * 
     * <p>We allow clients to filter events using Predicate&lt;E&gt; because:
     * <ul>
     *   <li>Type-safe - predicate is typed to the event class</li>
     *   <li>Composable - predicates can be combined with and(), or(), negate()</li>
     *   <li>Functional - enables concise lambda expressions</li>
     * </ul>
     * 
     * <p>Example:
     * <pre>{@code
     * eventBus.addSubscriberForFilteredEvents(
     *     OrderEvent.class,
     *     order -> order.getAmount() > 1000,
     *     order -> processLargeOrder(order)
     * );
     * }</pre>
     * 
     * @param clazz the event class to subscribe to
     * @param filter predicate to filter events - only events where filter.test(event) returns true are delivered
     * @param callback the callback to invoke for matching events
     * @param <E> the type of the event
     * @return a Subscription that can be used to unsubscribe
     * @throws NullPointerException if clazz, filter, or callback is null
     */
    <E> Subscription addSubscriberForFilteredEvents(Class<E> clazz, Predicate<E> filter, Consumer<E> callback);
}
