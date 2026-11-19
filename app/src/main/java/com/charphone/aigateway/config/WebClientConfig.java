package com.charphone.aigateway.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient geminiWebClient(AppProperties props) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getGemini().getConnectTimeoutSeconds() * 1000)
                .responseTimeout(Duration.ofSeconds(props.getGemini().getReadTimeoutSeconds()));

        return WebClient.builder()
                .baseUrl(props.getGemini().getBaseUrl())
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public WebClient openAiCompatWebClient(AppProperties props) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        props.getOpenaiCompat().getConnectTimeoutSeconds() * 1000)
                .responseTimeout(Duration.ofSeconds(props.getOpenaiCompat().getReadTimeoutSeconds()));

        return WebClient.builder()
                .baseUrl(props.getOpenaiCompat().getBaseUrl())
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
    }
}
