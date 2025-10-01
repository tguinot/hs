package com.hsbc.eventbus;

/**
 * Represents a subscription to an event bus that can be cancelled.
 * This interface follows the Disposable pattern for resource management.
 */
public interface Subscription {
    
    /**
     * Cancels this subscription, removing the associated subscriber from the event bus.
     * After calling this method, the subscriber will no longer receive events.
     * 
     * This method is idempotent - calling it multiple times has no additional effect.
     */
    void unsubscribe();
    
    /**
     * Checks if this subscription is still active.
     * 
     * @return true if the subscription is active, false if it has been unsubscribed
     */
    boolean isActive();
}
