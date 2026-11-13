package com.example.wikiconnectionfinder.controller;

import java.util.List;

import com.example.wikiconnectionfinder.dto.ConnectionPathArticle;
import com.example.wikiconnectionfinder.dto.ConnectionResponse;
import com.example.wikiconnectionfinder.dto.ConnectionStatus;
import com.example.wikiconnectionfinder.exception.ApiExceptionHandler;
import com.example.wikiconnectionfinder.exception.WikipediaRateLimitException;
import com.example.wikiconnectionfinder.service.ConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ConnectionController.class, ApiExceptionHandler.class})
class ConnectionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConnectionService connectionService;

    @Test
    void returnsConnectionResponse() throws Exception {
        when(connectionService.findConnection(1, 2)).thenReturn(new ConnectionResponse(ConnectionStatus.FOUND,
                null,
                List.of(new ConnectionPathArticle(1, "Source"), new ConnectionPathArticle(2, "Target")),
                1, 1, true));

        mockMvc.perform(post("/api/wiki-con-finder/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePageId\":1,\"targetPageId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.limitReason").isEmpty())
                .andExpect(jsonPath("$.path[1].pageId").value(2))
                .andExpect(jsonPath("$.degrees").value(1))
                .andExpect(jsonPath("$.shortestPathGuaranteed").value(true));
    }

    @Test
    void rejectsInvalidIds() throws Exception {
        mockMvc.perform(post("/api/wiki-con-finder/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePageId\":0,\"targetPageId\":2}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsServiceUnavailableForWikipediaRateLimit() throws Exception {
        when(connectionService.findConnection(1, 2))
                .thenThrow(new WikipediaRateLimitException(new RuntimeException("upstream 429")));

        mockMvc.perform(post("/api/wiki-con-finder/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePageId\":1,\"targetPageId\":2}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail")
                        .value("Wikipedia is rate limiting requests; please try again later"));
    }
}
