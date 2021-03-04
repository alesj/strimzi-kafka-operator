/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.metadata.ConfigRecord;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import static io.strimzi.operator.topic.KafkaImpl.toCompletionStage;

/**
 * Check if a topic config is already synced across cluster.
 */
class ConfigRecordSyncFunction extends TopicRecordSyncFunction {
    private ConfigRecord configRecord;

    public ConfigRecordSyncFunction(ConfigRecord configRecord, String topicName) {
        super(topicName);
        this.configRecord = configRecord;
    }

    @Override
    public CompletionStage<Boolean> apply(Admin admin) {
        ConfigResource topicConfigResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        KafkaFuture<Map<ConfigResource, Config>> all = admin.describeConfigs(Collections.singleton(topicConfigResource)).all();
        return toCompletionStage(
            all.thenApply(map -> {
                Config config = map.get(topicConfigResource);
                if (config != null) {
                    ConfigEntry entry = config.get(configRecord.name());
                    if (entry != null) {
                        return Objects.equals(configRecord.value(), entry.value());
                    }
                }
                return false;
            })
        );
    }
}
