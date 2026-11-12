package com.example.wikiconnectionfinder.dto;

import java.util.List;

public record ArticleSearchResponse(List<ArticleSummary> results) {
}
