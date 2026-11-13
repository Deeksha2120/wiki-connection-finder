package com.example.wikiconnectionfinder.client;

import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.example.wikiconnectionfinder.dto.ArticleLink;
import com.example.wikiconnectionfinder.dto.ArticleLinksResponse;
import com.example.wikiconnectionfinder.dto.ArticleSummary;
import com.example.wikiconnectionfinder.exception.ArticleNotFoundException;
import com.example.wikiconnectionfinder.exception.WikipediaRateLimitException;
import com.example.wikiconnectionfinder.exception.WikipediaUpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class WikipediaClient {
    private static final Logger log = LoggerFactory.getLogger(WikipediaClient.class);
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MILLIS = 500;
    private static final long MAX_RETRY_DELAY_MILLIS = 2_000;

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
        BatchLinksResult result = getOutgoingLinksBatch(List.of(pageId), () -> false);
        ArticleLinksData data = result.articles().get(pageId);
        if (data == null) {
            throw new ArticleNotFoundException(pageId);
        }
        List<ArticleLink> links = data.linkedTitles().stream().map(ArticleLink::new).toList();
        boolean truncated = !data.complete();
        log.debug("Fetched {} outgoing links for pageId={}, truncated={}", links.size(), pageId, truncated);
        return new ArticleLinksResponse(pageId, data.article().title(), links, truncated);
    }

    public BatchLinksResult getOutgoingLinksBatch(List<Long> pageIds, BooleanSupplier deadlineExceeded) {
        return getOutgoingLinksBatch(pageIds, new WikipediaRequestContext(deadlineExceeded));
    }

    public BatchLinksResult getOutgoingLinksBatch(List<Long> pageIds, WikipediaRequestContext context) {
        if (pageIds.size() > 50) {
            throw new IllegalArgumentException("At most 50 page IDs may be requested");
        }
        log.debug("Fetching Wikipedia outgoing links batch: size={}", pageIds.size());
        Map<Long, LinkAccumulator> accumulated = new LinkedHashMap<>();
        String plcontinue = null;
        String genericContinue = null;
        boolean complete = true;

        do {
            if (context.deadlineExceeded()) {
                complete = false;
                break;
            }
            JsonNode response = getLinksPage(pageIds, plcontinue, genericContinue, context);
            JsonNode pages = response.path("query").path("pages");
            if (!pages.isArray() || pages.isEmpty()) {
                throw new WikipediaUpstreamException("Wikipedia returned an invalid response");
            }

            for (JsonNode page : pages) {
                if (page.path("missing").asBoolean(false) || !page.has("pageid") || page.path("ns").asInt(-1) != 0) {
                    continue;
                }
                long pageId = page.path("pageid").asLong();
                LinkAccumulator accumulator = accumulated.computeIfAbsent(pageId,
                        ignored -> new LinkAccumulator(new CanonicalArticle(pageId, page.path("title").asText())));
                JsonNode pageLinks = page.path("links");
                if (pageLinks.isArray()) {
                    for (JsonNode link : pageLinks) {
                        if (accumulator.titles.size() == maxOutgoingLinks) {
                            accumulator.truncated = true;
                            complete = false;
                        } else {
                            accumulator.titles.add(link.path("title").asText());
                        }
                    }
                }
            }

            JsonNode continuation = response.path("continue");
            plcontinue = continuation.path("plcontinue").asText(null);
            genericContinue = continuation.path("continue").asText(null);
            if (plcontinue != null && accumulated.keySet().containsAll(pageIds)
                    && accumulated.values().stream().allMatch(value -> value.titles.size() >= maxOutgoingLinks)) {
                accumulated.values().forEach(value -> value.truncated = true);
                complete = false;
                break;
            }
        } while (plcontinue != null);

        Map<Long, ArticleLinksData> results = new LinkedHashMap<>();
        for (Map.Entry<Long, LinkAccumulator> entry : accumulated.entrySet()) {
            LinkAccumulator value = entry.getValue();
            results.put(entry.getKey(), new ArticleLinksData(value.article, List.copyOf(value.titles),
                    complete && !value.truncated));
        }
        log.debug("Fetched outgoing links batch: requested={}, returned={}, complete={}",
                pageIds.size(), results.size(), complete);
        return new BatchLinksResult(Map.copyOf(results), complete);
    }

    public TitleBatchLinksResult getOutgoingLinksByTitles(List<String> requestedTitles,
            WikipediaRequestContext context) {
        if (requestedTitles.size() > 50) {
            throw new IllegalArgumentException("At most 50 titles may be requested");
        }
        log.debug("Fetching Wikipedia outgoing links by title: size={}", requestedTitles.size());
        Map<Long, LinkAccumulator> accumulated = new LinkedHashMap<>();
        Map<String, String> aliases = new HashMap<>();
        String plcontinue = null;
        String genericContinue = null;
        boolean complete = true;

        do {
            if (context.deadlineExceeded()) {
                complete = false;
                break;
            }
            JsonNode response = getLinksPageByTitles(
                    requestedTitles, plcontinue, genericContinue, context);
            if (response == null) {
                complete = false;
                break;
            }
            addAliases(response.path("query").path("normalized"), aliases);
            addAliases(response.path("query").path("redirects"), aliases);
            JsonNode pages = response.path("query").path("pages");
            if (!pages.isArray()) {
                throw new WikipediaUpstreamException("Wikipedia returned an invalid response");
            }
            for (JsonNode page : pages) {
                if (page.path("missing").asBoolean(false) || !page.has("pageid")
                        || page.path("ns").asInt(-1) != 0) {
                    continue;
                }
                long pageId = page.path("pageid").asLong();
                LinkAccumulator accumulator = accumulated.computeIfAbsent(pageId,
                        ignored -> new LinkAccumulator(
                                new CanonicalArticle(pageId, page.path("title").asText())));
                JsonNode pageLinks = page.path("links");
                if (pageLinks.isArray()) {
                    for (JsonNode link : pageLinks) {
                        if (accumulator.titles.size() == maxOutgoingLinks) {
                            accumulator.truncated = true;
                            complete = false;
                        } else {
                            accumulator.titles.add(link.path("title").asText());
                        }
                    }
                }
            }
            JsonNode continuation = response.path("continue");
            plcontinue = continuation.path("plcontinue").asText(null);
            genericContinue = continuation.path("continue").asText(null);
            if (plcontinue != null && !accumulated.isEmpty()
                    && accumulated.values().stream().allMatch(value -> value.titles.size() >= maxOutgoingLinks)) {
                accumulated.values().forEach(value -> value.truncated = true);
                complete = false;
                break;
            }
        } while (plcontinue != null);

        Map<String, ArticleLinksData> results = new LinkedHashMap<>();
        Map<String, LinkAccumulator> byCanonicalTitle = new HashMap<>();
        accumulated.values().forEach(value -> byCanonicalTitle.put(value.article.title(), value));
        for (String requestedTitle : requestedTitles) {
            LinkAccumulator value = byCanonicalTitle.get(followAliases(requestedTitle, aliases));
            if (value != null) {
                results.put(requestedTitle, new ArticleLinksData(value.article,
                        List.copyOf(value.titles), complete && !value.truncated));
            }
        }
        log.debug("Fetched outgoing links by title: requested={}, returned={}, complete={}",
                requestedTitles.size(), results.size(), complete);
        return new TitleBatchLinksResult(Map.copyOf(results), complete);
    }

    public PageResolutionResult<Long> resolvePageIds(List<Long> pageIds, BooleanSupplier deadlineExceeded) {
        return resolvePageIds(pageIds, new WikipediaRequestContext(deadlineExceeded));
    }

    public PageResolutionResult<Long> resolvePageIds(List<Long> pageIds, WikipediaRequestContext context) {
        if (pageIds.size() > 50) {
            throw new IllegalArgumentException("At most 50 page IDs may be requested");
        }
        if (context.deadlineExceeded()) {
            return new PageResolutionResult<>(Map.of(), false);
        }
        JsonNode response = getPageInfoByIds(pageIds, context);
        Map<Long, CanonicalArticle> resolved = new LinkedHashMap<>();
        Map<Long, String> redirects = new LinkedHashMap<>();
        JsonNode pages = response.path("query").path("pages");
        if (!pages.isArray()) {
            throw new WikipediaUpstreamException("Wikipedia returned an invalid response");
        }
        for (JsonNode page : pages) {
            if (!page.has("pageid") || page.path("missing").asBoolean(false) || page.path("ns").asInt(-1) != 0) {
                continue;
            }
            long id = page.path("pageid").asLong();
            if (page.path("redirect").asBoolean(false)) {
                redirects.put(id, page.path("title").asText());
            } else {
                resolved.put(id, new CanonicalArticle(id, page.path("title").asText()));
            }
        }
        if (!redirects.isEmpty()) {
            PageResolutionResult<String> redirectTargets = resolveTitles(
                    new ArrayList<>(redirects.values()), context);
            for (Map.Entry<Long, String> redirect : redirects.entrySet()) {
                CanonicalArticle target = redirectTargets.articles().get(redirect.getValue());
                if (target != null) {
                    resolved.put(redirect.getKey(), target);
                }
            }
            return new PageResolutionResult<>(Map.copyOf(resolved), redirectTargets.complete());
        }
        return new PageResolutionResult<>(Map.copyOf(resolved), true);
    }

    public PageResolutionResult<String> resolveTitles(List<String> titles, BooleanSupplier deadlineExceeded) {
        return resolveTitles(titles, new WikipediaRequestContext(deadlineExceeded));
    }

    public PageResolutionResult<String> resolveTitles(List<String> titles, WikipediaRequestContext context) {
        Map<String, CanonicalArticle> resolved = new LinkedHashMap<>();
        boolean complete = true;
        for (int start = 0; start < titles.size(); start += 50) {
            if (context.deadlineExceeded()) {
                complete = false;
                break;
            }
            List<String> batch = titles.subList(start, Math.min(start + 50, titles.size()));
            log.debug("Resolving Wikipedia title batch: batch={}, size={}", start / 50 + 1, batch.size());
            JsonNode response = getPageInfoByTitles(batch, context);
            if (response == null) {
                complete = false;
                break;
            }
            Map<String, String> aliases = new HashMap<>();
            addAliases(response.path("query").path("normalized"), aliases);
            addAliases(response.path("query").path("redirects"), aliases);

            Map<String, CanonicalArticle> byTitle = new HashMap<>();
            JsonNode pages = response.path("query").path("pages");
            if (!pages.isArray()) {
                throw new WikipediaUpstreamException("Wikipedia returned an invalid response");
            }
            for (JsonNode page : pages) {
                if (page.has("pageid") && !page.path("missing").asBoolean(false)
                        && page.path("ns").asInt(-1) == 0) {
                    CanonicalArticle article = new CanonicalArticle(
                            page.path("pageid").asLong(), page.path("title").asText());
                    byTitle.put(article.title(), article);
                }
            }
            for (String requested : batch) {
                String canonicalTitle = followAliases(requested, aliases);
                CanonicalArticle article = byTitle.get(canonicalTitle);
                if (article != null) {
                    resolved.put(requested, article);
                }
            }
        }
        return new PageResolutionResult<>(Map.copyOf(resolved), complete);
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

    private JsonNode getLinksPage(List<Long> pageIds, String plcontinue, String genericContinue,
            WikipediaRequestContext context) {
        return execute(() -> restClient.get().uri(uri -> {
            var builder = uri
                    .queryParam("action", "query")
                    .queryParam("format", "json")
                    .queryParam("formatversion", "2")
                    .queryParam("pageids", join(pageIds))
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
        }).retrieve().body(JsonNode.class), context, false);
    }

    private JsonNode getLinksPageByTitles(List<String> titles, String plcontinue,
            String genericContinue, WikipediaRequestContext context) {
        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try {
                return execute(() -> restClient.get().uri(uri -> {
                    var builder = uri
                            .queryParam("action", "query")
                            .queryParam("format", "json")
                            .queryParam("formatversion", "2")
                            .queryParam("titles", String.join("|", titles))
                            .queryParam("redirects", "1")
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
                }).retrieve().body(JsonNode.class), context, false);
            } catch (WikipediaUpstreamException exception) {
                RestClientResponseException responseException = responseException(exception);
                if (responseException == null || responseException.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS
                        || attempt == MAX_RATE_LIMIT_RETRIES) {
                    if (responseException != null
                            && responseException.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                        throw new WikipediaRateLimitException(responseException);
                    }
                    throw exception;
                }
                if (!waitBeforeRetry(responseException, context, attempt + 1)) {
                    return null;
                }
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private JsonNode getPageInfoByIds(List<Long> pageIds, WikipediaRequestContext context) {
        return execute(() -> restClient.get().uri(uri -> uri
                .queryParam("action", "query")
                .queryParam("format", "json")
                .queryParam("formatversion", "2")
                .queryParam("pageids", join(pageIds))
                .queryParam("prop", "info")
                .build()).retrieve().body(JsonNode.class), context, false);
    }

    private JsonNode getPageInfoByTitles(List<String> titles, WikipediaRequestContext context) {
        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try {
                return execute(() -> restClient.get().uri(uri -> uri
                        .queryParam("action", "query")
                        .queryParam("format", "json")
                        .queryParam("formatversion", "2")
                        .queryParam("titles", String.join("|", titles))
                        .queryParam("redirects", "1")
                        .queryParam("prop", "info")
                        .build()).retrieve().body(JsonNode.class), context, true);
            } catch (WikipediaUpstreamException exception) {
                RestClientResponseException responseException = responseException(exception);
                if (responseException == null || responseException.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS
                        || attempt == MAX_RATE_LIMIT_RETRIES) {
                    if (responseException != null
                            && responseException.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                        throw new WikipediaRateLimitException(responseException);
                    }
                    throw exception;
                }
                if (!waitBeforeRetry(responseException, context, attempt + 1)) {
                    return null;
                }
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private RestClientResponseException responseException(WikipediaUpstreamException exception) {
        return exception.getCause() instanceof RestClientResponseException responseException
                ? responseException : null;
    }

    private boolean waitBeforeRetry(RestClientResponseException exception,
            WikipediaRequestContext context, int retryNumber) {
        if (context.deadlineExceeded()) {
            return false;
        }
        long delayMillis = retryDelayMillis(exception);
        log.debug("Retrying Wikipedia title resolution after HTTP 429: retry={}, delayMs={}",
                retryNumber, delayMillis);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WikipediaUpstreamException("Wikipedia request failed", interrupted, false);
        }
        return !context.deadlineExceeded();
    }

    private long retryDelayMillis(RestClientResponseException exception) {
        String retryAfter = exception.getResponseHeaders() == null
                ? null : exception.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter == null) {
            return DEFAULT_RETRY_DELAY_MILLIS;
        }
        try {
            return Math.min(Math.multiplyExact(Long.parseLong(retryAfter), 1_000), MAX_RETRY_DELAY_MILLIS);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return DEFAULT_RETRY_DELAY_MILLIS;
        }
    }

    private String join(List<Long> pageIds) {
        return pageIds.stream().map(String::valueOf).reduce((left, right) -> left + "|" + right).orElse("");
    }

    private void addAliases(JsonNode values, Map<String, String> aliases) {
        if (values.isArray()) {
            values.forEach(value -> aliases.put(value.path("from").asText(), value.path("to").asText()));
        }
    }

    private String followAliases(String title, Map<String, String> aliases) {
        String current = title;
        Set<String> seen = new HashSet<>();
        while (aliases.containsKey(current) && seen.add(current)) {
            current = aliases.get(current);
        }
        return current;
    }

    private JsonNode execute(ResponseSupplier supplier) {
        return execute(supplier, null, false);
    }

    private JsonNode execute(ResponseSupplier supplier, WikipediaRequestContext context, boolean titleResolution) {
        try {
            if (context != null) {
                context.recordRequest(titleResolution);
            }
            JsonNode response = supplier.get();
            log.trace("Wikipedia response: {}", response);
            if (response == null || response.isNull()) {
                throw new WikipediaUpstreamException("Wikipedia returned an empty response");
            }
            if (response.has("error")) {
                log.error("Wikipedia API error: code={}, info={}",
                        response.path("error").path("code").asText("unknown"),
                        response.path("error").path("info").asText("unavailable"));
                throw new WikipediaUpstreamException("Wikipedia API returned an error");
            }
            return response;
        } catch (WikipediaUpstreamException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (context != null && exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                context.recordRateLimitResponse();
                log.debug("Wikipedia returned HTTP 429: count={}", context.rateLimitResponses());
            }
            log.error("Wikipedia HTTP request failed with status {}", exception.getStatusCode(), exception);
            throw new WikipediaUpstreamException("Wikipedia request failed", exception, false);
        } catch (RestClientException exception) {
            boolean timeout = hasCause(exception, HttpTimeoutException.class);
            log.error("Wikipedia request failed without an HTTP response (timeout={})", timeout, exception);
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

    private static final class LinkAccumulator {
        private final CanonicalArticle article;
        private final List<String> titles = new ArrayList<>();
        private boolean truncated;

        private LinkAccumulator(CanonicalArticle article) {
            this.article = article;
        }
    }
}
