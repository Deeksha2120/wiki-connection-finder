package com.example.wikiconnectionfinder.client;

import com.example.wikiconnectionfinder.dto.ArticleLinksResponse;
import com.example.wikiconnectionfinder.exception.ArticleNotFoundException;
import com.example.wikiconnectionfinder.exception.WikipediaUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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
}
