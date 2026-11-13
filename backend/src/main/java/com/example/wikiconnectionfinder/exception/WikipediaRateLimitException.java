package com.example.wikiconnectionfinder.exception;

public class WikipediaRateLimitException extends WikipediaUpstreamException {
    public WikipediaRateLimitException(Throwable cause) {
        super("Wikipedia is rate limiting requests; please try again later", cause, false);
    }
}
