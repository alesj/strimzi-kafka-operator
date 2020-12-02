/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.crd.convert.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.strimzi.api.annotations.ApiVersion;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.kafka.crd.convert.converter.Converter;
import io.strimzi.kafka.crd.convert.converter.KafkaConverter;
import picocli.CommandLine;

@CommandLine.Command(name = "convert", aliases = {"c"}, description = "Convert CRDs")
public class ConvertCommand extends JYCommand {
    @CommandLine.Option(names = {"-tv", "--to-version"}, required = true, description = "K8s ApiVersion")
    ApiVersion apiVersion;

    Converter<Kafka> converter = new KafkaConverter(); // currently just this converter ...

    @Override
    protected JsonNode run(JsonNode root) {
        converter.convertTo(root, apiVersion);
        return root;
    }
}
