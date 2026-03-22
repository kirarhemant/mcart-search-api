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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

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

    private static String escape(String s) {
        // Minimal escaping for JSON embedding; safer is a JSON builder, but this works for demo.
        return s.replace("\"", "\\\"");
    }

    /**
     * Full‑text search (PLP) across name/brand/attributes.*
     */
    @GetMapping
    public JsonNode search(@RequestParam(required = false) String q,
                           @RequestParam(defaultValue="0") @Min(0) int page,
                           @RequestParam(defaultValue="20") @Min(1) int size,
                           @RequestParam(required = false) List<String> brand,
                           @RequestParam(required = false) List<String> categories,
                           @RequestParam(required = false) Double priceMin,
                           @RequestParam(required = false) Double priceMax,
                           @RequestParam(defaultValue = "relevance") String sort) throws IOException {

        String must = (q == null || q.isBlank()) ? "{ \"match_all\": {} }" : """
                {
                   "bool": {
                     "should": [
                       {
                         "multi_match": {
                           "query": "%s",
                           "fields": ["name^4", "brand^2", "categories","attributes.*"],
                           "lenient": true,
                           "fuzziness": "AUTO"
                         }
                       },
                       {
                         "multi_match": {
                           "query": "%s",
                           "fields": ["name^3"],
                           "type": "phrase_prefix"
                         }
                       }
                     ],
                     "minimum_should_match": 1
                   }
                 }
                """.formatted(escape(q), escape(q));

        StringBuilder filter = new StringBuilder();
        if (brand != null && !brand.isEmpty()) {
            filter.append("""
                    { "terms": { "brand": %s } },""".formatted(toJsonArray(brand)));
        }
        if (categories != null && !categories.isEmpty()) {
            filter.append("""
                    { "terms": { "categories": %s } },""".formatted(toJsonArray(categories)));
        }
        if (priceMin != null || priceMax != null) {
            String gte = priceMin != null ? "\"gte\":" + priceMin + "," : "";
            String lte = priceMax != null ? "\"lte\":" + priceMax + "," : "";
            filter.append("{ \"range\": { \"price\": { %s %s } } },".formatted(gte, lte).replace(", }", " }"));
        }

        String bool = """
                "query": { "bool": {
                  "must": [%s],
                  %s,
                  "should": [
                    { "match": { "name": { "query": "%s", "boost": 3 } } }
                  ]
                }}"""
                .formatted(must,
                        filter.length() > 0 ? "\"filter\": [" + trimComma(filter) + "]" : "\"filter\": []",
                        escape(q == null ? "" : q));

        String sortJson;

        if ("price_asc".equals(sort)) {
            sortJson = "[{ \"price\": \"asc\" }]";
        } else if ("price_desc".equals(sort)) {
            sortJson = "[{ \"price\": \"desc\" }]";
        } else {
            sortJson = "[\"_score\"]"; // default relevance
        }

        String body = """
                {
                  "from": %d, "size": %d,
                  %s,
                  "sort": %s,
                  "aggs": {
                    "brands": { "terms": {"field":"brand"} },
                    "categories": { "terms": { "field": "categories" } },
                    "price_hist": { "histogram": {"field":"price","interval":100.0} }
                  }
                }""".formatted(page * size, size, bool, sortJson);

        Request req = new Request("POST", "/" + index + "/_search");
        req.setOptions(options);
        req.setJsonEntity(body);
        Response resp = client.performRequest(req);
        try (InputStream in = resp.getEntity().getContent()) {
            return objectMapper.readTree(in);
        }
    }

    /**
     * Simple suggest based on prefix (no completion mapping required).
     */
    @GetMapping("/suggest")
    public JsonNode suggest(@RequestParam("q") @NotBlank String prefix,
                            @RequestParam(defaultValue = "5") @Min(1) int size) throws Exception {

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

    private String toJsonArray(List<String> values) throws IOException {
        return objectMapper.writeValueAsString(values);
    }

    private String trimComma(StringBuilder sb) {
        int len = sb.length();
        if (len > 0 && sb.charAt(len - 1) == ',') {
            sb.setLength(len - 1);
        }
        return sb.toString();
    }
}