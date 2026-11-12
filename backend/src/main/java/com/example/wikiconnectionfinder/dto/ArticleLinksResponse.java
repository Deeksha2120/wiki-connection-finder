package com.example.wikiconnectionfinder.dto;

import java.util.List;

public record ArticleLinksResponse(long pageId, String title, List<ArticleLink> links, boolean truncated) {
}
