package com.hsbc.eventbus;

/**
 * Exception thrown when an error occurs in the EventBus.
 */
public class EventBusException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    public EventBusException(String message) {
        super(message);
    }
    
    public EventBusException(String message, Throwable cause) {
        super(message, cause);
    }
}
