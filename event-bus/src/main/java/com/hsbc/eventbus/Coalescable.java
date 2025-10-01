package com.hsbc.eventbus;

/**
 * Marker interface for events that support coalescing/conflation.
 * 
 * <p>Events implementing this interface indicate that they represent
 * state updates where only the latest value matters. When multiple
 * events of this type are published in rapid succession, they can
 * be coalesced so that only the latest event is delivered.
 * 
 * <p>The coalescing key is determined by the {@link #getCoalescingKey()}
 * method. Events with the same key will be coalesced together.
 * 
 * <p>Example implementation:
 * <pre>{@code
 * public class MarketDataUpdate implements Coalescable {
 *     private final String symbol;
 *     private final double price;
 *     private final long timestamp;
 *     
 *     @Override
 *     public Object getCoalescingKey() {
 *         return symbol;  // Coalesce updates for the same symbol
 *     }
 *     
 *     // ... other methods
 * }
 * }</pre>
 * 
 * <p>Benefits of implementing this interface:
 * <ul>
 *   <li>Automatic coalescing when used with CoalescingEventBus</li>
 *   <li>Reduced processing overhead for rapid state updates</li>
 *   <li>Prevention of queue buildup during high-frequency updates</li>
 *   <li>Guaranteed latest state delivery to subscribers</li>
 * </ul>
 * 
 * @see CoalescingEventBus
 */
public interface Coalescable {
    
    /**
     * Returns the key used for coalescing this event with others.
     * 
     * <p>Events with equal keys (as determined by {@link Object#equals(Object)})
     * will be coalesced together, with only the latest event being delivered.
     * 
     * <p>The key should be:
     * <ul>
     *   <li>Immutable - the key should not change after the event is created</li>
     *   <li>Consistent - equal events should return equal keys</li>
     *   <li>Efficient - key comparison should be fast</li>
     * </ul>
     * 
     * <p>Common key types:
     * <ul>
     *   <li>String - for symbols, identifiers, names</li>
     *   <li>Integer/Long - for numeric IDs</li>
     *   <li>Composite keys - for multi-field grouping</li>
     * </ul>
     * 
     * @return the coalescing key for this event, must not be null
     */
    Object getCoalescingKey();
    
    /**
     * Optional method to indicate if this event should replace another event
     * with the same coalescing key.
     * 
     * <p>By default, newer events always replace older ones. Override this
     * method if you need custom logic (e.g., based on timestamps or version numbers).
     * 
     * @param other the existing event that might be replaced
     * @return true if this event should replace the other, false otherwise
     */
    default boolean shouldReplace(Coalescable other) {
        return true;  // Default: always replace with newer events
    }
}
