package com.example.wikiconnectionfinder.controller;

import java.util.List;

import com.example.wikiconnectionfinder.dto.ArticleLink;
import com.example.wikiconnectionfinder.dto.ArticleLinksResponse;
import com.example.wikiconnectionfinder.dto.ArticleSearchResponse;
import com.example.wikiconnectionfinder.dto.ArticleSummary;
import com.example.wikiconnectionfinder.exception.ApiExceptionHandler;
import com.example.wikiconnectionfinder.exception.ArticleNotFoundException;
import com.example.wikiconnectionfinder.exception.WikipediaUpstreamException;
import com.example.wikiconnectionfinder.service.ArticleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ArticleController.class, ApiExceptionHandler.class})
class ArticleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArticleService articleService;

    @Test
    void searchReturnsResultsUsingDefaultLimit() throws Exception {
        when(articleService.search("Java", 5)).thenReturn(new ArticleSearchResponse(List.of(
                new ArticleSummary(123, "Java (programming language)", "Programming language",
                        "https://en.wikipedia.org/wiki/Java_(programming_language)"))));

        mockMvc.perform(get("/api/wiki-con-finder/articles/search").param("q", " Java "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].pageId").value(123))
                .andExpect(jsonPath("$.results[0].description").value("Programming language"));
        verify(articleService).search("Java", 5);
    }

    @Test
    void searchReturnsEmptyResults() throws Exception {
        when(articleService.search("nothing", 5)).thenReturn(new ArticleSearchResponse(List.of()));

        mockMvc.perform(get("/api/wiki-con-finder/articles/search").param("q", "nothing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    void searchRejectsBlankQueryAndInvalidLimits() throws Exception {
        mockMvc.perform(get("/api/wiki-con-finder/articles/search").param("q", "  "))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/wiki-con-finder/articles/search").param("q", "Java").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/wiki-con-finder/articles/search").param("q", "Java").param("limit", "21"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void linksReturnsTruncationFlag() throws Exception {
        when(articleService.getOutgoingLinks(123)).thenReturn(new ArticleLinksResponse(123,
                "Java (programming language)", List.of(new ArticleLink("Object-oriented programming")), true));

        mockMvc.perform(get("/api/wiki-con-finder/articles/123/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageId").value(123))
                .andExpect(jsonPath("$.title").value("Java (programming language)"))
                .andExpect(jsonPath("$.links[0].title").value("Object-oriented programming"))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    @Test
    void linksReturnsNotFound() throws Exception {
        when(articleService.getOutgoingLinks(999)).thenThrow(new ArticleNotFoundException(999));

        mockMvc.perform(get("/api/wiki-con-finder/articles/999/links"))
                .andExpect(status().isNotFound());
    }

    @Test
    void upstreamErrorsAreGraceful() throws Exception {
        when(articleService.getOutgoingLinks(123)).thenThrow(new WikipediaUpstreamException("Wikipedia request failed"));

        mockMvc.perform(get("/api/wiki-con-finder/articles/123/links"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Wikipedia request failed"));
    }
}
