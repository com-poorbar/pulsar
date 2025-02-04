/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.cli;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.RateLimiter;
import com.google.gson.JsonParseException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.apache.pulsar.client.api.schema.KeyValueSchema;
import org.apache.pulsar.client.impl.schema.SchemaInfoImpl;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.websocket.data.ProducerMessage;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * pulsar-client produce command implementation.
 */
@Command(description = "Produce messages to a specified topic")
public class CmdProduce extends AbstractCmd {

    private static final Logger LOG = LoggerFactory.getLogger(PulsarClientTool.class);
    private static final int MAX_MESSAGES = 1000;
    static final String KEY_VALUE_ENCODING_TYPE_NOT_SET = "";
    private static final String KEY_VALUE_ENCODING_TYPE_SEPARATED = "separated";
    private static final String KEY_VALUE_ENCODING_TYPE_INLINE = "inline";

    @Parameters(description = "TopicName", arity = "1")
    private String topic;

    @Option(names = { "-m", "--messages" },
            description = "Messages to send, either -m or -f must be specified. Specify -m for each message.")
    private List<String> messages = new ArrayList<>();

    @Option(names = { "-f", "--files" },
               description = "Comma separated file paths to send, either -m or -f must be specified.")
    private List<String> messageFileNames = new ArrayList<>();

    @Option(names = { "-n", "--num-produce" },
               description = "Number of times to send message(s), the count of messages/files * num-produce "
                       + "should below than " + MAX_MESSAGES + ".")
    private int numTimesProduce = 1;

    @Option(names = { "-r", "--rate" },
               description = "Rate (in msg/sec) at which to produce,"
                       + " value 0 means to produce messages as fast as possible.")
    private double publishRate = 0;

    @Option(names = { "-db", "--disable-batching" }, description = "Disable batch sending of messages")
    private boolean disableBatching = false;

    @Option(names = { "-c",
            "--chunking" }, description = "Should split the message and publish in chunks if message size is "
            + "larger than allowed max size")
    private boolean chunkingAllowed = false;

    @Option(names = { "-s", "--separator" },
               description = "Character to split messages string on default is comma")
    private String separator = ",";

    @Option(names = { "-p", "--properties"}, description = "Properties to add, Comma separated "
            + "key=value string, like k1=v1,k2=v2.")
    private List<String> properties = new ArrayList<>();

    @Option(names = { "-k", "--key"}, description = "Partitioning key to add to each message")
    private String key;
    @Option(names = { "-kvk", "--key-value-key"}, description = "Value to add as message key in KeyValue schema")
    private String keyValueKey;
    @Option(names = {"-kvkf", "--key-value-key-file"},
            description = "Path to file containing the value to add as message key in KeyValue schema. "
            + "JSON and AVRO files are supported.")
    private String keyValueKeyFile;

    @Option(names = { "-vs", "--value-schema"}, description = "Schema type (can be bytes,avro,json,string...)")
    private String valueSchema = "bytes";

    @Option(names = { "-ks", "--key-schema"}, description = "Schema type (can be bytes,avro,json,string...)")
    private String keySchema = "string";

    @Option(names = { "-kvet", "--key-value-encoding-type"},
            description = "Key Value Encoding Type (it can be separated or inline)")
    private String keyValueEncodingType = null;

    @Option(names = { "-ekn", "--encryption-key-name" }, description = "The public key name to encrypt payload")
    private String encKeyName = null;

    @Option(names = { "-ekv",
            "--encryption-key-value" }, description = "The URI of public key to encrypt payload, for example "
                    + "file:///path/to/public.key or data:application/x-pem-file;base64,*****")
    private String encKeyValue = null;

    @Option(names = { "-dr",
            "--disable-replication" }, description = "Disable geo-replication for messages.")
    private boolean disableReplication = false;

    private ClientBuilder clientBuilder;
    private Authentication authentication;
    private String serviceURL;

    public CmdProduce() {
        // Do nothing
    }

    /**
     * Set Pulsar client configuration.
     */
    public void updateConfig(ClientBuilder newBuilder, Authentication authentication, String serviceURL) {
        this.clientBuilder = newBuilder;
        this.authentication = authentication;
        this.serviceURL = serviceURL;
    }

