/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import org.apache.kafka.clients.admin.Admin;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * A function to be used to check if metadata record is in sync with current state.
 */
interface SyncFunction extends Function<Admin, CompletionStage<Boolean>> {
    /**
     * The key to store this function in the tasks map.
     * Same functions have to have same name.
     * e.g. any ConfigRecord sync function for a same topic
     *
     * @return the key
     */
    String key();
}
