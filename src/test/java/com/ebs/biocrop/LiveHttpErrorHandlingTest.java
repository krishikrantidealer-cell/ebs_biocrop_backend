package com.ebs.biocrop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.mongodb.uri=mongodb://127.0.0.1:27017/biocrop_test",
        "spring.data.mongodb.database=biocrop_test",
        "app.jwt.secret=test-only-secret-key-that-is-at-least-32-bytes-long",
        "app.database.maintenance-mode.enabled=true",
        "app.rate-limit.in-memory-fallback=true",
        "app.rate-limit.dev-in-memory-fallback=true"
})
class LiveHttpErrorHandlingTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void refreshWithoutBodyReturns400OverHttp() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/token/refresh", HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("Request body is required");
    }

    @Test
    void unsupportedMediaTypeReturns415OverHttp() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/otp/send", HttpMethod.POST,
                new HttpEntity<>("not-json", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
    }

    @Test
    void unknownUnauthenticatedPathHasJson401OverHttp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/nonexistent", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).contains("Full authentication is required");
    }
}
