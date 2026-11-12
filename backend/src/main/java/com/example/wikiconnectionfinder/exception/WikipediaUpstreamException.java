package com.example.wikiconnectionfinder.exception;

public class WikipediaUpstreamException extends RuntimeException {
    private final boolean timeout;

    public WikipediaUpstreamException(String message, Throwable cause, boolean timeout) {
        super(message, cause);
        this.timeout = timeout;
    }

    public WikipediaUpstreamException(String message) {
        this(message, null, false);
    }

    public boolean isTimeout() {
        return timeout;
    }
}
