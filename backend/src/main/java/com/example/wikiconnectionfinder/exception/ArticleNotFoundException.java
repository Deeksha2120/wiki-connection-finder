package com.example.wikiconnectionfinder.exception;

public class ArticleNotFoundException extends RuntimeException {
    public ArticleNotFoundException(long pageId) {
        super("Wikipedia article with page ID " + pageId + " was not found");
    }
}
