/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.KafkaFuture;

import java.util.Set;
import java.util.concurrent.CompletionStage;

import static io.strimzi.operator.topic.KafkaImpl.toCompletionStage;

/**
 * Check if the topic does or doesn't exist.
 */
class TopicRecordSyncFunction implements SyncFunction {
    private boolean condition = true;
    protected String topicName;

    public TopicRecordSyncFunction(String topicName) {
        this.topicName = topicName;
    }

    public TopicRecordSyncFunction negate() {
        condition = !condition;
        return this;
    }

    @Override
    public String key() {
        return String.format("%s_%s", topicName, getClass().getSimpleName());
    }

    public CompletionStage<Boolean> apply(Admin admin) {
        KafkaFuture<Set<String>> kfNames = admin.listTopics().names();
        return toCompletionStage(kfNames.thenApply(n -> n.contains(topicName)));
    }
}
