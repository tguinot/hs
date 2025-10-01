package com.hsbc.eventbus;

import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Extended EventBus interface that supports event coalescing/conflation.
 * 
 * <p>Coalescing is useful for event types where only the latest value matters,
 * such as market data updates where multiple price updates may arrive while
 * the system is processing a previous update.
 * 
 * <p>When coalescing is enabled for an event type:
 * <ul>
 *   <li>Multiple events of the same type with the same key are conflated</li>
 *   <li>Only the latest event for each key is delivered to subscribers</li>
 *   <li>"Latest" can be customised per subscriber via a comparator (e.g. by timestamp)</li>
 *   <li>Events are delivered when the subscriber is ready to process them</li>
 *   <li>Supports composite keys for fine-grained coalescing control</li>
 * </ul>
 * 
 * <p>Example use case - Order Book with Composite Keys:
 * <pre>{@code
 * // Composite key for order book price levels
 * class PriceLevelKey {
 *     String symbol;
 *     double price;
 *     Side side;  // BID or ASK
 *     
 *     // equals() and hashCode() implementation...
 * }
 * 
 * // Configure coalescing with composite key
 * eventBus.addCoalescingSubscriber(
 *     OrderBookUpdate.class,
 *     update -> new PriceLevelKey(update.getSymbol(), 
 *                                 update.getPrice(), 
 *                                 update.getSide()),
 *     update -> processOrderBookUpdate(update)
 * );
 * 
 * // These updates coalesce independently by price level:
 * eventBus.publishEvent(new OrderBookUpdate("AAPL", 150.00, 100.0, BID));  // Bid at 150.00
 * eventBus.publishEvent(new OrderBookUpdate("AAPL", 150.05, 200.0, BID));  // Bid at 150.05 (different key)
 * eventBus.publishEvent(new OrderBookUpdate("AAPL", 150.00, 300.0, BID));  // Replaces first (same key)
 * // Result: Two events delivered - 150.00 with size 300, and 150.05 with size 200
 * }</pre>
 */
public interface CoalescingEventBus extends EventBus {
    
    /**
     * Adds a coalescing subscriber for the specified event class.
     * 
     * <p>Events are coalesced based on a key extracted from each event.
     * When multiple events with the same key are published before the subscriber
     * processes them, only the latest event is delivered.
     * 
     * <p>The key can be simple (e.g., a symbol string) or composite (e.g., a
     * custom object with multiple fields). For composite keys, ensure proper
     * equals() and hashCode() implementations.
     * 
     * @param eventClass the event class to subscribe to
     * @param keyExtractor function to extract the coalescing key from an event
     * @param callback the callback to invoke with the latest event for each key
     * @param <E> the type of the event
     * @param <K> the type of the coalescing key (can be composite)
     * @return a Subscription that can be used to unsubscribe
     * @throws NullPointerException if any parameter is null
     */
    <E, K> Subscription addCoalescingSubscriber(
        Class<E> eventClass,
        Function<E, K> keyExtractor,
        Consumer<E> callback
    );

    /**
     * Adds a coalescing subscriber that determines the latest event using a comparator.
     *
     * <p>This overload is useful when events contain their own ordering metadata such as
     * timestamps or sequence numbers. The comparator is invoked whenever multiple events
     * for the same key are pending, and the event considered <em>greater</em> according to the
     * comparator is retained.</p>
     *
     * @param eventClass the event class to subscribe to
     * @param keyExtractor function to extract the coalescing key from an event
     * @param recencyComparator comparator that defines which event is newer (higher value wins)
     * @param callback the callback to invoke with the latest event for each key
     * @param <E> the type of the event
     * @param <K> the type of the coalescing key (can be composite)
     * @return a Subscription that can be used to unsubscribe
     * @throws NullPointerException if any parameter is null
     */
    <E, K> Subscription addCoalescingSubscriber(
        Class<E> eventClass,
        Function<E, K> keyExtractor,
        Comparator<E> recencyComparator,
        Consumer<E> callback
    );
    
    /**
     * Adds a coalescing subscriber with filtering support.
     * 
     * <p>Combines coalescing with event filtering. Events are first filtered,
     * then coalesced based on the extracted key.
     * 
     * @param eventClass the event class to subscribe to
     * @param keyExtractor function to extract the coalescing key from an event
     * @param filter predicate to filter events before coalescing
     * @param callback the callback to invoke with the latest matching event for each key
     * @param <E> the type of the event
     * @param <K> the type of the coalescing key
     * @return a Subscription that can be used to unsubscribe
     * @throws NullPointerException if any parameter is null
     */
    <E, K> Subscription addCoalescingSubscriber(
        Class<E> eventClass,
        Function<E, K> keyExtractor,
        Predicate<E> filter,
        Consumer<E> callback
    );

    /**
     * Adds a coalescing subscriber with filtering and custom recency comparator support.
     *
     * @param eventClass the event class to subscribe to
     * @param keyExtractor function to extract the coalescing key from an event
     * @param recencyComparator comparator that defines which event is newer (higher value wins)
     * @param filter predicate to filter events before coalescing
     * @param callback the callback to invoke with the latest matching event for each key
     * @param <E> the type of the event
     * @param <K> the type of the coalescing key
     * @return a Subscription that can be used to unsubscribe
     * @throws NullPointerException if any parameter is null
     */
    <E, K> Subscription addCoalescingSubscriber(
        Class<E> eventClass,
        Function<E, K> keyExtractor,
        Comparator<E> recencyComparator,
        Predicate<E> filter,
        Consumer<E> callback
    );
    
    /**
     * Configures global coalescing for an event class.
     * 
     * <p>When enabled, ALL subscribers to this event class will receive
     * coalesced events. This is useful when the event type inherently
     * represents state that should always be coalesced.
     * 
     * <p>Supports composite keys for scenarios like order books where
     * coalescing should occur per price level, not per symbol.
     * 
     * @param eventClass the event class to configure
     * @param keyExtractor function to extract the coalescing key from an event
     * @param <E> the type of the event
     * @param <K> the type of the coalescing key (can be composite)
     */
    <E, K> void configureCoalescing(
        Class<E> eventClass,
        Function<E, K> keyExtractor
    );
    
    /**
     * Disables global coalescing for an event class.
     * 
     * @param eventClass the event class to configure
     * @param <E> the type of the event
     */
    <E> void disableCoalescing(Class<E> eventClass);
    
    /**
     * Checks if global coalescing is enabled for an event class.
     * 
     * @param eventClass the event class to check
     * @return true if coalescing is enabled for this event class
     */
    boolean isCoalescingEnabled(Class<?> eventClass);
    
    /**
     * Gets the number of coalesced events that are pending delivery.
     * 
     * <p>This is useful for monitoring and debugging to understand
     * how much conflation is occurring.
     * 
     * @return the total number of pending coalesced events across all types
     */
    int getPendingCoalescedEventCount();
    
    /**
     * Gets the number of coalesced events for a specific event class.
     * 
     * @param eventClass the event class to check
     * @return the number of pending coalesced events for this class
     */
    int getPendingCoalescedEventCount(Class<?> eventClass);
    
    /**
     * Forces immediate delivery of all pending coalesced events.
     * 
     * <p>This can be useful in certain scenarios like graceful shutdown
     * or when you need to ensure all pending updates are processed.
     */
    void flushCoalescedEvents();
    
    /**
     * Forces immediate delivery of pending coalesced events for a specific class.
     * 
     * @param eventClass the event class to flush
     * @param <E> the type of the event
     */
    <E> void flushCoalescedEvents(Class<E> eventClass);
}
