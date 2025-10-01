package com.hsbc.window;

import java.util.Map;

public interface Statistics {
    double getMean();
    double getStandardDeviation();
    double getMedian();
    long getMode();
    double getPctile(int pctile);
    double getKurtosis();
    double getSkewness();
    Map<Double, Double> getQuantiles();
}

