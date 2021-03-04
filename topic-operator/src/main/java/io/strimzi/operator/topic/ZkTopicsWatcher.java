/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.strimzi.operator.topic.zk.Zk;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ZooKeeper watcher for child znodes of {@code /brokers/topics},
 * calling {@link TopicOperator#onTopicCreated(LogContext, TopicName)} for new children and
 * {@link TopicOperator#onTopicDeleted(LogContext, TopicName)} for deleted children.
 */
class ZkTopicsWatcher extends TopicOperatorWatcher {

    private final static Logger LOGGER = LogManager.getLogger(ZkTopicsWatcher.class);

    private static final String TOPICS_ZNODE = "/brokers/topics";

    private final Zk zk;

    /*test*/ protected final TopicConfigsWatcher tcw;
    /*test*/ protected final ZkTopicWatcher tw;

    private List<String> children;

    private volatile int state = 0;

    /**
     * Constructor
     *
     * @param topicOperator    Operator instance
     */
    ZkTopicsWatcher(TopicOperator topicOperator, Zk zk) {
        super(topicOperator);
        this.zk = zk;
        this.tcw = new TopicConfigsWatcher(topicOperator, zk);
        LOGGER.debug("Using TopicConfigsWatcher {}", tcw);
        this.tw = new ZkTopicWatcher(topicOperator, zk);
        LOGGER.debug("Using TopicWatcher {}", tw);
    }

    public void stop() {
        this.tcw.stop();
        this.tw.stop();
        this.state = 2;
    }

    public boolean started() {
        return tcw.started() && tcw.started() && this.state == 1;
    }

    public void start() {
        synchronized (this) {
            children = null;
        }
        tcw.start();
        tw.start();
        zk.watchChildren(TOPICS_ZNODE, new ChildrenWatchHandler(zk)).<Void>compose(zk2 -> {
            zk.children(TOPICS_ZNODE, childResult -> {
                if (childResult.failed()) {
                    LOGGER.error("Error on znode {} children", TOPICS_ZNODE, childResult.cause());
                    return;
                }
                List<String> result = childResult.result();
                LOGGER.debug("Setting initial children {}", result);
                synchronized (this) {
                    this.children = result;
                }
                // Start watching existing children for config and partition changes
                for (String child : result) {
                    tcw.addChild(child);
                    tw.addChild(child);
                }
                this.state = 1;
            });
            return Future.succeededFuture();
        });
    }

    /**
     * Handler which runs on ZkClient's single event handling thread.
     */
    private class ChildrenWatchHandler implements Handler<AsyncResult<List<String>>> {

        private final Zk zk;
        private int watchCount = 0;

        public ChildrenWatchHandler(Zk zk) {
            this.zk = zk;
        }

        @Override
        public void handle(AsyncResult<List<String>> childResult) {
            if (state == 2) {
                zk.unwatchChildren(TOPICS_ZNODE);
                return;
            }
            if (childResult.failed()) {
                LOGGER.error("Error on znode {} children", TOPICS_ZNODE, childResult.cause());
                return;
            }
            ++watchCount;
            List<String> result = childResult.result();
            Set<String> deleted;
            Set<String> created;
            synchronized (ZkTopicsWatcher.this) {
                LOGGER.debug("{}: znode {} now has children {}, previous children {}", watchCount, TOPICS_ZNODE, result, ZkTopicsWatcher.this.children);
                List<String> oldChildren = ZkTopicsWatcher.this.children;
                if (oldChildren == null) {
                    return;
                }
                deleted = new HashSet<>(oldChildren);
                deleted.removeAll(result);
                created = new HashSet<>(result);
                created.removeAll(ZkTopicsWatcher.this.children);
                ZkTopicsWatcher.this.children = result;
            }

            LOGGER.info("Topics deleted from ZK for watch {}: {}", watchCount, deleted);
            if (!deleted.isEmpty()) {
                for (String topicName : deleted) {
                    tcw.removeChild(topicName);
                    tw.removeChild(topicName);
                    LogContext logContext = LogContext.zkWatch(TOPICS_ZNODE, watchCount + ":-" + topicName);
                    topicOperator.onTopicDeleted(logContext, new TopicName(topicName)).onComplete(ar -> {
                        if (ar.succeeded()) {
                            LOGGER.debug("{}: Success responding to deletion of topic {}", logContext, topicName);
                        } else {
                            LOGGER.warn("{}: Error responding to deletion of topic {}", logContext, topicName, ar.cause());
                        }
                    });
                }
            }

            LOGGER.info("Topics created in ZK for watch {}: {}", watchCount, created);
            if (!created.isEmpty()) {
                for (String topicName : created) {
                    tcw.addChild(topicName);
                    tw.addChild(topicName);
                    LogContext logContext = LogContext.zkWatch(TOPICS_ZNODE, watchCount + ":+" + topicName);
                    topicOperator.onTopicCreated(logContext, new TopicName(topicName)).onComplete(ar -> {
                        if (ar.succeeded()) {
                            LOGGER.debug("{}: Success responding to creation of topic {}", logContext, topicName);
                        } else {
                            LOGGER.warn("{}: Error responding to creation of topic {}", logContext, topicName, ar.cause());
                        }
                    });
                }
            }
        }
    }
}
