package com.hsbc.window;

public interface SlidingWindowStatistics {
    void add(long measurement);
    void subscribeForStatistics(StatisticsSubscriber subscriber);
    void unsubscribeForStatistics(StatisticsSubscriber subscriber);
    Statistics getLatestStatistics();
}