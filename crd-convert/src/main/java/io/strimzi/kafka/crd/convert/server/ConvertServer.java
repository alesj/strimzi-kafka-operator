/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.kafka.crd.convert.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.strimzi.api.annotations.ApiVersion;
import io.strimzi.kafka.crd.convert.converter.Converter;
import io.strimzi.kafka.crd.convert.converter.KafkaConverter;
import io.strimzi.kafka.crd.convert.utils.ContentType;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PfxOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.lang.Integer.parseInt;

public class ConvertServer {

    private static final Logger LOGGER = LogManager.getLogger(ConvertServer.class);

    public static final String CONTENT_TYPE = "Content-Type";
    public static final int SC_OK = HttpURLConnection.HTTP_OK;
    public static final int SC_INTERNAL_SERVER_ERROR = HttpURLConnection.HTTP_INTERNAL_ERROR;
    public static final String KEYSTORE = "keystore";
    public static final String KEYSTORE_PASSWORD = "keystore.password";
    public static final String CERTSTORE = "certstore";
    public static final String TLS_PROTOCOLS = "tls.protocols";
    public static final String PLAIN_PORT = "plain.port";
    public static final String TLS_PORT = "tls.port";
    public static final String HOST = "HOST";

    private final Map<String, Converter<?>> bindings;
    private HttpServer httpServer;

    public ConvertServer() {
        this(Collections.singletonMap("Kafka", new KafkaConverter()));
    }

    public ConvertServer(Map<String, Converter<?>> bindings) {
        this.bindings = bindings;
    }

    private void doConvert(HttpServerRequest request, long startTime, HttpServerResponse response) {
        String contentType = request.headers().get(CONTENT_TYPE);
        ContentType ct = ContentType.findByContentType(contentType);
        if (ct == null) {
            LOGGER.warn("Unexpected request {} {}", CONTENT_TYPE, contentType);
            end(startTime, response, HttpURLConnection.HTTP_UNSUPPORTED_TYPE, Buffer.buffer()); // unsupported media type
            return;
        }
        request.bodyHandler(body -> {
            int sc = SC_INTERNAL_SERVER_ERROR;
            Buffer buffer = null;
            try {
                ObjectMapper mapper = ct.getMapper();
                ConversionReview conversionReview = mapper.readValue(body.getBytes(), ConversionReview.class);
                Request reviewRequest = conversionReview.getRequest();
                ResponseResult result = new ResponseResult();
                List<Object> converted;
                try {
                    converted = convert(ct, reviewRequest);
                    result.setStatus("Success");
                } catch (Exception e) {
                    converted = null;
                    result.setStatus("Failed");
                    result.setMessage(e.getMessage());
                    LOGGER.warn("Error converting {}", reviewRequest, e);
                    response.setStatusCode(SC_INTERNAL_SERVER_ERROR);
                }
                conversionReview.setRequest(null);
                Response reviewResponse = new Response();
                reviewResponse.setUid(reviewRequest.uid);
                reviewResponse.setConvertedObjects(converted);
                reviewResponse.setResult(result);
                conversionReview.setResponse(reviewResponse);
                response.putHeader(CONTENT_TYPE, ct.getContentType());
                buffer = serialize(request, reviewResponse, ct);
                sc = SC_OK;
            } catch (Exception e) {
                LOGGER.error("Error handling request to " + request.path(), e);
                buffer = null;
                sc = SC_INTERNAL_SERVER_ERROR;
            } finally {
                end(startTime, response, sc, buffer);
            }
        });
    }

    private void end(long startTime, HttpServerResponse response, int sc, Buffer buffer) {
        response.setStatusCode(sc);
        response.end(buffer);
        LOGGER.info("Returned {} byte {} response in {}ms", buffer.length(), sc, (System.nanoTime() - startTime) / 1_000_000);
    }

    private static Buffer serialize(HttpServerRequest request, Response reviewResponse, ContentType ct) throws JsonProcessingException {
        ObjectMapper mapper;
        if (Boolean.parseBoolean(request.params().get("pretty"))) {
            mapper = ct.getMapper().copy().enable(SerializationFeature.INDENT_OUTPUT);
        } else {
            mapper = ct.getMapper();
        }
        return Buffer.buffer(mapper.writeValueAsBytes(reviewResponse));
    }

    private void doUnexpectedRequested(HttpServerRequest request,
                                      long startTime,
                                      HttpServerResponse response,
                                      int sc) {
        LOGGER.info("Unexpected request {}, {}", request.method(), request.path());
        response.headers().add("Content-Type", "text/plain");
        end(startTime, response, sc, Buffer.buffer("I support GET /ready, GET /healthy & POST /convert\n"));
    }

    private static void doProbe(HttpServerRequest request, HttpServerResponse response) {
        LOGGER.trace("{}", request.path());
        response.setStatusCode(SC_OK);
        response.end();
    }

    private List<Object> convert(ContentType ct, Request reviewRequest) {
        String desiredAPIVersion = reviewRequest.getDesiredAPIVersion();
        //String desiredGroup = desiredAPIVersion.substring(0, desiredAPIVersion.indexOf('/'));
        ApiVersion desiredVersion = ApiVersion.parse(desiredAPIVersion.substring(desiredAPIVersion.indexOf('/') + 1));
        List<JsonNode> objects = reviewRequest.getObjects();
        List<Object> result = new ArrayList<>(objects.size());
        for (JsonNode node : objects) {
            JsonNode kindNode = node.get("kind");
            if (kindNode == null || kindNode.isNull()) {
                throw new IllegalStateException("No kind attribute found!");
            }
            String kind = kindNode.asText();
            Converter<?> converter = bindings.get(kind);
            if (converter == null) {
                throw new IllegalArgumentException("Could find converter for kind " + kind);
            }
            try {
                result.add(convert(ct, converter, node, desiredVersion, reviewRequest.isInstantiate()));
            } catch (RuntimeException | IOException e) {
                throw new RuntimeException("Could not convert object of kind " + kind, e);
            }
        }
        return result;
    }

