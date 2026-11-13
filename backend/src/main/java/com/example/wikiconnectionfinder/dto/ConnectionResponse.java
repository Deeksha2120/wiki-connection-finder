package com.example.wikiconnectionfinder.dto;

import java.util.List;

/**
 * A false shortestPathGuaranteed value is conservative: incomplete Wikipedia
 * data may make the guarantee unavailable even when the returned path is shortest.
 */
public record ConnectionResponse(
        ConnectionStatus status,
        ConnectionLimitReason limitReason,
        List<ConnectionPathArticle> path,
        Integer degrees,
        int expandedArticles,
        boolean shortestPathGuaranteed) {
}
