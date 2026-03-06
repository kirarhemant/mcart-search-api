package com.mcart.search.config;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EsClientConfig {

    @Value("${es.url}")
    private String esUrl;

    @Value("${es.apiKey}")   // base64(id:key)
    private String apiKey;

    @Bean
    public RestClient restClient() {
        // Set default Authorization header once (ApiKey <base64>)
        Header[] headers = new Header[] {
                new BasicHeader("Authorization", "ApiKey " + apiKey)
        };

        RestClientBuilder builder = RestClient.builder(HttpHost.create(esUrl))
                .setDefaultHeaders(headers);

        return builder.build();
    }

    @Bean
    public RequestOptions requestOptions() {
        return RequestOptions.DEFAULT;
    }
}