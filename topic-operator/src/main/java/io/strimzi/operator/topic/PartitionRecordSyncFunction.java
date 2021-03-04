/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.metadata.PartitionRecord;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

class PartitionRecordSyncFunction extends TopicRecordSyncFunction {
    private PartitionRecord partitionRecord;

    public PartitionRecordSyncFunction(PartitionRecord partitionRecord, String topicName) {
        super(topicName);
        this.partitionRecord = partitionRecord;
    }

    public CompletionStage<Boolean> apply(Admin admin) {
        KafkaFuture<Map<String, TopicDescription>> all = admin.describeTopics(Collections.singleton(topicName)).all();
        return KafkaImpl.toCompletionStage(
            all.thenApply(map -> {
                TopicDescription td = map.get(topicName);
                if (td != null) {
                    TopicPartitionInfo info = td.partitions().stream().filter(tp -> tp.partition() == partitionRecord.partitionId())
                        .findFirst()
                        .orElse(null);
                    if (info != null) {
                        // we only care about replicas, no leader or isr ?!
                        Set<Integer> infoNodes = info.replicas().stream().map(Node::id).collect(Collectors.toCollection(TreeSet::new));
                        Set<Integer> prNodes = new TreeSet<>(partitionRecord.replicas());
                        return infoNodes.equals(prNodes);
                    }
                }
                return false;
            })
        );
    }
}
