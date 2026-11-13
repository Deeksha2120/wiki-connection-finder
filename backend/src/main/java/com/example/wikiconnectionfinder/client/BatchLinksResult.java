package com.example.wikiconnectionfinder.client;

import java.util.Map;

public record BatchLinksResult(Map<Long, ArticleLinksData> articles, boolean complete) {
}
