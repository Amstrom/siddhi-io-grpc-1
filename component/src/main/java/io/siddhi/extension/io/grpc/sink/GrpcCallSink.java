/*
 * Copyright (c)  2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.siddhi.extension.io.grpc.sink;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.exception.SiddhiAppRuntimeException;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.transport.DynamicOptions;
import io.siddhi.core.util.transport.OptionHolder;
import io.siddhi.extension.io.grpc.util.GrpcConstants;
import io.siddhi.extension.io.grpc.util.GrpcSourceRegistry;
import io.siddhi.query.api.exception.SiddhiAppValidationException;
import org.apache.log4j.Logger;
import org.wso2.grpc.Event;
import org.wso2.grpc.EventServiceGrpc;

import java.util.concurrent.TimeUnit;

/**
 * {@code GrpcCallSink} Handle the gRPC publishing tasks and injects response into grpc-call-response source.
 */
@Extension(name = "grpc-call", namespace = "sink", description = "This extension publishes event data encoded into " +
        "GRPC Classes as defined in the user input jar. This extension has a default gRPC service classes jar " +
        "added. The default service is called \"EventService\". Please find the following protobuf definition. \n\n" +
        "-------------EventService.proto--------------\n" +
        "syntax = \"proto3\";\n" +
        "\n" +
        "option java_multiple_files = true;\n" +
        "option java_package = \"org.wso2.grpc\";\n" +
        "\n" +
        "package org.wso2.grpc.eventservice;\n" +
        "\n" +
        "import \"google/protobuf/empty.proto\";\n" +
        "\n" +
        "service EventService {\n" +
        "    rpc process(Event) returns (Event) {}\n" +
        "\n" +
        "    rpc consume(Event) returns (google.protobuf.Empty) {}\n" +
        "}\n" +
        "\n" +
        "message Event {\n" +
        "    string payload = 1;\n" +
        "}\n" +
        "----------------------------------------------\n\n" +
        "This grpc-call sink is used for scenarios where we send a request out and expect a response back. In " +
        "default mode this will use EventService process method. grpc-call-response source is used to receive the " +
        "responses. A unique sink.id is used to correlate between the sink and its corresponding source.",
        parameters = {
                @Parameter(
                        name = "url",
                        description = "The url to which the outgoing events should be published via this extension. " +
                                "This url should consist the host address, port, service name, method name in the " +
                                "following format. `grpc://0.0.0.0:9763/<serviceName>/<methodName>`" ,
                        type = {DataType.STRING}),
                @Parameter(
                        name = "sink.id",
                        description = "a unique ID that should be set for each grpc-call-sink. There is a 1:1 " +
                                "mapping between grpc-call sinks and grpc-call-response sources. Each sink has one " +
                                "particular source listening to the responses to requests published from that sink. " +
                                "So the same sink.id should be given when writing the source also." ,
                        type = {DataType.INT}),
                @Parameter(
                        name = "headers",
                        description = "GRPC Request headers in format `\"'<key>:<value>','<key>:<value>'\"`. " +
                                "If header parameter is not provided just the payload is sent" ,
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "null"),
                @Parameter(
                        name = "idle.timeout",
                        description = "Set the duration in seconds without ongoing RPCs before going to idle mode." ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "1800"),
                @Parameter(
                        name = "keep.alive.time",
                        description = "Sets the time in seconds without read activity before sending a keepalive " +
                                "ping. Keepalives can increase the load on services so must be used with caution. By " +
                                "default set to Long.MAX_VALUE which disables keep alive pinging." ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "Long.MAX_VALUE"),
                @Parameter(
                        name = "keep.alive.timeout",
                        description = "Sets the time in seconds waiting for read activity after sending a keepalive " +
                                "ping." ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "20"),
                @Parameter(
                        name = "keep.alive.without.calls",
                        description = "Sets whether keepalive will be performed when there are no outstanding RPC " +
                                "on a connection." ,
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "false"),
                @Parameter(
                        name = "enable.retry",
                        description = "Enables the retry and hedging mechanism provided by the gRPC library." ,
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "false"),
                @Parameter(
                        name = "max.retry.attempts",
                        description = "Sets max number of retry attempts. The total number of retry attempts for " +
                                "each RPC will not exceed this number even if service config may allow a higher " +
                                "number." ,
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "5"),
                @Parameter(
                        name = "max.hedged.attempts",
                        description = "Sets max number of hedged attempts. The total number of hedged attempts for " +
                                "each RPC will not exceed this number even if service config may allow a higher " +
                                "number." ,
                        type = {DataType.INT},
                        optional = true,
                        defaultValue = "5"),
                @Parameter(
                        name = "retry.buffer.size",
                        description = "Sets the retry buffer size in bytes. If the buffer limit is exceeded, no " +
                                "RPC could retry at the moment, and in hedging case all hedges but one of the same " +
                                "RPC will cancel." ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "16777216"),
                @Parameter(
                        name = "per.rpc.buffer.size",
                        description = "Sets the per RPC buffer limit in bytes used for retry. The RPC is not " +
                                "retriable if its buffer limit is exceeded." ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "1048576"),
                @Parameter(
                        name = "channel.termination.waiting.time",
                        description = "The time in seconds to wait for the channel to become terminated, giving up " +
                                "if the timeout is reached." ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "5"),
                @Parameter(
                        name = "max.inbound.message.size",
                        description = "Sets the maximum message size allowed to be received on the channel in bytes" ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "4194304"),
                @Parameter(
                        name = "max.inbound.metadata.size",
                        description = "Sets the maximum size of metadata allowed to be received in bytes" ,
                        type = {DataType.LONG},
                        optional = true,
                        defaultValue = "8192"),
        },
        examples = {
                @Example(syntax = "" +
                        "@sink(type='grpc-call',\n" +
                        "      url = 'grpc://194.23.98.100:8080/EventService/process',\n" +
                        "      sink.id= '1', @map(type='json'))\n" +
                        "define stream FooStream (message String);\n",
                        description = "" +
                                "Here a stream named FooStream is defined with grpc sink. A grpc server " +
                                "should be running at 194.23.98.100 listening to port 8080. sink.id is set to 1 here." +
                                " So we can write a source with sink.id 1 so that it will listen to responses for " +
                                "requests published from this stream. Note that since we are using EventService/" +
                                "process the sink will be operating in default mode"
                ),
                @Example(syntax = "" +
                        "@sink(type='grpc-call',\n" +
                        "      url = 'grpc://194.23.98.100:8080/EventService/process',\n" +
                        "      sink.id= '1', @map(type='json'))\n" +
                        "define stream FooStream (message String);\n" +
                        "\n" +
                        "@source(type='grpc-call-response', sink.id= '1')\n" +
                        "define stream BarStream (message String);",
                        description = "Here with the same FooStream definition we have added a BarStream which has " +
                                "a grpc-call-response source with the same sink.id 1. So the responses for calls " +
                                "sent from the FooStream will be added to BarStream."
                )
        }
)
public class GrpcCallSink extends AbstractGrpcSink {
    private static final Logger logger = Logger.getLogger(GrpcCallSink.class.getName());
    protected String sinkID;

