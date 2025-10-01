package com.hsbc.eventbus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DeadEvent Tests")
class DeadEventTest {
    
    @Test
    @DisplayName("Should create DeadEvent with valid event")
    void shouldCreateDeadEventWithValidEvent() {
        String event = "test event";
        DeadEvent deadEvent = new DeadEvent(event);
        
        assertThat(deadEvent.getEvent()).isEqualTo(event);
        assertThat(deadEvent.getTimestamp()).isGreaterThan(0);
    }
    
    @Test
    @DisplayName("Should reject null event")
    void shouldRejectNullEvent() {
        assertThatThrownBy(() -> new DeadEvent(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Event must not be null");
    }
    
    @Test
    @DisplayName("Should have timestamp close to current time")
    void shouldHaveTimestampCloseToCurrentTime() {
        long before = System.currentTimeMillis();
        DeadEvent deadEvent = new DeadEvent("test");
        long after = System.currentTimeMillis();
        
        assertThat(deadEvent.getTimestamp())
            .isGreaterThanOrEqualTo(before)
            .isLessThanOrEqualTo(after);
    }
    
    @Test
    @DisplayName("Should implement toString correctly")
    void shouldImplementToStringCorrectly() {
        String event = "test event";
        DeadEvent deadEvent = new DeadEvent(event);
        
        String toString = deadEvent.toString();
        
        assertThat(toString)
            .contains("DeadEvent")
            .contains("event=test event")
            .contains("eventClass=java.lang.String")
            .contains("timestamp=" + deadEvent.getTimestamp());
    }
    
    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        String event1 = "test";
        String event2 = "test";
        String event3 = "different";
        
        // Create two dead events at the same time (approximately)
        DeadEvent deadEvent1 = new DeadEvent(event1);
        DeadEvent deadEvent2 = new DeadEvent(event2);
        DeadEvent deadEvent3 = new DeadEvent(event3);
        
        // Same instance
        assertThat(deadEvent1).isEqualTo(deadEvent1);
        
        // Null
        assertThat(deadEvent1).isNotEqualTo(null);
        
        // Different class
        assertThat(deadEvent1).isNotEqualTo("not a dead event");
        
        // Different event
        assertThat(deadEvent1).isNotEqualTo(deadEvent3);
        
        // Note: deadEvent1 and deadEvent2 might not be equal due to different timestamps
        // even though they wrap the same event
    }
    
    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCodeCorrectly() {
        String event = "test";
        DeadEvent deadEvent = new DeadEvent(event);
        
        // Should not throw and should be consistent
        int hash1 = deadEvent.hashCode();
        int hash2 = deadEvent.hashCode();
        
        assertThat(hash1).isEqualTo(hash2);
    }
    
    @Test
    @DisplayName("Should handle complex event objects")
    void shouldHandleComplexEventObjects() {
        Object complexEvent = new Object() {
            @Override
            public String toString() {
                return "ComplexEvent";
            }
        };
        
        DeadEvent deadEvent = new DeadEvent(complexEvent);
        
        assertThat(deadEvent.getEvent()).isSameAs(complexEvent);
        assertThat(deadEvent.toString()).contains("ComplexEvent");
    }
    
    @Test
    @DisplayName("Should preserve event reference")
    void shouldPreserveEventReference() {
        Object event = new Object();
        DeadEvent deadEvent = new DeadEvent(event);
        
        assertThat(deadEvent.getEvent()).isSameAs(event);
    }
    
    @Test
    @DisplayName("Should handle events with null toString")
    void shouldHandleEventsWithNullToString() {
        Object eventWithNullToString = new Object() {
            @Override
            public String toString() {
                return null;
            }
        };
        
        DeadEvent deadEvent = new DeadEvent(eventWithNullToString);
        
        // Should not throw when calling toString
        assertThatNoException().isThrownBy(deadEvent::toString);
    }
}
