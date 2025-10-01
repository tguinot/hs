package com.hsbc.eventbus;

import java.util.Objects;

/**
 * Wraps an event that was published but had no subscribers.
 * This allows monitoring of events that might indicate configuration issues.
 */
public final class DeadEvent {
    
    private final Object event;
    private final long timestamp;
    
    /**
     * Creates a new DeadEvent wrapping the original event.
     * 
     * @param event the event that had no subscribers
     */
    public DeadEvent(Object event) {
        this.event = Objects.requireNonNull(event, "Event must not be null");
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Gets the original event that had no subscribers.
     * 
     * @return the wrapped event
     */
    public Object getEvent() {
        return event;
    }
    
    /**
     * Gets the timestamp when this dead event was created.
     * 
     * @return the timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return "DeadEvent{" +
                "event=" + event +
                ", eventClass=" + event.getClass().getName() +
                ", timestamp=" + timestamp +
                '}';
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeadEvent deadEvent = (DeadEvent) o;
        return timestamp == deadEvent.timestamp && Objects.equals(event, deadEvent.event);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(event, timestamp);
    }
}
