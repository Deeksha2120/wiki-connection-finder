package com.example.wikiconnectionfinder.service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.example.wikiconnectionfinder.client.ArticleLinksData;
import com.example.wikiconnectionfinder.client.CanonicalArticle;
import com.example.wikiconnectionfinder.client.PageResolutionResult;
import com.example.wikiconnectionfinder.client.TitleBatchLinksResult;
import com.example.wikiconnectionfinder.client.WikipediaClient;
import com.example.wikiconnectionfinder.client.WikipediaRequestContext;
import com.example.wikiconnectionfinder.dto.ConnectionPathArticle;
import com.example.wikiconnectionfinder.dto.ConnectionLimitReason;
import com.example.wikiconnectionfinder.dto.ConnectionResponse;
import com.example.wikiconnectionfinder.dto.ConnectionStatus;
import com.example.wikiconnectionfinder.exception.ArticleNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConnectionService {
    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    private static final int BATCH_SIZE = 50;

    private final WikipediaClient wikipediaClient;
    private final int maxDepth;
    private final int maxExpandedArticles;
    private final Duration maxDuration;
    private final LongSupplier nanoTime;

    @Autowired
    public ConnectionService(WikipediaClient wikipediaClient,
            @Value("${connection-search.max-depth:6}") int maxDepth,
            @Value("${connection-search.max-expanded-articles:100}") int maxExpandedArticles,
            @Value("${connection-search.max-duration:30s}") Duration maxDuration) {
        this(wikipediaClient, maxDepth, maxExpandedArticles, maxDuration, System::nanoTime);
    }

    ConnectionService(WikipediaClient wikipediaClient, int maxDepth, int maxExpandedArticles,
            Duration maxDuration, LongSupplier nanoTime) {
        if (maxDepth < 1 || maxExpandedArticles < 1 || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("Connection search limits must be positive");
        }
        this.wikipediaClient = wikipediaClient;
        this.maxDepth = maxDepth;
        this.maxExpandedArticles = maxExpandedArticles;
        this.maxDuration = maxDuration;
        this.nanoTime = nanoTime;
    }

    public ConnectionResponse findConnection(long sourcePageId, long targetPageId) {
        long startedAt = nanoTime.getAsLong();
        long durationNanos = maxDuration.toNanos();
        BooleanSupplier deadlineExceeded = () -> nanoTime.getAsLong() - startedAt >= durationNanos;
        WikipediaRequestContext requestContext = new WikipediaRequestContext(deadlineExceeded);
        String outcome = "ERROR";
        try {
            ConnectionResponse response = findConnection(sourcePageId, targetPageId, requestContext, deadlineExceeded);
            outcome = response.status().name();
            return response;
        } finally {
            long elapsedMillis = Math.max(0, nanoTime.getAsLong() - startedAt) / 1_000_000;
            log.debug("BFS Wikipedia metrics: outcome={}, elapsedMs={}, total={}, titleResolution={}, cacheHits={}, http429={}",
                    outcome, elapsedMillis, requestContext.totalRequests(),
                    requestContext.titleResolutionRequests(), requestContext.cacheHits(),
                    requestContext.rateLimitResponses());
        }
    }

    private ConnectionResponse findConnection(long sourcePageId, long targetPageId,
            WikipediaRequestContext requestContext, BooleanSupplier deadlineExceeded) {
        PageResolutionResult<Long> endpoints = wikipediaClient.resolvePageIds(
                List.of(sourcePageId, targetPageId), requestContext);
        if (!endpoints.complete()) {
            return complete(requestContext, ConnectionStatus.LIMIT_REACHED,
                    ConnectionLimitReason.INCOMPLETE_DATA, List.of(), null, 0, false);
        }

        CanonicalArticle source = endpoints.articles().get(sourcePageId);
        CanonicalArticle target = endpoints.articles().get(targetPageId);
        if (source == null) {
            throw new ArticleNotFoundException(sourcePageId);
        }
        if (target == null) {
            throw new ArticleNotFoundException(targetPageId);
        }
        if (source.pageId() == target.pageId()) {
            return complete(requestContext, ConnectionStatus.FOUND, null,
                    List.of(pathArticle(source)), 0, 0, true);
        }

        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<String> queuedTitles = new HashSet<>();
        Set<Long> expandedCanonicalIds = new HashSet<>();
        Map<Long, Long> parents = new HashMap<>();
        Map<Long, CanonicalArticle> articles = new HashMap<>();
        queue.add(new Node(source.title(), 0, null));
        queuedTitles.add(source.title());
        articles.put(source.pageId(), source);
        articles.put(target.pageId(), target);

        int expanded = 0;
        boolean incomplete = false;

        while (!queue.isEmpty()) {
            int depth = queue.peek().depth();
            if (depth >= maxDepth) {
                return complete(requestContext, ConnectionStatus.LIMIT_REACHED,
                        ConnectionLimitReason.DEPTH_LIMIT, List.of(), null, expanded, false);
            }
            if (deadlineExceeded.getAsBoolean()) {
                return complete(requestContext, ConnectionStatus.LIMIT_REACHED,
                        ConnectionLimitReason.TIME_LIMIT, List.of(), null, expanded, false);
            }

            int remainingBudget = maxExpandedArticles - expanded;
            if (remainingBudget == 0) {
                return complete(requestContext, ConnectionStatus.LIMIT_REACHED,
                        ConnectionLimitReason.EXPANSION_LIMIT, List.of(), null, expanded, false);
            }

            List<Node> batch = new ArrayList<>(Math.min(BATCH_SIZE, remainingBudget));
            while (!queue.isEmpty() && queue.peek().depth() == depth
                    && batch.size() < BATCH_SIZE && batch.size() < remainingBudget) {
                batch.add(queue.remove());
            }
            log.debug("BFS depth={}, batchSize={}, expandedArticles={}", depth, batch.size(), expanded);

            List<String> requestedTitles = batch.stream().map(Node::title).toList();
            TitleBatchLinksResult linkResult = wikipediaClient.getOutgoingLinksByTitles(
                    requestedTitles, requestContext);
            incomplete |= !linkResult.complete();
            boolean targetFound = false;

            for (Node node : batch) {
                ArticleLinksData data = linkResult.articles().get(node.title());
                if (data == null) {
                    continue;
                }
                if (!data.complete()) {
                    incomplete = true;
                }
                CanonicalArticle current = data.article();
                articles.put(current.pageId(), current);
                if (node.parentPageId() != null) {
                    parents.putIfAbsent(current.pageId(), node.parentPageId());
                }
                if (current.pageId() == target.pageId()) {
                    targetFound = true;
                    continue;
                }
                if (!expandedCanonicalIds.add(current.pageId())) {
                    requestContext.recordCacheHits(1);
                    continue;
                }
                expanded++;
                for (String title : data.linkedTitles()) {
                    if (title.equals(target.title())) {
                        parents.putIfAbsent(target.pageId(), current.pageId());
                        targetFound = true;
                        continue;
                    }
                    if (queuedTitles.add(title)) {
                        queue.add(new Node(title, depth + 1, current.pageId()));
                    } else {
                        requestContext.recordCacheHits(1);
                    }
                }
            }

            if (targetFound) {
                List<ConnectionPathArticle> path = reconstructPath(
                        source.pageId(), target.pageId(), parents, articles);
                return complete(requestContext, ConnectionStatus.FOUND, null, path, path.size() - 1,
                        expanded, !incomplete);
            }

            if (deadlineExceeded.getAsBoolean()) {
                return complete(requestContext, ConnectionStatus.LIMIT_REACHED,
                        ConnectionLimitReason.TIME_LIMIT, List.of(), null, expanded, false);
            }
        }

        return incomplete
                ? complete(requestContext, ConnectionStatus.LIMIT_REACHED,
                        ConnectionLimitReason.INCOMPLETE_DATA, List.of(), null, expanded, false)
                : complete(requestContext, ConnectionStatus.NOT_FOUND, null,
                        List.of(), null, expanded, true);
    }

    private List<ConnectionPathArticle> reconstructPath(long sourceId, long targetId,
            Map<Long, Long> parents, Map<Long, CanonicalArticle> articles) {
        ArrayDeque<ConnectionPathArticle> path = new ArrayDeque<>();
        long current = targetId;
        while (true) {
            path.addFirst(pathArticle(articles.get(current)));
            if (current == sourceId) {
                return List.copyOf(path);
            }
            current = parents.get(current);
        }
    }

    private ConnectionPathArticle pathArticle(CanonicalArticle article) {
        return new ConnectionPathArticle(article.pageId(), article.title());
    }

    private ConnectionResponse complete(WikipediaRequestContext requestContext,
            ConnectionStatus status, ConnectionLimitReason limitReason, List<ConnectionPathArticle> path,
            Integer degrees, int expanded, boolean guaranteed) {
        log.debug("BFS completed: status={}, limitReason={}, expandedArticles={}, shortestPathGuaranteed={}",
                status, limitReason, expanded, guaranteed);
        return new ConnectionResponse(status, limitReason, path, degrees, expanded, guaranteed);
    }

    private record Node(String title, int depth, Long parentPageId) {
    }
}
