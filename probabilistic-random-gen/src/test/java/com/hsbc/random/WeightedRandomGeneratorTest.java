package com.hsbc.random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WeightedRandomGenerator Tests")
class WeightedRandomGeneratorTest {
    
    @Test
    @DisplayName("Should create generator with valid probabilities")
    void shouldCreateWithValidProbabilities() {
        // Given
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.3f),
            new ProbabilisticRandomGen.NumAndProbability(2, 0.7f)
        );
        
        // When & Then
        assertThatNoException().isThrownBy(() -> 
            new WeightedRandomGenerator(probabilities));
    }
    
    @Test
    @DisplayName("Should throw exception for null or empty probabilities")
    void shouldThrowExceptionForNullOrEmptyProbabilities() {
        // Given & When & Then
        assertThatThrownBy(() -> new WeightedRandomGenerator(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null or empty");
            
        assertThatThrownBy(() -> new WeightedRandomGenerator(Collections.emptyList()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be null or empty");
    }
    
    static Stream<Arguments> provideInvalidProbabilitySums() {
        return Stream.of(
            Arguments.of(
                "Sum less than 1.0",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.3f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.5f) // Sum = 0.8
                )
            ),
            Arguments.of(
                "Sum greater than 1.0",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.6f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.6f) // Sum = 1.2
                )
            ),
            Arguments.of(
                "Mixed zeros with non-1.0 sum",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.0f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.3f),
                    new ProbabilisticRandomGen.NumAndProbability(3, 0.0f),
                    new ProbabilisticRandomGen.NumAndProbability(4, 0.4f) // Sum = 0.7
                )
            )
        );
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("provideInvalidProbabilitySums")
    @DisplayName("Should throw exception when probabilities don't sum to 1.0")
    void shouldThrowWhenProbabilitiesDontSumToOne(String testCase, List<ProbabilisticRandomGen.NumAndProbability> probabilities) {
        assertThatThrownBy(() -> new WeightedRandomGenerator(probabilities))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Probabilities must sum to 1.0");
    }
    
    static Stream<Arguments> provideValidProbabilitySumsWithRounding() {
        return Stream.of(
            Arguments.of(
                "Three equal with rounding",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.33334f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.33333f),
                    new ProbabilisticRandomGen.NumAndProbability(3, 0.33333f) // Sum ≈ 1.0
                )
            ),
            Arguments.of(
                "Float precision edge case",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.333333f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.333333f),
                    new ProbabilisticRandomGen.NumAndProbability(3, 0.333334f) // Sum ≈ 1.0
                )
            ),
            Arguments.of(
                "Very close to 1.0",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.5f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.5f - 1e-7f) // Just under 1.0
                )
            )
        );
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("provideValidProbabilitySumsWithRounding")
    @DisplayName("Should accept probabilities that sum approximately to 1.0")
    void shouldAcceptProbabilitiesWithRoundingTolerance(String testCase, List<ProbabilisticRandomGen.NumAndProbability> probabilities) {
        assertThatNoException().isThrownBy(() -> {
            WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
            // Verify it generates valid samples
            for (int i = 0; i < 100; i++) {
                generator.nextFromSample();
            }
        });
    }
    
    @Test
    @DisplayName("Should return only configured numbers")
    void shouldReturnOnlyConfiguredNumbers() {
        // Given
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(10, 0.5f),
            new ProbabilisticRandomGen.NumAndProbability(20, 0.5f)
        );
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
        Set<Integer> expectedNumbers = Set.of(10, 20);
        Set<Integer> actualNumbers = new HashSet<>();
        
        // When - generate many samples
        for (int i = 0; i < 1000; i++) {
            actualNumbers.add(generator.nextFromSample());
        }
        
        // Then
        assertThat(actualNumbers).isEqualTo(expectedNumbers);
    }
    
    @RepeatedTest(25)  // Increased from 10 to 25 for better confidence
    @DisplayName("Should respect probability distribution over many samples")
    void shouldRespectProbabilityDistribution() {
        // Given
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.1f),  // 10%
            new ProbabilisticRandomGen.NumAndProbability(2, 0.9f)   // 90%
        );
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
        
        int samples = 50000;  // Increased from 10,000 to 50,000 for tighter confidence
        int count1 = 0, count2 = 0;
        
        // When
        for (int i = 0; i < samples; i++) {
            int result = generator.nextFromSample();
            if (result == 1) count1++;
            else if (result == 2) count2++;
        }
        
        // Then - verify distribution (with 2% tolerance due to larger sample size)
        double ratio1 = (double) count1 / samples;
        double ratio2 = (double) count2 / samples;
        
        assertThat(ratio1).isCloseTo(0.1, within(0.02));  // Tighter tolerance
        assertThat(ratio2).isCloseTo(0.9, within(0.02));  // Tighter tolerance
    }
    
    @Test
    @DisplayName("Should work with single probability")
    void shouldWorkWithSingleProbability() {
        // Given
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(42, 1.0f)
        );
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
        
        // When & Then
        for (int i = 0; i < 100; i++) {
            assertThat(generator.nextFromSample()).isEqualTo(42);
        }
    }
    
    @Test
    @DisplayName("Should use provided Random instance")
    void shouldUseProvidedRandomInstance() {
        // Given
        Random mockRandom = new Random(12345); // Fixed seed for predictable results
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.5f),
            new ProbabilisticRandomGen.NumAndProbability(2, 0.5f)
        );
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities, mockRandom);
        
        // When
        List<Integer> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(generator.nextFromSample());
        }
        
        // Then - with fixed seed, results should be deterministic
        WeightedRandomGenerator generator2 = new WeightedRandomGenerator(probabilities, new Random(12345));
        List<Integer> results2 = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results2.add(generator2.nextFromSample());
        }
        
        assertThat(results).isEqualTo(results2);
    }
    
    @Test
    @DisplayName("Should return defensive copies of internal arrays")
    void shouldReturnDefensiveCopies() {
        // Given
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.5f),
            new ProbabilisticRandomGen.NumAndProbability(2, 0.5f)
        );
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
        
        // When
        int[] numbers1 = generator.getNumbers();
        int[] numbers2 = generator.getNumbers();
        float[] probs1 = generator.getCumulativeProbabilities();
        float[] probs2 = generator.getCumulativeProbabilities();
        
        // Then
        assertThat(numbers1).isNotSameAs(numbers2);
        assertThat(probs1).isNotSameAs(probs2);
        
        // Modifying returned arrays shouldn't affect the generator
        numbers1[0] = 999;
        probs1[0] = 999.0f;
        
        assertThat(generator.getNumbers()[0]).isNotEqualTo(999);
        assertThat(generator.getCumulativeProbabilities()[0]).isNotEqualTo(999.0f);
    }

    @Test
    @DisplayName("Should not select items with zero probability")
    void shouldNotSelectZeroProbabilityItems() {
        // Given
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.5f),
            new ProbabilisticRandomGen.NumAndProbability(2, 0.0f), // Zero probability
            new ProbabilisticRandomGen.NumAndProbability(3, 0.5f)
        );
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);

        // When
        for (int i = 0; i < 1000; i++) {
            int result = generator.nextFromSample();
            // Then
            assertThat(result).isNotEqualTo(2); // The number with 0 probability should never be chosen
        }

        // Also check that the internal numbers array does not contain the zero-prob number
        assertThat(generator.getNumbers()).containsExactly(1, 3);
    }
    
    static Stream<Arguments> provideComplexProbabilityDistributions() {
        return Stream.of(
            Arguments.of(
                "Three equal probabilities",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.33333f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.33333f),
                    new ProbabilisticRandomGen.NumAndProbability(3, 0.33334f)
                )
            ),
            Arguments.of(
                "Skewed distribution",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.05f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.15f),
                    new ProbabilisticRandomGen.NumAndProbability(3, 0.80f)
                )
            ),
            Arguments.of(
                "Many small probabilities",
                Arrays.asList(
                    new ProbabilisticRandomGen.NumAndProbability(1, 0.1f),
                    new ProbabilisticRandomGen.NumAndProbability(2, 0.1f),
                    new ProbabilisticRandomGen.NumAndProbability(3, 0.1f),
                    new ProbabilisticRandomGen.NumAndProbability(4, 0.1f),
                    new ProbabilisticRandomGen.NumAndProbability(5, 0.1f),
                    new ProbabilisticRandomGen.NumAndProbability(6, 0.5f)
                )
            )
        );
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("provideComplexProbabilityDistributions")
    @DisplayName("Should handle complex probability distributions with correct frequencies")
    void shouldHandleComplexProbabilityDistributions(String testName, 
                                                    List<ProbabilisticRandomGen.NumAndProbability> probabilities) {
        // Given
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
        Map<Integer, Float> expectedDistribution = new HashMap<>();
        probabilities.forEach(nap -> expectedDistribution.put(nap.getNumber(), nap.getProbabilityOfSample()));
        
        // When - Sample 50,000 times for statistical significance
        int sampleSize = 50_000;
        Map<Integer, Integer> counts = new HashMap<>();
        for (int i = 0; i < sampleSize; i++) {
            int result = generator.nextFromSample();
            counts.merge(result, 1, Integer::sum);
        }
        
        // Then - Verify all expected numbers appeared
        assertThat(counts.keySet())
            .as("All expected numbers should appear in samples")
            .containsExactlyInAnyOrderElementsOf(expectedDistribution.keySet());
        
        // Verify frequency distribution matches expected probabilities
        for (Map.Entry<Integer, Float> entry : expectedDistribution.entrySet()) {
            int number = entry.getKey();
            float expectedProb = entry.getValue();
            int actualCount = counts.getOrDefault(number, 0);
            double actualProb = (double) actualCount / sampleSize;
            
            // Calculate acceptable tolerance based on probability magnitude
            // Smaller probabilities need wider tolerance
            double tolerance = Math.max(0.02, expectedProb * 0.2); // At least 2% or 20% of expected
            
            assertThat(actualProb)
                .as("Number %d with expected probability %.3f should have actual probability close to expected (tolerance: %.3f)",
                    number, expectedProb, tolerance)
                .isCloseTo(expectedProb, within(tolerance));
        }
    }
    
    static Stream<Arguments> provideTinyProbabilities() {
        return Stream.of(
            Arguments.of(
                "Extremely small probability (1e-6)",
                1e-6f,
                2_000_000,  // Much larger sample size for better chance
                false       // Don't validate frequency - too unreliable
            ),
            Arguments.of(
                "Float.MIN_VALUE boundary",
                Float.MIN_VALUE,
                100,        // Just verify it doesn't crash
                false       // Don't validate frequency for MIN_VALUE
            )
        );
    }
    
    @ParameterizedTest(name = "{0}")
    @MethodSource("provideTinyProbabilities")
    @DisplayName("Should handle tiny probabilities at float precision limits")
    void shouldHandleTinyProbabilities(String testCase, float tinyProb, int sampleSize, boolean validateFrequency) {
        // Given
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, tinyProb),
            new ProbabilisticRandomGen.NumAndProbability(2, 1.0f - tinyProb)
        );
        
        // When & Then - Should create valid generator
        assertThatNoException().isThrownBy(() -> {
            WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
            
            if (validateFrequency) {
                // Sample and verify rare item appears
                boolean rareAppeared = false;
                int rareCount = 0;
                
                for (int i = 0; i < sampleSize; i++) {
                    if (generator.nextFromSample() == 1) {
                        rareAppeared = true;
                        rareCount++;
                    }
                }
                
                // Rare item should appear at least once
                assertThat(rareAppeared)
                    .as("Item with probability %f should appear at least once in %d samples", tinyProb, sampleSize)
                    .isTrue();
                
                // Verify frequency is roughly correct (with wide tolerance for tiny probabilities)
                double actualRatio = (double) rareCount / sampleSize;
                double tolerance = Math.max(tinyProb * 0.8, 1e-6); // 80% tolerance or minimum 1e-6
                
                assertThat(actualRatio)
                    .as("Rare item frequency should be close to expected probability")
                    .isCloseTo(tinyProb, within(tolerance));
            } else {
                // Just verify it doesn't crash for extreme values
                for (int i = 0; i < sampleSize; i++) {
                    generator.nextFromSample();
                }
            }
        });
    }
    
    @Test
    @DisplayName("Should reject negative probability values")
    void shouldRejectNegativeProbabilities() {
        // Given - Invalid negative probability
        // When & Then - Should throw IllegalArgumentException at NumAndProbability construction
        assertThatThrownBy(() -> new ProbabilisticRandomGen.NumAndProbability(1, -0.1f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Probability must be between 0.0 and 1.0");
    }
    
    @Test
    @DisplayName("Should reject probability values greater than 1")
    void shouldRejectProbabilitiesGreaterThanOne() {
        // Given - Invalid probability > 1
        assertThatThrownBy(() -> new ProbabilisticRandomGen.NumAndProbability(1, 1.5f))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Probability must be between 0.0 and 1.0");
    }
    
    @Test
    @DisplayName("Should reject NaN probability values")
    void shouldRejectNaNProbabilities() {
        // Given - NaN probability
        assertThatThrownBy(() -> new ProbabilisticRandomGen.NumAndProbability(1, Float.NaN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Probability must be between 0.0 and 1.0");
    }
    
    @Test
    @DisplayName("Should reject Infinity probability values")
    void shouldRejectInfinityProbabilities() {
        // Given - Positive infinity
        assertThatThrownBy(() -> new ProbabilisticRandomGen.NumAndProbability(1, Float.POSITIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Probability must be between 0.0 and 1.0");
        
        // Given - Negative infinity
        assertThatThrownBy(() -> new ProbabilisticRandomGen.NumAndProbability(1, Float.NEGATIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Probability must be between 0.0 and 1.0");
    }
    
    @Test
    @DisplayName("Should handle duplicate numbers with different probabilities")
    void shouldHandleDuplicateNumbers() {
        // Given - Same number appears multiple times with different probabilities
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.2f),
            new ProbabilisticRandomGen.NumAndProbability(1, 0.3f),  // Duplicate number
            new ProbabilisticRandomGen.NumAndProbability(2, 0.5f)
        );
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
        
        // When - Sample many times
        int count1 = 0;
        int count2 = 0;
        int samples = 50000;  // Increased sample size for better accuracy
        
        for (int i = 0; i < samples; i++) {
            int result = generator.nextFromSample();
            if (result == 1) count1++;
            else if (result == 2) count2++;
        }
        
        // Then - Number 1 should appear ~50% of the time (0.2 + 0.3)
        // Then - verify distribution (with 2% tolerance due to larger sample size)
        double ratio1 = (double) count1 / samples;
        double ratio2 = (double) count2 / samples;
        
        assertThat(ratio1).isCloseTo(0.5, within(0.02));  // Tighter tolerance
        assertThat(ratio2).isCloseTo(0.5, within(0.02));  // Tighter tolerance
        
        // Also verify internal structure contains both instances
        assertThat(generator.getNumbers()).hasSize(3);
        assertThat(generator.getNumbers()).containsExactly(1, 1, 2);
    }
    
    @Test
    @DisplayName("Should handle large-scale inputs efficiently")
    void shouldHandleLargeScaleInputs() {
        // Given - 1000+ items with uniform distribution
        int itemCount = 1000;
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = new ArrayList<>();
        
        // Calculate probabilities to ensure they sum to exactly 1.0
        float baseProb = 1.0f / itemCount;
        float totalAssigned = 0.0f;
        
        for (int i = 0; i < itemCount - 1; i++) {
            probabilities.add(new ProbabilisticRandomGen.NumAndProbability(i, baseProb));
            totalAssigned += baseProb;
        }
        // Last item gets the remainder to ensure exact sum of 1.0
        probabilities.add(new ProbabilisticRandomGen.NumAndProbability(itemCount - 1, 1.0f - totalAssigned));
        
        // When - Create generator and sample
        long startTime = System.currentTimeMillis();
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities);
        long constructionTime = System.currentTimeMillis() - startTime;
        
        // Sample to verify it works
        Set<Integer> sampledNumbers = new HashSet<>();
        startTime = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++) {
            sampledNumbers.add(generator.nextFromSample());
        }
        long samplingTime = System.currentTimeMillis() - startTime;
        
        // Then - Should handle large inputs efficiently
        assertThat(constructionTime)
            .as("Construction time for 1000 items should be reasonable")
            .isLessThan(1000); // Less than 1 second
        
        assertThat(samplingTime)
            .as("10000 samples should complete quickly")
            .isLessThan(100); // Less than 100ms for 10k samples
        
        // Verify reasonable coverage of numbers
        assertThat(sampledNumbers.size())
            .as("Should sample a good variety of numbers")
            .isGreaterThan(100); // At least 100 different numbers sampled
    }
    
    @Test
    @DisplayName("Should handle boundary random values correctly")
    void shouldHandleBoundaryRandomValues() {
        // Given - Simple distribution
        List<ProbabilisticRandomGen.NumAndProbability> probabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.25f),
            new ProbabilisticRandomGen.NumAndProbability(2, 0.25f),
            new ProbabilisticRandomGen.NumAndProbability(3, 0.25f),
            new ProbabilisticRandomGen.NumAndProbability(4, 0.25f)
        );
        
        // Create a custom Random subclass to return specific values
        Random customRandom = new Random() {
            private final float[] values = {0.0f, 0.24999f, 0.25f, 0.99999f, 0.5f, 0.75f};
            private int index = 0;
            
            @Override
            public float nextFloat() {
                return values[index++];
            }
        };
        
        WeightedRandomGenerator generator = new WeightedRandomGenerator(probabilities, customRandom);
        
        // When & Then - Verify each boundary case
        assertThat(generator.nextFromSample()).isEqualTo(1); // 0.0f -> first bucket
        assertThat(generator.nextFromSample()).isEqualTo(1); // 0.24999f -> still first bucket
        assertThat(generator.nextFromSample()).isEqualTo(2); // 0.25f -> second bucket
        assertThat(generator.nextFromSample()).isEqualTo(4); // 0.99999f -> last bucket
        assertThat(generator.nextFromSample()).isEqualTo(3); // 0.5f -> third bucket
        assertThat(generator.nextFromSample()).isEqualTo(4); // 0.75f -> fourth bucket
    }
    
    @Test
    @DisplayName("Should handle all zero probabilities after filtering")
    void shouldThrowWhenAllProbabilitiesAreZero() {
        // Given - All probabilities are zero
        List<ProbabilisticRandomGen.NumAndProbability> allZeroProbabilities = Arrays.asList(
            new ProbabilisticRandomGen.NumAndProbability(1, 0.0f),
            new ProbabilisticRandomGen.NumAndProbability(2, 0.0f),
            new ProbabilisticRandomGen.NumAndProbability(3, 0.0f)
        );
        
        // When & Then - Should throw because no valid distribution exists
        assertThatThrownBy(() -> new WeightedRandomGenerator(allZeroProbabilities))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("All probabilities are zero");
    }

}
