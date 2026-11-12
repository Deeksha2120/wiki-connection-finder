package com.example.wikiconnectionfinder.controller;

import com.example.wikiconnectionfinder.dto.ArticleLinksResponse;
import com.example.wikiconnectionfinder.dto.ArticleSearchResponse;
import com.example.wikiconnectionfinder.service.ArticleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wiki-con-finder/articles")
public class ArticleController {
    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @GetMapping("/search")
    public ArticleSearchResponse search(@RequestParam String q,
            @RequestParam(defaultValue = "5") int limit) {
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        return articleService.search(q.trim(), limit);
    }

    @GetMapping("/{pageId}/links")
    public ArticleLinksResponse links(@PathVariable long pageId) {
        if (pageId < 1) {
            throw new IllegalArgumentException("pageId must be positive");
        }
        return articleService.getOutgoingLinks(pageId);
    }
}
