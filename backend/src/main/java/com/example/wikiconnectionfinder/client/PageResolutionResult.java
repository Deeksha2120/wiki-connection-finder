package com.example.wikiconnectionfinder.client;

import java.util.Map;

public record PageResolutionResult<K>(Map<K, CanonicalArticle> articles, boolean complete) {
}
