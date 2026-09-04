package com.acme.orders.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Thin HTTP helper so the tests read as behaviour rather than plumbing. */
public class Api {

    private final TestRestTemplate http;

    public Api(TestRestTemplate http) {
        this.http = http;
    }

    public long placeAs(String tenant, String customer, String item, long amountCents) {
        HttpHeaders headers = tenantHeader(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"customer":"%s","item":"%s","amountCents":%d}""".formatted(customer, item, amountCents);

        ResponseEntity<JsonNode> response =
                http.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    public ResponseEntity<JsonNode> getAs(String path, String tenant) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(tenantHeader(tenant)), JsonNode.class);
    }

    public ResponseEntity<JsonNode> getWithHeaders(String path, HttpHeaders headers) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    /** Sets Host so the subdomain resolver has something to read. */
    public ResponseEntity<JsonNode> getWithHost(String path, String host) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.HOST, host);
        return getWithHeaders(path, headers);
    }

    public HttpHeaders tenantHeader(String tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-ID", tenant);
        return headers;
    }
}
