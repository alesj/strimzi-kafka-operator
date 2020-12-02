/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.crd.convert.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.strimzi.api.annotations.ApiVersion;
import io.strimzi.api.kafka.model.Kafka;

import java.io.IOException;
import java.io.UncheckedIOException;

class KafkaJsonNodeConverterTest extends KafkaConverterTestBase {

    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private final ExtKafkaConverter kafkaConverter = new ExtKafkaConverter() {
        @Override
        public Kafka testConvertTo(Kafka instance, ApiVersion toVersion) {
            try {
                byte[] bytes = JSON_MAPPER.writeValueAsBytes(instance);
                JsonNode node = JSON_MAPPER.readTree(bytes);
                convertTo(node, toVersion);
                return JSON_MAPPER.readerFor(crClass()).readValue(node);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    @Override
    ExtKafkaConverter kafkaConverter() {
        return kafkaConverter;
    }
}