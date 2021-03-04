/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base abstract class for all watchers.
 * With service-like methods; start, stop, started
 */
public abstract class TopicOperatorWatcher {

    protected Logger log = LogManager.getLogger(getClass());

    protected final TopicOperator topicOperator;

    /**
     * Constructor
     *
     * @param topicOperator Operator instance to notify
     */
    TopicOperatorWatcher(TopicOperator topicOperator) {
        this.topicOperator = topicOperator;
    }

    abstract void start();

    abstract boolean started();

    abstract void stop();

    protected void topicConfigChange(String child, LogContext logContext) {
        log.info("{}: Topic config change", logContext);
        topicOperator.onTopicConfigChanged(logContext,
            new TopicName(child)).onComplete(ar2 -> log.info("{}: Reconciliation result due to topic config change on topic {}: {}", logContext, child, ar2));
    }

    protected void partitionsChange(String child, LogContext logContext) {
        log.info("{}: Partitions change", logContext);
        topicOperator.onTopicPartitionsChanged(logContext,
            new TopicName(child)).onComplete(ar -> log.info("{}: Reconciliation result due to topic partitions change on topic {}: {}", logContext, child, ar));
    }
}
