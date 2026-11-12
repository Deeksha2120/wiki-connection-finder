package com.example.wikiconnectionfinder.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WikipediaClientConfig {

    @Bean
    RestClient wikipediaRestClient(
            RestClient.Builder builder,
            @Value("${wikipedia.user-agent:WikiConnectionFinder/0.0.1 (local development; set WIKIPEDIA_USER_AGENT with contact details)}") String userAgent,
            @Value("${wikipedia.connect-timeout:3s}") Duration connectTimeout,
            @Value("${wikipedia.read-timeout:10s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return builder
                .baseUrl("https://en.wikipedia.org/w/api.php")
                .defaultHeader("User-Agent", userAgent)
                .requestFactory(requestFactory)
                .build();
    }
}
