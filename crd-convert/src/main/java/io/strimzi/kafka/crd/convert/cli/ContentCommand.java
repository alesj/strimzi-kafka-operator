/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.crd.convert.cli;

import io.strimzi.kafka.crd.convert.utils.IoUtil;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public abstract class ContentCommand extends AbstractCommand {
    @CommandLine.ArgGroup(multiplicity = "1")
    Exclusive exclusive;

    static class Exclusive {
        @CommandLine.Option(names = {"-f", "--file"}, description = "CRD input file")
        File inputFile;

        @CommandLine.Option(names = {"-c", "--content"}, description = "CRD content")
        String content;
    }

    @CommandLine.Option(names = {"-o", "--output"}, description = "CRD output file")
    File outputFile;

    protected String extension;

    protected abstract Object run(byte[] data) throws IOException;

    @Override
    public void run() {
        try {
            byte[] data;
            if (exclusive.inputFile != null) {
                String name = exclusive.inputFile.getName();
                extension = name.substring(name.lastIndexOf('.') + 1);
                data = IoUtil.toBytes(new FileInputStream(exclusive.inputFile));
            } else if (exclusive.content != null && exclusive.content.length() > 0) {
                data = IoUtil.toBytes(exclusive.content);
            } else {
                throw new IllegalArgumentException("Missing content!");
            }
            if (debug) {
                String content = IoUtil.toString(data);
                log.info("Content: " + content);
                data = IoUtil.toBytes(content);
            }

            Object result = run(data);

            if (outputFile != null && result != null) {
                Files.copy(
                        new ByteArrayInputStream(IoUtil.toBytes(result.toString())),
                        outputFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
                result = "Content written to " + outputFile;
            }

            println(String.format("Response [%s]: \n\n" + result, spec.name()));
            println("\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