    @Override
    public void initSink(OptionHolder optionHolder) {
        managedChannelBuilder.maxInboundMessageSize(Integer.parseInt(optionHolder.getOrCreateOption( //todo: remove the optional param default if not given
                GrpcConstants.MAX_INBOUND_MESSAGE_SIZE, GrpcConstants.MAX_INBOUND_MESSAGE_SIZE_DEFAULT).getValue()));
        managedChannelBuilder.maxInboundMetadataSize(Integer.parseInt(optionHolder.getOrCreateOption(
                GrpcConstants.MAX_INBOUND_METADATA_SIZE, GrpcConstants.MAX_INBOUND_METADATA_SIZE_DEFAULT).getValue()));
        if (optionHolder.isOptionExists(GrpcConstants.SINK_ID)) {
            this.sinkID = optionHolder.validateAndGetOption(GrpcConstants.SINK_ID).getValue();
        } else {
            if (optionHolder.validateAndGetOption(GrpcConstants.SINK_TYPE_OPTION) //todo: remove error thorw.
                    .getValue().equalsIgnoreCase(GrpcConstants.GRPC_CALL_SINK_NAME)) {
                throw new SiddhiAppValidationException(siddhiAppContext.getName() + ":" + streamID + ": For " +
                        "grpc-call sink the parameter sink.id is mandatory for receiving responses. Please provide " +
                        "a sink.id");
            }
        }
    }

