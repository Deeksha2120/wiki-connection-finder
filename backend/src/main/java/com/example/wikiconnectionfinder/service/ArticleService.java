package com.example.wikiconnectionfinder.service;

import com.example.wikiconnectionfinder.client.WikipediaClient;
import com.example.wikiconnectionfinder.dto.ArticleLinksResponse;
import com.example.wikiconnectionfinder.dto.ArticleSearchResponse;
import org.springframework.stereotype.Service;

@Service
public class ArticleService {
    private final WikipediaClient wikipediaClient;

    public ArticleService(WikipediaClient wikipediaClient) {
        this.wikipediaClient = wikipediaClient;
    }

    public ArticleSearchResponse search(String query, int limit) {
        return new ArticleSearchResponse(wikipediaClient.search(query, limit));
    }

    public ArticleLinksResponse getOutgoingLinks(long pageId) {
        return wikipediaClient.getOutgoingLinks(pageId);
    }
}
