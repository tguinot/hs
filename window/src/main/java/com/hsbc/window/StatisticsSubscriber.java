package com.hsbc.window;

public interface StatisticsSubscriber {
    boolean shouldNotify(Statistics stats);
    void onStatisticsUpdate(Statistics stats);
}
