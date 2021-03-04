/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.apicurio.registry.utils.kafka.ConsumerActions;
import io.apicurio.registry.utils.kafka.Oneof2;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.metadata.ConfigRecord;
import org.apache.kafka.common.metadata.PartitionRecord;
import org.apache.kafka.common.metadata.RemoveTopicRecord;
import org.apache.kafka.common.metadata.TopicRecord;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.metadata.MetadataParser;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RaftTopicsWatcher extends TopicOperatorWatcher {

    private final Config config;
    private final Properties properties;
    private final long pollTimeout;

    private Admin admin;
    private ConsumerActions.DynamicAssignment<byte[], byte[]> consumer;
    private ExecutorService executor;

    private Map<String, Object> tasks = new ConcurrentHashMap<>();

    // TODO -- should go away with Admin::topicId handling
    private final Map<Uuid, String> topics = new ConcurrentHashMap<>();

    public RaftTopicsWatcher(TopicOperator topicOperator, Config config, Properties properties) {
        super(topicOperator);
        this.config = config;
        this.properties = properties;
        this.pollTimeout = TimeUnit.SECONDS.toNanos(config.get(Config.RAFT_WATCHER_POOL_TIMEOUT_SECONDS));
    }

    @Override
    public void start() {
        executor = new ScheduledThreadPoolExecutor(config.get(Config.RAFT_WATCHER_POOL_THREADS));
        admin = Admin.create(properties);
        // consume @metadata topic
        String controllerServers = config.get(Config.KAFKA_CONTROLLER_SERVERS);
        if (controllerServers == null) {
            throw new IllegalArgumentException("Missing Kafka RAFT controller servers config property!");
        }
        Properties consumerProperties = new Properties(properties);
        consumerProperties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, controllerServers);
        MetadataConsumer tmp = new MetadataConsumer(consumerProperties, Oneof2.first(this::consumeBytes));
        tmp.start();
        consumer = tmp;
    }

    private void consumeBytes(ConsumerRecord<byte[], byte[]> cr) {
        ByteBuffer buffer = ByteBuffer.wrap(cr.value());
        ApiMessage message = MetadataParser.read(buffer);
        handleMessage(cr.offset(), message);
    }

    /**
     * Poll admin client for current state and match it with metadata record.
     * Loop this check until it matches or it timeouts.
     * The loop is also broken if a same but newer sync function is queued.
     *
     * @param syncFn   the sync function
     * @param notifyTO the runnable to notify topic operator
     */
    private CompletionStage<Void> syncState(SyncFunction syncFn, Runnable notifyTO) {
        Object opId = new Object(); // marker
        String key = syncFn.key();
        tasks.put(key, opId);
        return syncState(syncFn, opId, System.nanoTime() + pollTimeout)
            .thenRunAsync(notifyTO)
            .whenComplete((r, x) -> tasks.remove(key, opId));
    }

    private CompletionStage<Void> syncState(SyncFunction syncFn, Object opId, long deadline) {
        return syncFn
            .apply(admin)
            .thenComposeAsync(success -> {
                if (success) {
                    return CompletableFuture.completedFuture(null);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return CompletableFuture.failedFuture(new TimeoutException("Timed out."));
                }
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(500_000_000L, remainingNanos));
                } catch (InterruptedException e) {
                    return CompletableFuture.failedFuture(e);
                }
                if (tasks.get(syncFn.key()) != opId) {
                    return CompletableFuture.failedFuture(new CancellationException("Canceled by next request for same key: " + syncFn.key()));
                } else {
                    return syncState(syncFn, opId, deadline);
                }
            });
    }

    private void handleMessage(long offset, ApiMessage message) {
        log.info("Received [" + offset + "] ApiMessage: " + message);
        SyncFunction syncFn = null;
        Runnable executable = null;
        if (message instanceof TopicRecord) {
            TopicRecord topicRecord = (TopicRecord) message;
            String topicName = topicRecord.name();
            topics.put(topicRecord.topicId(), topicName);
            syncFn = new TopicRecordSyncFunction(topicName);
            executable = () -> topicConfigChange(topicName, LogContext.raftWatch(offset, "+" + topicName));
        } else if (message instanceof RemoveTopicRecord) {
            RemoveTopicRecord rtr = (RemoveTopicRecord) message;
            String topicName = topics.remove(rtr.topicId());
            syncFn = new TopicRecordSyncFunction(topicName).negate();
            executable = () -> topicConfigChange(topicName, LogContext.raftWatch(offset, "-" + topicName));
        } else if (message instanceof ConfigRecord) {
            ConfigRecord kcr = (ConfigRecord) message;
            byte type = kcr.resourceType();
            if (type == ConfigResource.Type.TOPIC.id()) {
                String topicName = kcr.resourceName();
                syncFn = new ConfigRecordSyncFunction(kcr, topicName);
                executable = () -> topicConfigChange(topicName, LogContext.raftWatch(offset, "?" + topicName));
            }
        } else if (message instanceof PartitionRecord) {
            PartitionRecord pr = (PartitionRecord) message;
            String topicName = topics.get(pr.topicId());
            // TODO -- can topicName be null?
            syncFn = new PartitionRecordSyncFunction(pr, topicName);
            executable = () -> partitionsChange(topicName, LogContext.raftWatch(offset, "=" + topicName));
        }
        if (syncFn != null) {
            CompletionStage<Void> state = syncState(syncFn, executable);
            state.whenComplete((r, t) -> {
                if (t != null) {
                    log.error("Exception syncing state.", t);
                }
            });
        }
    }

    @Override
    public boolean started() {
        return consumer != null;
    }

    @Override
    public void stop() {
        if (consumer != null) {
            close(() -> {
                consumer.stop();
                close(consumer);
            });
        }
        close(admin);
        if (executor != null) {
            close(executor::shutdown);
        }
    }

    private void close(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Throwable t) {
            log.warn("Exception while closing: {}", closeable, t);
        }
    }

}
