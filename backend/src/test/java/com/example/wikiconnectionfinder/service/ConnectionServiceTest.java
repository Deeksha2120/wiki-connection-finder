package com.example.wikiconnectionfinder.service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.example.wikiconnectionfinder.client.ArticleLinksData;
import com.example.wikiconnectionfinder.client.CanonicalArticle;
import com.example.wikiconnectionfinder.client.PageResolutionResult;
import com.example.wikiconnectionfinder.client.TitleBatchLinksResult;
import com.example.wikiconnectionfinder.client.WikipediaClient;
import com.example.wikiconnectionfinder.client.WikipediaRequestContext;
import com.example.wikiconnectionfinder.dto.ConnectionStatus;
import com.example.wikiconnectionfinder.dto.ConnectionLimitReason;
import com.example.wikiconnectionfinder.exception.ArticleNotFoundException;
import com.example.wikiconnectionfinder.exception.WikipediaRateLimitException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionServiceTest {
    @Mock
    private WikipediaClient client;

    private final Map<Long, CanonicalArticle> articles = new LinkedHashMap<>();
    private final Map<Long, List<Long>> graph = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        for (long id = 1; id <= 120; id++) {
            articles.put(id, new CanonicalArticle(id, "Article " + id));
        }
        lenient().when(client.resolvePageIds(anyList(), any(WikipediaRequestContext.class))).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            Map<Long, CanonicalArticle> result = new LinkedHashMap<>();
            ids.forEach(id -> {
                if (articles.containsKey(id)) {
                    result.put(id, articles.get(id));
                }
            });
            return new PageResolutionResult<>(result, true);
        });
        lenient().when(client.getOutgoingLinksByTitles(anyList(), any(WikipediaRequestContext.class)))
                .thenAnswer(invocation -> titleLinks(invocation.getArgument(0), true));
    }

    @Test
    void sameSourceAndDestinationNeedsNoExpansion() {
        var response = service(6, 100).findConnection(1, 1);

        assertThat(response.status()).isEqualTo(ConnectionStatus.FOUND);
        assertThat(response.limitReason()).isNull();
        assertThat(response.degrees()).isZero();
        assertThat(response.expandedArticles()).isZero();
        verify(client, never()).getOutgoingLinksByTitles(anyList(), any(WikipediaRequestContext.class));
    }

    @Test
    void findsDirectLink() {
        graph.put(1L, List.of(2L));

        var response = service(6, 100).findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.FOUND);
        assertThat(response.degrees()).isEqualTo(1);
        assertThat(response.path()).extracting(item -> item.pageId()).containsExactly(1L, 2L);
        verify(client, never()).resolveTitles(anyList(), any(WikipediaRequestContext.class));
    }

    @Test
    void choosesShortestPathAndHandlesCyclesAndDuplicates() {
        graph.put(1L, List.of(3L, 3L, 4L));
        graph.put(3L, List.of(1L, 5L));
        graph.put(4L, List.of(6L));
        graph.put(5L, List.of(2L));
        graph.put(6L, List.of(2L));

        var response = service(6, 100).findConnection(1, 2);

        assertThat(response.degrees()).isEqualTo(3);
        assertThat(response.path()).extracting(item -> item.pageId()).containsExactly(1L, 3L, 5L, 2L);
    }

    @Test
    void stopsAtDepthLimitWithoutExpandingMaximumDepth() {
        graph.put(1L, List.of(3L));
        graph.put(3L, List.of(2L));

        var response = service(1, 100).findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.LIMIT_REACHED);
        assertThat(response.limitReason()).isEqualTo(ConnectionLimitReason.DEPTH_LIMIT);
        assertThat(response.expandedArticles()).isEqualTo(1);
    }

    @Test
    void respectsExpansionLimit() {
        graph.put(1L, List.of(3L, 4L));

        var response = service(6, 1).findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.LIMIT_REACHED);
        assertThat(response.limitReason()).isEqualTo(ConnectionLimitReason.EXPANSION_LIMIT);
        assertThat(response.expandedArticles()).isEqualTo(1);
    }

    @Test
    void checksDestinationAfterEachLevelBatch() {
        graph.put(1L, java.util.stream.LongStream.rangeClosed(3, 53).boxed().toList());
        graph.put(3L, List.of(2L));

        var response = service(6, 100).findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.FOUND);
        assertThat(response.expandedArticles()).isEqualTo(51);
    }

    @Test
    void incompleteDataCanFindPathButDoesNotGuaranteeShortestPath() {
        graph.put(1L, List.of(2L));
        when(client.getOutgoingLinksByTitles(anyList(), any(WikipediaRequestContext.class))).thenReturn(
                new TitleBatchLinksResult(Map.of("Article 1", links(1L, false)), false));

        var response = service(6, 100).findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.FOUND);
        assertThat(response.shortestPathGuaranteed()).isFalse();
    }

    @Test
    void incompleteDataCannotClaimNotFound() {
        when(client.getOutgoingLinksByTitles(anyList(), any(WikipediaRequestContext.class))).thenReturn(
                new TitleBatchLinksResult(Map.of("Article 1", links(1L, false)), false));

        var response = service(6, 100).findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.LIMIT_REACHED);
        assertThat(response.limitReason()).isEqualTo(ConnectionLimitReason.INCOMPLETE_DATA);
        assertThat(response.shortestPathGuaranteed()).isFalse();
    }

    @Test
    void durationLimitStopsBetweenUpstreamOperations() {
        AtomicLong time = new AtomicLong();
        when(client.getOutgoingLinksByTitles(anyList(), any(WikipediaRequestContext.class))).thenAnswer(invocation -> {
            time.set(10);
            return new TitleBatchLinksResult(Map.of("Article 1", links(1L, true)), true);
        });
        ConnectionService service = new ConnectionService(client, 6, 100, Duration.ofNanos(10), time::get);

        var response = service.findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.LIMIT_REACHED);
        assertThat(response.limitReason()).isEqualTo(ConnectionLimitReason.TIME_LIMIT);
        assertThat(response.expandedArticles()).isEqualTo(1);
    }

    @Test
    void missingEndpointReturnsNotFound() {
        articles.remove(2L);

        assertThatThrownBy(() -> service(6, 100).findConnection(1, 2))
                .isInstanceOf(ArticleNotFoundException.class);
    }

    @Test
    void duplicateTitlesAreQueuedOnlyOnceWithoutResolution() {
        graph.put(1L, List.of(3L, 3L, 4L));

        service(1, 100).findConnection(1, 2);

        verify(client, never()).resolveTitles(anyList(), any(WikipediaRequestContext.class));
    }

    @Test
    void reusesResolvedTitlesAcrossLevels() {
        graph.put(1L, List.of(3L, 5L));
        graph.put(3L, List.of(5L));

        service(3, 100).findConnection(1, 2);

        verify(client, never()).resolveTitles(anyList(), any(WikipediaRequestContext.class));
    }

    @Test
    void redirectAliasStillFindsCanonicalTarget() {
        graph.put(1L, List.of());
        when(client.getOutgoingLinksByTitles(anyList(), any(WikipediaRequestContext.class))).thenAnswer(invocation -> {
            List<String> titles = invocation.getArgument(0);
            if (titles.equals(List.of("Article 1"))) {
                return new TitleBatchLinksResult(Map.of("Article 1",
                        new ArticleLinksData(articles.get(1L), List.of("Target alias"), true)), true);
            }
            return new TitleBatchLinksResult(Map.of("Target alias",
                    new ArticleLinksData(articles.get(2L), List.of(), true)), true);
        });

        var response = service(6, 100).findConnection(1, 2);

        assertThat(response.status()).isEqualTo(ConnectionStatus.FOUND);
        assertThat(response.path()).extracting(item -> item.pageId()).containsExactly(1L, 2L);
    }

    @Test
    void logsRequestMetricsOnceOnSuccess() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConnectionService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            service(6, 100).findConnection(1, 1);

            assertThat(metricMessages(appender)).singleElement().satisfies(message -> {
                assertThat(message).contains("outcome=FOUND", "elapsedMs=", "total=0",
                        "titleResolution=0", "cacheHits=0", "http429=0");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void logsRequestMetricsOnceWhenSearchThrows() {
        when(client.resolvePageIds(anyList(), any(WikipediaRequestContext.class)))
                .thenThrow(new WikipediaRateLimitException(new RuntimeException("rate limited")));
        Logger logger = (Logger) LoggerFactory.getLogger(ConnectionService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> service(6, 100).findConnection(1, 2))
                    .isInstanceOf(WikipediaRateLimitException.class);

            assertThat(metricMessages(appender)).singleElement().satisfies(message -> {
                assertThat(message).contains("outcome=ERROR", "elapsedMs=", "total=0",
                        "titleResolution=0", "cacheHits=0", "http429=0");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private List<String> metricMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("BFS Wikipedia metrics:"))
                .toList();
    }

    private ConnectionService service(int maxDepth, int maxExpanded) {
        return new ConnectionService(client, maxDepth, maxExpanded, Duration.ofSeconds(30), System::nanoTime);
    }

    private ArticleLinksData links(long id, boolean complete) {
        List<String> titles = graph.getOrDefault(id, List.of()).stream()
                .map(linkedId -> articles.get(linkedId).title()).toList();
        return new ArticleLinksData(articles.get(id), titles, complete);
    }

    private TitleBatchLinksResult titleLinks(List<String> requestedTitles, boolean complete) {
        Map<String, ArticleLinksData> result = new LinkedHashMap<>();
        for (String requestedTitle : requestedTitles) {
            articles.values().stream()
                    .filter(article -> article.title().equals(requestedTitle))
                    .findFirst()
                    .ifPresent(article -> result.put(requestedTitle, links(article.pageId(), complete)));
        }
        return new TitleBatchLinksResult(result, complete);
    }
}
