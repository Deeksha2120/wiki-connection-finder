package com.example.wikiconnectionfinder.client;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.wikiconnectionfinder.dto.ArticleLink;
import com.example.wikiconnectionfinder.dto.ArticleLinksResponse;
import com.example.wikiconnectionfinder.dto.ArticleSummary;
import com.example.wikiconnectionfinder.exception.ArticleNotFoundException;
import com.example.wikiconnectionfinder.exception.WikipediaUpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WikipediaClient {
    private static final Logger log = LoggerFactory.getLogger(WikipediaClient.class);

    private final RestClient restClient;
    private final int maxOutgoingLinks;

    public WikipediaClient(RestClient wikipediaRestClient,
            @Value("${wikipedia.max-outgoing-links:5000}") int maxOutgoingLinks) {
        if (maxOutgoingLinks < 1) {
            throw new IllegalArgumentException("wikipedia.max-outgoing-links must be positive");
        }
        this.restClient = wikipediaRestClient;
        this.maxOutgoingLinks = maxOutgoingLinks;
    }

    public List<ArticleSummary> search(String query, int limit) {
        log.debug("Searching Wikipedia articles: query='{}', limit={}", query, limit);
        JsonNode response = getSearchResponse(query, limit);
        JsonNode pages = response.path("query").path("pages");
        if (!pages.isArray()) {
            return List.of();
        }

        List<JsonNode> rankedPages = new ArrayList<>();
        pages.forEach(rankedPages::add);
        rankedPages.sort(Comparator.comparingInt(page -> page.path("index").asInt(Integer.MAX_VALUE)));

        return rankedPages.stream()
                .filter(page -> page.path("ns").asInt(-1) == 0 && page.has("pageid"))
                .map(page -> new ArticleSummary(
                        page.path("pageid").asLong(),
                        page.path("title").asText(),
                        page.path("pageprops").has("wikibase-shortdesc")
                                ? page.path("pageprops").path("wikibase-shortdesc").asText()
                                : null,
                        page.path("fullurl").asText()))
                .toList();
    }

    public ArticleLinksResponse getOutgoingLinks(long pageId) {
        log.debug("Fetching Wikipedia outgoing links: pageId={}", pageId);
        List<ArticleLink> links = new ArrayList<>();
        String plcontinue = null;
        String genericContinue = null;
        String title = null;

        do {
            JsonNode response = getLinksPage(pageId, plcontinue, genericContinue);
            JsonNode pages = response.path("query").path("pages");
            if (!pages.isArray() || pages.isEmpty()) {
                throw new WikipediaUpstreamException("Wikipedia returned an invalid response");
            }

            JsonNode page = pages.get(0);
            if (page.path("missing").asBoolean(false) || !page.has("pageid") || page.path("ns").asInt(-1) != 0) {
                throw new ArticleNotFoundException(pageId);
            }
            if (title == null) {
                title = page.path("title").asText();
            }

            JsonNode pageLinks = page.path("links");
            if (pageLinks.isArray()) {
                for (JsonNode link : pageLinks) {
                    if (links.size() == maxOutgoingLinks) {
                        log.debug("Fetched {} outgoing links for pageId={}, truncated=true", links.size(), pageId);
                        return new ArticleLinksResponse(pageId, title, List.copyOf(links), true);
                    }
                    links.add(new ArticleLink(link.path("title").asText()));
                }
            }

            JsonNode continuation = response.path("continue");
            plcontinue = continuation.path("plcontinue").asText(null);
            genericContinue = continuation.path("continue").asText(null);
            if (links.size() == maxOutgoingLinks && plcontinue != null) {
                log.debug("Fetched {} outgoing links for pageId={}, truncated=true", links.size(), pageId);
                return new ArticleLinksResponse(pageId, title, List.copyOf(links), true);
            }
        } while (plcontinue != null);

        log.debug("Fetched {} outgoing links for pageId={}, truncated=false", links.size(), pageId);
        return new ArticleLinksResponse(pageId, title, List.copyOf(links), false);
    }

    private JsonNode getSearchResponse(String query, int limit) {
        return execute(() -> restClient.get().uri(uri -> uri
                .queryParam("action", "query")
                .queryParam("format", "json")
                .queryParam("formatversion", "2")
                .queryParam("generator", "search")
                .queryParam("gsrsearch", query)
                .queryParam("gsrnamespace", "0")
                .queryParam("gsrlimit", limit)
                .queryParam("prop", "info|pageprops")
                .queryParam("inprop", "url")
                .queryParam("ppprop", "wikibase-shortdesc")
                .build()).retrieve().body(JsonNode.class));
    }

    private JsonNode getLinksPage(long pageId, String plcontinue, String genericContinue) {
        return execute(() -> restClient.get().uri(uri -> {
            var builder = uri
                    .queryParam("action", "query")
                    .queryParam("format", "json")
                    .queryParam("formatversion", "2")
                    .queryParam("pageids", pageId)
                    .queryParam("prop", "links")
                    .queryParam("plnamespace", "0")
                    .queryParam("pllimit", "max");
            if (plcontinue != null) {
                builder.queryParam("plcontinue", plcontinue);
            }
            if (genericContinue != null) {
                builder.queryParam("continue", genericContinue);
            }
            return builder.build();
        }).retrieve().body(JsonNode.class));
    }

    private JsonNode execute(ResponseSupplier supplier) {
        try {
            JsonNode response = supplier.get();
            log.trace("Wikipedia response: {}", response);
            if (response == null || response.isNull()) {
                throw new WikipediaUpstreamException("Wikipedia returned an empty response");
            }
            if (response.has("error")) {
                throw new WikipediaUpstreamException("Wikipedia API returned an error");
            }
            return response;
        } catch (WikipediaUpstreamException exception) {
            throw exception;
        } catch (RestClientException exception) {
            boolean timeout = hasCause(exception, HttpTimeoutException.class);
            throw new WikipediaUpstreamException(
                    timeout ? "Wikipedia request timed out" : "Wikipedia request failed", exception, timeout);
        }
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface ResponseSupplier {
        JsonNode get();
    }
}
