/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.apicurio.registry.utils.kafka.ConsumerActions;
import io.apicurio.registry.utils.kafka.ConsumerContainer;
import io.apicurio.registry.utils.kafka.ConsumerSkipRecordsSerializationExceptionHandler;
import io.apicurio.registry.utils.kafka.Oneof2;
import io.apicurio.registry.utils.kafka.Seek;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.util.Properties;
import java.util.function.Consumer;

/**
 * A @metadata topic consumer
 */
class MetadataConsumer extends ConsumerContainer<byte[], byte[]> implements ConsumerActions.DynamicAssignment<byte[], byte[]> {
    private static final TopicPartition TP = new TopicPartition("@metadata", 0);

    public MetadataConsumer(
        Properties consumerProperties,
        Oneof2<Consumer<? super ConsumerRecord<byte[], byte[]>>, Consumer<? super ConsumerRecords<byte[], byte[]>>> recordOrRecordsHandler) {
        super(
            consumerProperties,
            new ByteArrayDeserializer(),
            new ByteArrayDeserializer(),
            recordOrRecordsHandler,
            new ConsumerSkipRecordsSerializationExceptionHandler()
        );
    }

    @Override
    public void start() {
        addTopicPartition(TP, Seek.FROM_CURRENT.offset(0L));
    }

    public void stop() {
        removeTopicParition(TP);
    }
}