    private static <T extends HasMetadata> Object convert(
            ContentType ct,
            Converter<T> converter,
            JsonNode node,
            ApiVersion desiredAPIVersion,
            boolean instantiate
    ) throws IOException {
        if (instantiate) {
            ObjectMapper mapper = ct.getMapper();
            T cr = mapper.readerFor(converter.crClass()).readValue(node);
            converter.convertTo(cr, desiredAPIVersion);
            return cr;
        } else {
            converter.convertTo(node, desiredAPIVersion);
            return node;
        }
    }

    private static String getString(String sysProp, String defaultValue) {
        String result = System.getProperty(sysProp);
        if (result == null) {
            result = System.getenv(sysProp.replace('.', '_').toUpperCase(Locale.ENGLISH));
        }
        if (result == null) {
            result = defaultValue;
        }
        return result;
    }

    private static int getInt(String sysProp, int defaultValue) {
        String str = getString(sysProp, String.valueOf(defaultValue));
        int result;
        try {
            result = parseInt(str);
        } catch (NumberFormatException e) {
            throw new RuntimeException("System property / env var " + sysProp + " is not an int");
        }
        return result;
    }

    public static void main(String[] args) {
        ConvertServer server = new ConvertServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
    }

    public AutoCloseable start() {
        String host = getString(HOST, "0.0.0.0");
        int plainPort = getInt(PLAIN_PORT, -1);
        int tlsPort = getInt(TLS_PORT, -1);
        HttpServerOptions options = new HttpServerOptions().setLogActivity(true);
        if (tlsPort > 0 || plainPort > 0) {
            if (plainPort > 0) {
                httpServer = createServer(host, plainPort, options);
            } else if (tlsPort > 0) {
                httpServer = createServer(host, tlsPort, configureHttpsOptions(options));
            }
        } else {
            exit("Either {} or {} must be specified", TLS_PORT, PLAIN_PORT);
        }
        return this::stop;
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.close();
        }
    }

    private static void exit(String message, Object... parameters) {
        LOGGER.error(message, parameters);
        System.exit(1);
    }

    private HttpServer createServer(String host, int port, HttpServerOptions options) {
        return Vertx.vertx()
            .createHttpServer(options)
            .requestHandler(this::requestHandler)
            .listen(port, host, ar -> {
                if (ar.succeeded()) {
                    LOGGER.info("Bound {} listener on host {}, port {}",
                            options.isSsl() ? "TLS" : "plain", host, port);
                } else {
                    exit("Error binding {} listener on host {}, port {}",
                            options.isSsl() ? "TLS" : "plain", host, port, ar.cause());
                }
            });
    }

    private void requestHandler(HttpServerRequest request) {
        long startTime = System.nanoTime();
        HttpServerResponse response = request.response();
        String path = request.path().replaceAll("/+$", "");
        switch (path) {
            case "/convert":
                if (HttpMethod.POST.equals(request.method())) {
                    doConvert(request, startTime, response);
                } else {
                    doUnexpectedRequested(request, startTime, response, HttpURLConnection.HTTP_BAD_METHOD); // method not supported
                }
                break;
            case "/ready":
            case "/healthy":
                if (HttpMethod.GET.equals(request.method())) {
                    doProbe(request, response);
                } else {
                    doUnexpectedRequested(request, startTime, response, HttpURLConnection.HTTP_BAD_METHOD); // method not supported
                }
                break;
            default:
                doUnexpectedRequested(request, startTime, response, HttpURLConnection.HTTP_NOT_FOUND); // not found
                break;
        }
    }

    private static HttpServerOptions configureHttpsOptions(HttpServerOptions options) {
        options = new HttpServerOptions(options);
        options.setSsl(true);
        String keystorePath = getString(KEYSTORE, null);
        if (keystorePath == null) {
            LOGGER.warn("No {} specified; running without SSL", KEYSTORE);
        } else {
            options.setSsl(true);
            if (keystorePath.endsWith(".jks")) {
                options.setKeyStoreOptions(new JksOptions().setPath(keystorePath)
                        .setPassword(getString(KEYSTORE_PASSWORD, null)));
            } else if (keystorePath.endsWith(".pfx")) {
                options.setPfxKeyCertOptions(new PfxOptions().setPath(keystorePath)
                        .setPassword(getString(KEYSTORE_PASSWORD, null)));
            } else if (keystorePath.endsWith(".pem")) {
                String certPath = getString(CERTSTORE, null);
                if (certPath == null) {
                    throw new RuntimeException(CERTSTORE + " must be specified if using a PEM keystore");
                }
                options.setPemKeyCertOptions(
                        new PemKeyCertOptions()
                                .setKeyPath(keystorePath)
                                .setCertPath(certPath));
            } else {
                throw new RuntimeException(KEYSTORE + " should be end with one of '.jks', '.pfx' or '.pem'");
            }
        }
        options.getEnabledSecureTransportProtocols()
                .forEach(options::removeEnabledSecureTransportProtocol);
        Arrays.stream(getString(TLS_PROTOCOLS, "TLSv1.2,TLSv1.3").split(" *, *"))
                .forEach(options::addEnabledSecureTransportProtocol);
        return options;
    }
}
