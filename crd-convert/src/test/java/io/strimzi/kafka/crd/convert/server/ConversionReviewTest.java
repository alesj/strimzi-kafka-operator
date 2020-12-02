/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.crd.convert.server;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.strimzi.kafka.crd.convert.utils.ContentType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

class ConversionReviewTest {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    @Test
    public void testDeserialization() throws IOException {
        ConversionReview conversionReview = JSON_MAPPER.readValue(getClass().getResourceAsStream("reviewRequest.json"), ConversionReview.class);
        Assertions.assertEquals("705ab4f5-6393-11e8-b7cc-42010a800002", conversionReview.getRequest().uid);
        Assertions.assertEquals(1, conversionReview.getRequest().objects.size());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    public void testServer() throws Exception {
        System.setProperty("plain.port", "8080");
        ConvertServer server = new ConvertServer();
        try (AutoCloseable ac = server.start()) {
            HttpClient client = HttpClient.newBuilder().build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/convert"))
                    .header("Content-type", ContentType.JSON.getContentType())
                    .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> getClass().getResourceAsStream("reviewRequest.json"))
                    )
                    .build();
            HttpResponse<byte[]> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            Response cr = JSON_MAPPER.readValue(response.body(), Response.class);
            Assertions.assertEquals("705ab4f5-6393-11e8-b7cc-42010a800002", cr.getUid());
            List<Object> convertedObjects = cr.getConvertedObjects();
            Assertions.assertTrue(convertedObjects.size() > 0);
            Map<Object, Object> map = (Map) convertedObjects.get(0);
            Assertions.assertEquals("kafka.strimzi.io/v1beta2", map.get("apiVersion"));
        }
    }

}