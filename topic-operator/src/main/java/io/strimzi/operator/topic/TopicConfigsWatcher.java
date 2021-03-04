/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.strimzi.operator.topic.zk.Zk;

/**
 * ZooKeeper watcher for child znodes of {@code /configs/topics},
 * calling {@link TopicOperator#onTopicConfigChanged(LogContext, TopicName)}
 * for changed children.
 */
class TopicConfigsWatcher extends ZkWatcher {

    private static final String CONFIGS_ZNODE = "/config/topics";

    TopicConfigsWatcher(TopicOperator topicOperator, Zk zk) {
        super(topicOperator, CONFIGS_ZNODE, zk);
    }

    @Override
    protected void notifyOperator(String child) {
        topicConfigChange(child, LogContext.zkWatch(CONFIGS_ZNODE, "=" + child));
    }
}
