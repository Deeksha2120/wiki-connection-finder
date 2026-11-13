package com.example.wikiconnectionfinder.client;

import com.example.wikiconnectionfinder.dto.ArticleLinksResponse;
import com.example.wikiconnectionfinder.exception.ArticleNotFoundException;
import com.example.wikiconnectionfinder.exception.WikipediaUpstreamException;
import com.example.wikiconnectionfinder.exception.WikipediaRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadGateway;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WikipediaClientTest {
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://en.wikipedia.org/w/api.php");
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    @Test
    void searchPreservesWikipediaRelevanceOrderAndNullableDescription() {
        server.expect(once(), method(HttpMethod.GET))
                .andExpect(queryParam("generator", "search"))
                .andExpect(queryParam("gsrsearch", "Java"))
                .andExpect(queryParam("gsrlimit", "2"))
                .andRespond(withSuccess("""
                        {"query":{"pages":[
                          {"pageid":2,"ns":0,"title":"Java island","index":2,"fullurl":"https://example/2"},
                          {"pageid":1,"ns":0,"title":"Java","index":1,"fullurl":"https://example/1",
                           "pageprops":{"wikibase-shortdesc":"Programming language"}}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        var results = new WikipediaClient(restClient, 100).search("Java", 2);

        assertThat(results).extracting(result -> result.title()).containsExactly("Java", "Java island");
        assertThat(results.get(0).description()).isEqualTo("Programming language");
        assertThat(results.get(1).description()).isNull();
        server.verify();
    }

    @Test
    void searchReturnsNoResults() {
        server.expect(once(), method(HttpMethod.GET))
                .andRespond(withSuccess("{\"batchcomplete\":true,\"query\":{\"pages\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(new WikipediaClient(restClient, 100).search("no match", 5)).isEmpty();
    }

    @Test
    void linksFollowPlcontinueAndResolveCurrentTitle() {
        server.expect(once(), queryParam("pageids", "123"))
                .andExpect(queryParam("plnamespace", "0"))
                .andRespond(withSuccess("""
                        {"continue":{"plcontinue":"123|0|Second","continue":"||"},
                         "query":{"pages":[{"pageid":123,"ns":0,"title":"Current title",
                         "links":[{"ns":0,"title":"First"}]}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), queryParam("plcontinue", "123%7C0%7CSecond"))
                .andExpect(queryParam("continue", "%7C%7C"))
                .andRespond(withSuccess("""
                        {"batchcomplete":true,"query":{"pages":[{"pageid":123,"ns":0,"title":"Current title",
                         "links":[{"ns":0,"title":"Second"}]}]}}
                        """, MediaType.APPLICATION_JSON));

        ArticleLinksResponse response = new WikipediaClient(restClient, 100).getOutgoingLinks(123);

        assertThat(response.title()).isEqualTo("Current title");
        assertThat(response.links()).extracting(link -> link.title()).containsExactly("First", "Second");
        assertThat(response.truncated()).isFalse();
        server.verify();
    }

    @Test
    void linksStopAtConfiguredSafetyLimitAndReportTruncation() {
        server.expect(once(), method(HttpMethod.GET)).andRespond(withSuccess("""
                {"continue":{"plcontinue":"123|0|Third","continue":"||"},
                 "query":{"pages":[{"pageid":123,"ns":0,"title":"Article",
                 "links":[{"ns":0,"title":"First"},{"ns":0,"title":"Second"}]}]}}
                """, MediaType.APPLICATION_JSON));

        ArticleLinksResponse response = new WikipediaClient(restClient, 2).getOutgoingLinks(123);

        assertThat(response.links()).hasSize(2);
        assertThat(response.truncated()).isTrue();
        server.verify();
    }

    @Test
    void linksReturnNotFoundForMissingPage() {
        server.expect(once(), method(HttpMethod.GET)).andRespond(withSuccess("""
                {"query":{"pages":[{"ns":0,"title":"123","missing":true}]}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new WikipediaClient(restClient, 100).getOutgoingLinks(123))
                .isInstanceOf(ArticleNotFoundException.class);
    }

    @Test
    void nullWikipediaResponseIsHandled() {
        server.expect(once(), method(HttpMethod.GET)).andRespond(withSuccess());

        assertThatThrownBy(() -> new WikipediaClient(restClient, 100).search("Java", 5))
                .isInstanceOf(WikipediaUpstreamException.class)
                .hasMessage("Wikipedia returned an empty response");
    }

    @Test
    void upstreamHttpErrorIsHandled() {
        server.expect(once(), method(HttpMethod.GET)).andRespond(withBadGateway());

        assertThatThrownBy(() -> new WikipediaClient(restClient, 100).search("Java", 5))
                .isInstanceOf(WikipediaUpstreamException.class)
                .hasMessage("Wikipedia request failed");
    }

    @Test
    void batchedLinksFollowContinuationForMultiplePages() {
        server.expect(once(), queryParam("pageids", "1%7C2"))
                .andRespond(withSuccess("""
                        {"continue":{"plcontinue":"2|0|B2","continue":"||"},"query":{"pages":[
                          {"pageid":1,"ns":0,"title":"One","links":[{"ns":0,"title":"A"}]},
                          {"pageid":2,"ns":0,"title":"Two","links":[{"ns":0,"title":"B"}]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), queryParam("plcontinue", "2%7C0%7CB2"))
                .andRespond(withSuccess("""
                        {"query":{"pages":[{"pageid":2,"ns":0,"title":"Two",
                          "links":[{"ns":0,"title":"B2"}]}]}}
                        """, MediaType.APPLICATION_JSON));

        BatchLinksResult result = new WikipediaClient(restClient, 100)
                .getOutgoingLinksBatch(java.util.List.of(1L, 2L), () -> false);

        assertThat(result.complete()).isTrue();
        assertThat(result.articles().get(1L).linkedTitles()).containsExactly("A");
        assertThat(result.articles().get(2L).linkedTitles()).containsExactly("B", "B2");
        server.verify();
    }

    @Test
    void pageIdResolutionFollowsRedirectsToCanonicalArticle() {
        server.expect(once(), queryParam("pageids", "10"))
                .andRespond(withSuccess("""
                        {"query":{"pages":[{"pageid":10,"ns":0,"title":"Old name","redirect":true}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), queryParam("redirects", "1"))
                .andRespond(withSuccess("""
                        {"query":{"redirects":[{"from":"Old name","to":"New name"}],
                         "pages":[{"pageid":20,"ns":0,"title":"New name"}]}}
                        """, MediaType.APPLICATION_JSON));

        PageResolutionResult<Long> result = new WikipediaClient(restClient, 100)
                .resolvePageIds(java.util.List.of(10L), () -> false);

        assertThat(result.articles().get(10L)).isEqualTo(new CanonicalArticle(20, "New name"));
        server.verify();
    }

    @Test
    void batchedLinksReportConfiguredTruncation() {
        server.expect(once(), method(HttpMethod.GET)).andRespond(withSuccess("""
                {"query":{"pages":[{"pageid":1,"ns":0,"title":"One","links":[
                  {"ns":0,"title":"A"},{"ns":0,"title":"B"}
                ]}]}}
                """, MediaType.APPLICATION_JSON));

        BatchLinksResult result = new WikipediaClient(restClient, 1)
                .getOutgoingLinksBatch(java.util.List.of(1L), () -> false);

        assertThat(result.complete()).isFalse();
        assertThat(result.articles().get(1L).complete()).isFalse();
        assertThat(result.articles().get(1L).linkedTitles()).containsExactly("A");
    }

    @Test
    void titleBasedLinksCanonicalizeRedirectsAndFollowContinuation() {
        server.expect(once(), queryParam("titles", "Alias%7COther"))
                .andExpect(queryParam("redirects", "1"))
                .andExpect(queryParam("prop", "links"))
                .andRespond(withSuccess("""
                        {"continue":{"plcontinue":"20|0|Second","continue":"||"},
                         "query":{"redirects":[{"from":"Alias","to":"Canonical"}],"pages":[
                           {"pageid":20,"ns":0,"title":"Canonical","links":[{"ns":0,"title":"First"}]},
                           {"pageid":30,"ns":0,"title":"Other","links":[{"ns":0,"title":"Other link"}]}
                         ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), queryParam("plcontinue", "20%7C0%7CSecond"))
                .andRespond(withSuccess("""
                        {"query":{"pages":[
                          {"pageid":20,"ns":0,"title":"Canonical","links":[{"ns":0,"title":"Second"}]}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        TitleBatchLinksResult result = new WikipediaClient(restClient, 100)
                .getOutgoingLinksByTitles(java.util.List.of("Alias", "Other"),
                        new WikipediaRequestContext(() -> false));

        assertThat(result.complete()).isTrue();
        assertThat(result.articles().get("Alias").article())
                .isEqualTo(new CanonicalArticle(20, "Canonical"));
        assertThat(result.articles().get("Alias").linkedTitles()).containsExactly("First", "Second");
        assertThat(result.articles().get("Other").article())
                .isEqualTo(new CanonicalArticle(30, "Other"));
        server.verify();
    }

    @Test
    void titleBasedLinksAreNotCountedAsTitleResolutionRequests() {
        server.expect(once(), method(HttpMethod.GET)).andRespond(withSuccess("""
                {"query":{"pages":[{"pageid":1,"ns":0,"title":"Source","links":[]}]}}
                """, MediaType.APPLICATION_JSON));
        WikipediaRequestContext context = new WikipediaRequestContext(() -> false);

        new WikipediaClient(restClient, 100)
                .getOutgoingLinksByTitles(java.util.List.of("Source"), context);

        assertThat(context.totalRequests()).isEqualTo(1);
        assertThat(context.titleResolutionRequests()).isZero();
        server.verify();
    }

    @Test
    void titleResolutionRetriesHttp429AndRecovers() {
        server.expect(once(), queryParam("titles", "Target"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"));
        server.expect(once(), queryParam("titles", "Target"))
                .andRespond(withSuccess("""
                        {"query":{"pages":[{"pageid":2,"ns":0,"title":"Target"}]}}
                        """, MediaType.APPLICATION_JSON));

        PageResolutionResult<String> result = new WikipediaClient(restClient, 100)
                .resolveTitles(java.util.List.of("Target"), () -> false);

        assertThat(result.complete()).isTrue();
        assertThat(result.articles().get("Target")).isEqualTo(new CanonicalArticle(2, "Target"));
        server.verify();
    }

    @Test
    void titleResolutionStopsRetryingWhenDeadlineExpires() {
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        server.expect(once(), method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"));

        PageResolutionResult<String> result = new WikipediaClient(restClient, 100)
                .resolveTitles(java.util.List.of("Target"), () -> expired.getAndSet(true));

        assertThat(result.complete()).isFalse();
        assertThat(result.articles()).isEmpty();
        server.verify();
    }

    @Test
    void exhaustedHttp429RetriesReturnRateLimitErrorAndCountRequests() {
        for (int attempt = 0; attempt < 4; attempt++) {
            server.expect(once(), method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"));
        }
        WikipediaRequestContext context = new WikipediaRequestContext(() -> false);

        assertThatThrownBy(() -> new WikipediaClient(restClient, 100)
                .resolveTitles(java.util.List.of("Target"), context))
                .isInstanceOf(WikipediaRateLimitException.class)
                .hasMessage("Wikipedia is rate limiting requests; please try again later");
        assertThat(context.totalRequests()).isEqualTo(4);
        assertThat(context.titleResolutionRequests()).isEqualTo(4);
        assertThat(context.rateLimitResponses()).isEqualTo(4);
        server.verify();
    }
}
