package com.example.wikiconnectionfinder.controller;

import com.example.wikiconnectionfinder.dto.ConnectionRequest;
import com.example.wikiconnectionfinder.dto.ConnectionResponse;
import com.example.wikiconnectionfinder.service.ConnectionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wiki-con-finder/connections")
public class ConnectionController {
    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @PostMapping
    public ConnectionResponse findConnection(@RequestBody ConnectionRequest request) {
        if (request.sourcePageId() == null || request.sourcePageId() < 1) {
            throw new IllegalArgumentException("sourcePageId must be positive");
        }
        if (request.targetPageId() == null || request.targetPageId() < 1) {
            throw new IllegalArgumentException("targetPageId must be positive");
        }
        return connectionService.findConnection(request.sourcePageId(), request.targetPageId());
    }
}
