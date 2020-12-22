/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.strimzi.operator.topic.zk.Zk;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Migration tool to move ZkTopicStore to KafkaStreamsTopicStore.
 */
public class Zk2KafkaStreams {
    private static final Logger log = LoggerFactory.getLogger(Zk2KafkaStreams.class);

    public static CompletionStage<KafkaStreamsTopicStoreService> upgrade(
            Zk zk,
            Config config,
            Properties kafkaProperties,
            boolean doStop
    ) {
        String topicsPath = config.get(Config.TOPICS_PATH);

        log.info("Upgrading topic store: {}", topicsPath);

        TopicStore zkTopicStore = new ZkTopicStore(zk, topicsPath);
        KafkaStreamsTopicStoreService service = new KafkaStreamsTopicStoreService();
        return service.start(config, kafkaProperties)
                .thenCompose(ksTopicStore -> {
                    log.info("Starting upgrade ...");
                    CompletableFuture<Void> cf = new CompletableFuture<>();
                    zk.children(topicsPath, result -> {
                        if (result.failed()) {
                            cf.completeExceptionally(result.cause());
                        } else {
                            result.map(list -> {
                                log.info("Topics to upgrade: {}", list);
                                @SuppressWarnings("rawtypes")
                                List<Future> results = new ArrayList<>();
                                list.forEach(topicName -> {
                                    Future<Topic> ft = zkTopicStore.read(new TopicName(topicName));
                                    results.add(
                                            ft.onSuccess(ksTopicStore::create)
                                                    .onSuccess(t1 -> zkTopicStore.delete(t1.getTopicName()))
                                    );
                                });
                                return CompositeFuture.all(results);
                            }).result().onComplete(ar -> {
                                if (ar.failed()) {
                                    cf.completeExceptionally(ar.cause());
                                } else {
                                    zk.delete(topicsPath, -1, v -> {
                                        if (v.failed()) {
                                            cf.completeExceptionally(v.cause());
                                        } else {
                                            cf.complete(null);
                                        }
                                    });
                                }
                            });
                        }
                    });
                    return cf;
                })
                .whenCompleteAsync((v, t) -> {
                    // stop in another thread
                    if (doStop || t != null) {
                        service.stop();
                    }
                    log.info("Upgrade complete", t);
                })
                .thenApply(v -> service);
    }
}
