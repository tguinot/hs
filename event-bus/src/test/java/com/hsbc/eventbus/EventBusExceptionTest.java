package com.hsbc.eventbus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventBusException Tests")
class EventBusExceptionTest {
    
    @Test
    @DisplayName("Should create exception with message")
    void shouldCreateExceptionWithMessage() {
        String message = "Test error message";
        EventBusException exception = new EventBusException(message);
        
        assertThat(exception)
            .isInstanceOf(RuntimeException.class)
            .hasMessage(message)
            .hasNoCause();
    }
    
    @Test
    @DisplayName("Should create exception with message and cause")
    void shouldCreateExceptionWithMessageAndCause() {
        String message = "Test error message";
        Throwable cause = new IllegalStateException("Root cause");
        
        EventBusException exception = new EventBusException(message, cause);
        
        assertThat(exception)
            .isInstanceOf(RuntimeException.class)
            .hasMessage(message)
            .hasCause(cause);
    }
    
    @Test
    @DisplayName("Should be throwable")
    void shouldBeThrowable() {
        assertThatThrownBy(() -> {
            throw new EventBusException("Test exception");
        })
        .isInstanceOf(EventBusException.class)
        .hasMessage("Test exception");
    }
    
    @Test
    @DisplayName("Should preserve stack trace")
    void shouldPreserveStackTrace() {
        EventBusException exception = new EventBusException("Test");
        
        assertThat(exception.getStackTrace()).isNotEmpty();
    }
    
    @Test
    @DisplayName("Should handle null message")
    void shouldHandleNullMessage() {
        EventBusException exception = new EventBusException(null);
        
        assertThat(exception.getMessage()).isNull();
    }
    
    @Test
    @DisplayName("Should handle null cause")
    void shouldHandleNullCause() {
        EventBusException exception = new EventBusException("Test", null);
        
        assertThat(exception.getMessage()).isEqualTo("Test");
        assertThat(exception.getCause()).isNull();
    }
}
