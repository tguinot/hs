package com.hsbc.random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("NumAndProbability Tests")
class NumAndProbabilityTest {
    
    @Test
    @DisplayName("Should create NumAndProbability with valid parameters")
    void shouldCreateWithValidParameters() {
        // Given
        int number = 42;
        float probability = 0.5f;
        
        // When
        ProbabilisticRandomGen.NumAndProbability nap = 
            new ProbabilisticRandomGen.NumAndProbability(number, probability);
        
        // Then
        assertThat(nap.getNumber()).isEqualTo(number);
        assertThat(nap.getProbabilityOfSample()).isEqualTo(probability);
    }
    
    @Test
    @DisplayName("Should accept boundary probability values")
    void shouldAcceptBoundaryValues() {
        // Given & When & Then
        assertThatNoException().isThrownBy(() -> 
            new ProbabilisticRandomGen.NumAndProbability(1, 0.0f));
        assertThatNoException().isThrownBy(() -> 
            new ProbabilisticRandomGen.NumAndProbability(1, 1.0f));
    }
    
    @ParameterizedTest
    @ValueSource(floats = {-0.1f, -1.0f, 1.1f, 2.0f, Float.NaN, Float.POSITIVE_INFINITY})
    @DisplayName("Should throw exception for invalid probability values")
    void shouldThrowExceptionForInvalidProbabilities(float invalidProbability) {
        // Given & When & Then
        assertThatThrownBy(() -> 
            new ProbabilisticRandomGen.NumAndProbability(1, invalidProbability))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Probability must be between 0.0 and 1.0");
    }
    
    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        // Given
        ProbabilisticRandomGen.NumAndProbability nap1 = 
            new ProbabilisticRandomGen.NumAndProbability(42, 0.5f);
        ProbabilisticRandomGen.NumAndProbability nap2 = 
            new ProbabilisticRandomGen.NumAndProbability(42, 0.5f);
        ProbabilisticRandomGen.NumAndProbability nap3 = 
            new ProbabilisticRandomGen.NumAndProbability(43, 0.5f);
        ProbabilisticRandomGen.NumAndProbability nap4 = 
            new ProbabilisticRandomGen.NumAndProbability(42, 0.6f);
        
        // When & Then
        assertThat(nap1).isEqualTo(nap2);
        assertThat(nap1).isNotEqualTo(nap3);
        assertThat(nap1).isNotEqualTo(nap4);
        assertThat(nap1).isNotEqualTo(null);
        assertThat(nap1).isNotEqualTo("not a NumAndProbability");
    }
    
    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCodeCorrectly() {
        // Given
        ProbabilisticRandomGen.NumAndProbability nap1 = 
            new ProbabilisticRandomGen.NumAndProbability(42, 0.5f);
        ProbabilisticRandomGen.NumAndProbability nap2 = 
            new ProbabilisticRandomGen.NumAndProbability(42, 0.5f);
        
        // When & Then
        assertThat(nap1.hashCode()).isEqualTo(nap2.hashCode());
    }
    
    @Test
    @DisplayName("Should implement toString correctly")
    void shouldImplementToStringCorrectly() {
        // Given
        ProbabilisticRandomGen.NumAndProbability nap = 
            new ProbabilisticRandomGen.NumAndProbability(42, 0.5f);
        
        // When
        String result = nap.toString();
        
        // Then
        assertThat(result).contains("42");
        assertThat(result).contains("0.500");
        assertThat(result).contains("NumAndProbability");
    }
}
