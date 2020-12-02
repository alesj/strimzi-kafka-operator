/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.crd.convert.cli;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.strimzi.kafka.crd.convert.utils.ContentType;
import picocli.CommandLine;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

/**
 * Json or Yaml content.
 */
public abstract class JYCommand extends ContentCommand {

    @CommandLine.Option(names = {"-t", "--type"}, description = "Content type, e.g. json or yaml")
    ContentType type;

    protected abstract JsonNode run(JsonNode root) throws IOException;

    @Override
    protected Object run(byte[] data) throws IOException {
        ContentType ct = type;
        if (ct == null && extension != null) {
            ct = ContentType.findByExtension(extension);
        }
        if (ct == null) {
            throw new IllegalStateException("Cannot determine content type!");
        }
        ObjectMapper mapper = ct.getMapper();
        JsonNode result = run(mapper.readTree(data));
        Writer writer = new StringWriter();
        JsonGenerator generator = mapper.getFactory().createGenerator(writer);
        mapper.writeTree(generator, result); // output same type; json vs yaml
        return writer.toString();
    }
}
