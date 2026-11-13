package com.example.wikiconnectionfinder.client;

import java.util.function.BooleanSupplier;

public final class WikipediaRequestContext {
    private final BooleanSupplier deadlineExceeded;
    private int totalRequests;
    private int titleResolutionRequests;
    private int cacheHits;
    private int rateLimitResponses;

    public WikipediaRequestContext(BooleanSupplier deadlineExceeded) {
        this.deadlineExceeded = deadlineExceeded;
    }

    public boolean deadlineExceeded() {
        return deadlineExceeded.getAsBoolean();
    }

    void recordRequest(boolean titleResolution) {
        totalRequests++;
        if (titleResolution) {
            titleResolutionRequests++;
        }
    }

    void recordRateLimitResponse() {
        rateLimitResponses++;
    }

    public void recordCacheHits(int count) {
        cacheHits += count;
    }

    public int totalRequests() {
        return totalRequests;
    }

    public int titleResolutionRequests() {
        return titleResolutionRequests;
    }

    public int cacheHits() {
        return cacheHits;
    }

    public int rateLimitResponses() {
        return rateLimitResponses;
    }
}