    /*
     * Generate a list of message bodies which can be used to build messages
     *
     * @param stringMessages List of strings to send
     *
     * @param messageFileNames List of file names to read and send
     *
     * @return list of message bodies
     */
    static List<byte[]> generateMessageBodies(List<String> stringMessages, List<String> messageFileNames,
                                              Schema schema) {
        List<byte[]> messageBodies = new ArrayList<>();

        for (String m : stringMessages) {
            if (schema.getSchemaInfo().getType() == SchemaType.AVRO) {
                // JSON TO AVRO
                org.apache.avro.Schema avroSchema = ((Optional<org.apache.avro.Schema>) schema.getNativeSchema()).get();
                byte[] encoded = jsonToAvro(m, avroSchema);
                messageBodies.add(encoded);
            } else {
                messageBodies.add(m.getBytes());
            }
        }

        try {
            for (String filename : messageFileNames) {
                byte[] fileBytes = Files.readAllBytes(Paths.get(filename));
                messageBodies.add(fileBytes);
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }

        return messageBodies;
    }

    private static byte[] jsonToAvro(String m, org.apache.avro.Schema avroSchema) {
        try {
            GenericDatumReader<Object> reader = new GenericDatumReader<>(avroSchema);
            JsonDecoder jsonDecoder = DecoderFactory.get().jsonDecoder(avroSchema, m);
            GenericDatumWriter<Object> writer = new GenericDatumWriter<>(avroSchema);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Encoder e = EncoderFactory.get().binaryEncoder(out, null);
            Object datum = null;
            while (true) {
                try {
                    datum = reader.read(datum, jsonDecoder);
                } catch (EOFException eofException) {
                    break;
                }
                writer.write(datum, e);
                e.flush();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Cannot convert " + m + " to AVRO " + e.getMessage(), e);
        }
    }

    @Spec
    private CommandSpec commandSpec;

    /**
     * Run the producer.
     *
     * @return 0 for success, < 0 otherwise
     * @throws Exception
     */
    public int run() throws PulsarClientException {
        if (this.numTimesProduce <= 0) {
            throw new CommandLine.ParameterException(commandSpec.commandLine(),
                    "Number of times need to be positive number.");
        }

        if (messages.size() > 0) {
            messages = messages.stream().map(str -> str.split(separator)).flatMap(Stream::of).toList();
        }

        if (messages.size() == 0 && messageFileNames.size() == 0) {
            throw new CommandLine.ParameterException(commandSpec.commandLine(),
                    "Please supply message content with either --messages or --files");
        }

        if (keyValueEncodingType == null) {
            keyValueEncodingType = KEY_VALUE_ENCODING_TYPE_NOT_SET;
        } else {
            switch (keyValueEncodingType) {
                case KEY_VALUE_ENCODING_TYPE_SEPARATED:
                case KEY_VALUE_ENCODING_TYPE_INLINE:
                    break;
                default:
                    throw (new IllegalArgumentException("--key-value-encoding-type "
                            + keyValueEncodingType + " is not valid, only 'separated' or 'inline'"));
            }
        }

        int totalMessages = (messages.size() + messageFileNames.size()) * numTimesProduce;
        if (totalMessages > MAX_MESSAGES) {
            String msg = "Attempting to send " + totalMessages + " messages. Please do not send more than "
                    + MAX_MESSAGES + " messages";
            throw new IllegalArgumentException(msg);
        }

        if (this.serviceURL.startsWith("ws")) {
            return publishToWebSocket(topic);
        } else {
            return publish(topic);
        }
    }

    private int publish(String topic) {
        int numMessagesSent = 0;
        int returnCode = 0;

        try (PulsarClient client = clientBuilder.build()){
            Schema<?> schema = buildSchema(this.keySchema, this.valueSchema, this.keyValueEncodingType);
            ProducerBuilder<?> producerBuilder = client.newProducer(schema).topic(topic);
            if (this.chunkingAllowed) {
                producerBuilder.enableChunking(true);
                producerBuilder.enableBatching(false);
            } else if (this.disableBatching) {
                producerBuilder.enableBatching(false);
            }
            if (isNotBlank(this.encKeyName) && isNotBlank(this.encKeyValue)) {
                producerBuilder.addEncryptionKey(this.encKeyName);
                producerBuilder.defaultCryptoKeyReader(this.encKeyValue);
            }
            try (Producer<?> producer = producerBuilder.create();) {
                Schema<?> schemaForPayload = schema.getSchemaInfo().getType() == SchemaType.KEY_VALUE
                        ? ((KeyValueSchema) schema).getValueSchema() : schema;
                List<byte[]> messageBodies = generateMessageBodies(this.messages, this.messageFileNames,
                        schemaForPayload);
                RateLimiter limiter = (this.publishRate > 0) ? RateLimiter.create(this.publishRate) : null;

                Map<String, String> kvMap = new HashMap<>();
                for (String property : properties) {
                    String[] kv = property.split("=");
                    kvMap.put(kv[0], kv[1]);
                }

                final byte[] keyValueKeyBytes;
                if (this.keyValueKey != null) {
                    if (keyValueEncodingType == KEY_VALUE_ENCODING_TYPE_NOT_SET) {
                        throw new IllegalArgumentException(
                            "Key value encoding type must be set when using --key-value-key");
                    }
                    keyValueKeyBytes = this.keyValueKey.getBytes(StandardCharsets.UTF_8);
                } else if (this.keyValueKeyFile != null) {
                    if (keyValueEncodingType == KEY_VALUE_ENCODING_TYPE_NOT_SET) {
                        throw new IllegalArgumentException(
                            "Key value encoding type must be set when using --key-value-key-file");
                    }
                    keyValueKeyBytes = Files.readAllBytes(Paths.get(this.keyValueKeyFile));
                } else if (this.key != null) {
                    keyValueKeyBytes = this.key.getBytes(StandardCharsets.UTF_8);
                } else {
                    keyValueKeyBytes = null;
                }

                for (int i = 0; i < this.numTimesProduce; i++) {
                    for (byte[] content : messageBodies) {
                        if (limiter != null) {
                            limiter.acquire();
                        }

                        TypedMessageBuilder message = producer.newMessage();

                        if (!kvMap.isEmpty()) {
                            message.properties(kvMap);
                        }

                        switch (keyValueEncodingType) {
                            case KEY_VALUE_ENCODING_TYPE_NOT_SET:
                                if (key != null && !key.isEmpty()) {
                                    message.key(key);
                                }
                                message.value(content);
                                break;
                            case KEY_VALUE_ENCODING_TYPE_SEPARATED:
                            case KEY_VALUE_ENCODING_TYPE_INLINE:
                                KeyValue kv = new KeyValue<>(
                                        keyValueKeyBytes,
                                        content);
                                message.value(kv);
                                break;
                            default:
                                throw new IllegalStateException();
                        }

                        if (disableReplication) {
                            message.disableReplication();
                        }

                        message.send();


                        numMessagesSent++;
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error while producing messages");
            LOG.error(e.getMessage(), e);
            returnCode = -1;
        } finally {
            LOG.info("{} messages successfully produced", numMessagesSent);
        }

        return returnCode;
    }

    static Schema<?> buildSchema(String keySchema, String schema, String keyValueEncodingType) {
        switch (keyValueEncodingType) {
            case KEY_VALUE_ENCODING_TYPE_NOT_SET:
                return buildComponentSchema(schema);
            case KEY_VALUE_ENCODING_TYPE_SEPARATED:
                return Schema.KeyValue(buildComponentSchema(keySchema), buildComponentSchema(schema),
                        KeyValueEncodingType.SEPARATED);
            case KEY_VALUE_ENCODING_TYPE_INLINE:
                return Schema.KeyValue(buildComponentSchema(keySchema), buildComponentSchema(schema),
                        KeyValueEncodingType.INLINE);
            default:
                throw new IllegalArgumentException("Invalid KeyValueEncodingType "
                        + keyValueEncodingType + ", only: 'none','separated' and 'inline");
        }
    }

    private static Schema<?> buildComponentSchema(String schema) {
        Schema<?> base;
        switch (schema) {
            case "string":
                base = Schema.STRING;
                break;
            case "bytes":
                // no need for wrappers
                return Schema.BYTES;
            default:
                if (schema.startsWith("avro:")) {
                    base = buildGenericSchema(SchemaType.AVRO, schema.substring(5));
                } else if (schema.startsWith("json:")) {
                    base = buildGenericSchema(SchemaType.JSON, schema.substring(5));
                } else {
                    throw new IllegalArgumentException("Invalid schema type: " + schema);
                }
        }
        return Schema.AUTO_PRODUCE_BYTES(base);
    }

    private static Schema<?> buildGenericSchema(SchemaType type, String definition) {
        return Schema.generic(SchemaInfoImpl
                .builder()
                .schema(definition.getBytes(StandardCharsets.UTF_8))
                .name("client")
                .properties(new HashMap<>())
                .type(type)
                .build());

    }

    @SuppressWarnings("deprecation")
    @VisibleForTesting
    public String getWebSocketProduceUri(String topic) {
        String serviceURLWithoutTrailingSlash = serviceURL.substring(0,
                serviceURL.endsWith("/") ? serviceURL.length() - 1 : serviceURL.length());

        TopicName topicName = TopicName.get(topic);
        String wsTopic;
        if (topicName.isV2()) {
            wsTopic = String.format("%s/%s/%s/%s", topicName.getDomain(), topicName.getTenant(),
                    topicName.getNamespacePortion(), topicName.getLocalName());
        } else {
            wsTopic = String.format("%s/%s/%s/%s/%s", topicName.getDomain(), topicName.getTenant(),
                    topicName.getCluster(), topicName.getNamespacePortion(), topicName.getLocalName());
        }

        String uriFormat = "%s/ws" + (topicName.isV2() ? "/v2/" : "/") + "producer/%s";
        return String.format(uriFormat, serviceURLWithoutTrailingSlash, wsTopic);
    }

    @SuppressWarnings("deprecation")
    private int publishToWebSocket(String topic) {
        int numMessagesSent = 0;
        int returnCode = 0;

        URI produceUri = URI.create(getWebSocketProduceUri(topic));

        WebSocketClient produceClient = new WebSocketClient(new SslContextFactory(true));
        ClientUpgradeRequest produceRequest = new ClientUpgradeRequest();
        try {
            if (authentication != null) {
                authentication.start();
                AuthenticationDataProvider authData = authentication.getAuthData(produceUri.getHost());
                if (authData.hasDataForHttp()) {
                    for (Map.Entry<String, String> kv : authData.getHttpHeaders()) {
                        produceRequest.setHeader(kv.getKey(), kv.getValue());
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Authentication plugin error: " + e.getMessage());
            return -1;
        }

        CompletableFuture<Void> connected = new CompletableFuture<>();
        ProducerSocket produceSocket = new ProducerSocket(connected);
        try {
            produceClient.start();
        } catch (Exception e) {
            LOG.error("Failed to start websocket-client", e);
            return -1;
        }

        try {
            LOG.info("Trying to create websocket session.. on {},{}", produceUri, produceRequest);
            produceClient.connect(produceSocket, produceUri, produceRequest);
            connected.get();
        } catch (Exception e) {
            LOG.error("Failed to create web-socket session", e);
            return -1;
        }

        try {
            List<byte[]> messageBodies = generateMessageBodies(this.messages, this.messageFileNames, Schema.BYTES);
            RateLimiter limiter = (this.publishRate > 0) ? RateLimiter.create(this.publishRate) : null;
            for (int i = 0; i < this.numTimesProduce; i++) {
                int index = i * 10;
                for (byte[] content : messageBodies) {
                    if (limiter != null) {
                        limiter.acquire();
                    }
                    produceSocket.send(index++, content).get(30, TimeUnit.SECONDS);
                    numMessagesSent++;
                }
            }
            produceSocket.close();
        } catch (Exception e) {
            LOG.error("Error while producing messages");
            LOG.error(e.getMessage(), e);
            returnCode = -1;
        } finally {
            LOG.info("{} messages successfully produced", numMessagesSent);
        }

        return returnCode;
    }

    @WebSocket(maxTextMessageSize = 64 * 1024)
    public static class ProducerSocket {

        private final CountDownLatch closeLatch;
        private Session session;
        private CompletableFuture<Void> connected;
        private volatile CompletableFuture<Void> result;

        public ProducerSocket(CompletableFuture<Void> connected) {
            this.closeLatch = new CountDownLatch(1);
            this.connected = connected;
        }

        public CompletableFuture<Void> send(int index, byte[] content) throws Exception {
            this.session.getRemote().sendString(getTestJsonPayload(index, content));
            this.result = new CompletableFuture<>();
            return result;
        }

        private static String getTestJsonPayload(int index, byte[] content) throws JsonProcessingException {
            ProducerMessage msg = new ProducerMessage();
            msg.payload = Base64.getEncoder().encodeToString(content);
            msg.key = Integer.toString(index);
            return ObjectMapperFactory.getMapper().writer().writeValueAsString(msg);
        }

        public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
            return this.closeLatch.await(duration, unit);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            LOG.info("Connection closed: {} - {}", statusCode, reason);
            this.session = null;
            this.closeLatch.countDown();
        }

        @OnWebSocketConnect
        public void onConnect(Session session) {
            LOG.info("Got connect: {}", session);
            this.session = session;
            this.connected.complete(null);
        }

        @OnWebSocketMessage
        public synchronized void onMessage(String msg) throws JsonParseException {
            LOG.info("ack= {}", msg);
            if (this.result != null) {
                this.result.complete(null);
            }
        }

        public RemoteEndpoint getRemote() {
            return this.session.getRemote();
        }

        public Session getSession() {
            return this.session;
        }

        public void close() {
            this.session.close();
        }

    }
}
