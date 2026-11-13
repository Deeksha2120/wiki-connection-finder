package com.example.wikiconnectionfinder.client;

import java.util.Map;

public record TitleBatchLinksResult(Map<String, ArticleLinksData> articles, boolean complete) {
}
