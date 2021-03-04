/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.strimzi.operator.topic.zk.Zk;

/**
 * ZooKeeper watcher for child znodes of {@code /brokers/topics},
 * calling {@link TopicOperator#onTopicPartitionsChanged(LogContext, TopicName)}
 * for changed children.
 */
public class ZkTopicWatcher extends ZkWatcher {

    private static final String TOPICS_ZNODE = "/brokers/topics";

    ZkTopicWatcher(TopicOperator topicOperator, Zk zk) {
        super(topicOperator, TOPICS_ZNODE, zk);
    }

    @Override
    protected void notifyOperator(String child) {
        partitionsChange(child, LogContext.zkWatch(TOPICS_ZNODE, "=" + child));
    }
}
