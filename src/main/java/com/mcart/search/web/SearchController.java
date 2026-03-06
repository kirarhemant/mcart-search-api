package com.mcart.search.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@Validated
public class SearchController {

    private final RestClient client;
    private final RequestOptions options;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${es.index:products}")
    private String index;

    public SearchController(RestClient client, RequestOptions options) {
        this.client = client;
        this.options = options;
    }

    /** Full‑text search (PLP) across name/brand/attributes.* */
    @GetMapping
    public JsonNode search(@RequestParam @NotBlank String q,
                           @RequestParam(defaultValue="0") @Min(0) int page,
                           @RequestParam(defaultValue="20") @Min(1) int size) throws Exception {

        int from = Math.max(0, page * size);

        String body = """
    {
      "from": %d,
      "size": %d,
      "query": {
        "multi_match": {
          "query": "%s",
          "fields": ["name^3","brand","attributes.*"],
          "lenient": true
        }
      },
      "aggs": {
        "brands": { "terms": { "field": "brand" } },
        "price_hist": { "histogram": { "field": "price", "interval": 100.0 } }
      }
    }
    """.formatted(from, size, escape(q));

        Request req = new Request("POST", "/" + index + "/_search");
        req.setJsonEntity(body);
        Response resp = client.performRequest(req);
        try (InputStream in = resp.getEntity().getContent()) {
            return objectMapper.readTree(in);
        }
    }

    /** Simple suggest based on prefix (no completion mapping required). */
    @GetMapping("/suggest")
    public JsonNode suggest(@RequestParam("q") @NotBlank String prefix,
                            @RequestParam(defaultValue="5") @Min(1) int size) throws Exception {

        String body = """
    {
      "from": 0,
      "size": %d,
      "query": {
        "match_phrase_prefix": { "name": "%s" }
      },
      "_source": ["sku","name","brand","price"]
    }
    """.formatted(size, escape(prefix));

        Request req = new Request("POST", "/" + index + "/_search");
        req.setJsonEntity(body);
        Response resp = client.performRequest(req);
        try (InputStream in = resp.getEntity().getContent()) {
            return objectMapper.readTree(in);
        }
    }

    private static String escape(String s) {
        // Minimal escaping for JSON embedding; safer is a JSON builder, but this works for demo.
        return s.replace("\"","\\\"");
    }
}