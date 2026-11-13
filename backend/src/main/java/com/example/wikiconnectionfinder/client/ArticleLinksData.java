package com.example.wikiconnectionfinder.client;

import java.util.List;

public record ArticleLinksData(CanonicalArticle article, List<String> linkedTitles, boolean complete) {
}
