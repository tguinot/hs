package com.hsbc.random;

/**
 * Interface for probabilistic random number generation.
 * Implementations should provide a way to generate random numbers based on
 * predefined probabilities for different values.
 */
public interface ProbabilisticRandomGen {
    
    /**
     * Generates the next random number from the configured sample distribution.
     * 
     * @return a random number based on the configured probabilities
     */
    int nextFromSample();
    
    /**
     * Represents a number and its associated probability of being selected.
     * This is an immutable data class that holds a number value and its
     * corresponding probability of being sampled.
     */
    static class NumAndProbability {
        private final int number;
        private final float probabilityOfSample;
        
        /**
         * Creates a new NumAndProbability instance.
         * 
         * @param number the number value
         * @param probabilityOfSample the probability of this number being selected (0.0 to 1.0)
         * @throws IllegalArgumentException if probability is not between 0.0 and 1.0
         */
        public NumAndProbability(int number, float probabilityOfSample) {
            if (Float.isNaN(probabilityOfSample) || Float.isInfinite(probabilityOfSample) || 
                probabilityOfSample < 0.0f || probabilityOfSample > 1.0f) {
                throw new IllegalArgumentException("Probability must be between 0.0 and 1.0, got: " + probabilityOfSample);
            }
            this.number = number;
            this.probabilityOfSample = probabilityOfSample;
        }
        
        /**
         * Gets the number value.
         * 
         * @return the number
         */
        public int getNumber() {
            return number;
        }
        
        /**
         * Gets the probability of this number being sampled.
         * 
         * @return the probability (0.0 to 1.0)
         */
        public float getProbabilityOfSample() {
            return probabilityOfSample;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            NumAndProbability that = (NumAndProbability) obj;
            return number == that.number && 
                   Float.compare(that.probabilityOfSample, probabilityOfSample) == 0;
        }
        
        @Override
        public int hashCode() {
            return java.util.Objects.hash(number, probabilityOfSample);
        }
        
        @Override
        public String toString() {
            return String.format("NumAndProbability{number=%d, probability=%.3f}", 
                               number, probabilityOfSample);
        }
    }
}