    @Override
    public void publish(Object payload, DynamicOptions dynamicOptions, State state)
            throws ConnectionUnavailableException { //todo: throw connection unavailable exception. fix headers
        if (isDefaultMode) {
            if (!(payload instanceof String)) {
                throw new SiddhiAppRuntimeException(siddhiAppContext.getName() + ":" + streamID + ": Payload should " +
                        "be of type String for default EventService but found " + payload.getClass().getName()); //todo: no need to check
            }
            Event sequenceCallRequest = Event.newBuilder().setPayload((String) payload).build(); //todo to string
            EventServiceGrpc.EventServiceFutureStub currentFutureStub = futureStub;

            if (sequenceName != null || headersOption != null) { //todo: have a method in abstract class and use in both sinks
                Metadata header = new Metadata();
                String headers = "";
                if (sequenceName != null) {
                    headers += "'sequence:" + sequenceName + "'";
                    if (headersOption != null) {
                        headers += ",";
                    }
                }
                if (headersOption != null) {
                    headers +=  headersOption.getValue(dynamicOptions);
                }
                Metadata.Key<String> key =
                        Metadata.Key.of(GrpcConstants.HEADERS, Metadata.ASCII_STRING_MARSHALLER);
                header.put(key, headers);
                currentFutureStub = MetadataUtils.attachHeaders(futureStub, header);
            }
            ListenableFuture<Event> futureResponse =
                    currentFutureStub.process(sequenceCallRequest);
            Futures.addCallback(futureResponse, new FutureCallback<Event>() {
                @Override
                public void onSuccess(Event result) {
                    GrpcSourceRegistry.getInstance().getGrpcCallResponseSourceSource(sinkID).onResponse(result); //todo check if the associated source is available in connect
                }

                @Override
                public void onFailure(Throwable t) { //todo: simulate connection unavailable and auth error and check the error message
                    logger.error(siddhiAppContext.getName() + ":" + streamID + ": " + t.getMessage());
                }
            }, MoreExecutors.directExecutor());
        } else {
            //todo: handle publishing to generic service
        }
    }

    /**
     * This method will be called before the processing method.
     * Intention to establish connection to publish event.
     * @throws ConnectionUnavailableException if end point is unavailable the ConnectionUnavailableException thrown
     *                                        such that the  system will take care retrying for connection
     */
    @Override
    public void connect() throws ConnectionUnavailableException {
        this.channel = ManagedChannelBuilder.forTarget(address).usePlaintext().build();
        this.futureStub = EventServiceGrpc.newFutureStub(channel);
        logger.info(siddhiAppContext.getName() + ": gRPC service on " + streamID + " has successfully connected to "
                + url);
    }

    /**
     * Called after all publishing is done, or when {@link ConnectionUnavailableException} is thrown
     * Implementation of this method should contain the steps needed to disconnect from the sink.
     */
    @Override
    public void disconnect() {
        try {
            channel.shutdown().awaitTermination(channelTerminationWaitingTime, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new SiddhiAppRuntimeException(siddhiAppContext.getName() + ":" + streamID + ": Error in shutting " +
                    "down the channel. " + e.getMessage());
        }
    }
}
