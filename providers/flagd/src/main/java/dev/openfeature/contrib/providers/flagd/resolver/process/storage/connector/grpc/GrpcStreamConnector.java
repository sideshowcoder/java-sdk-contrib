package dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.grpc;

import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import dev.openfeature.contrib.providers.flagd.resolver.common.ChannelBuilder;
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.Connector;
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.StreamPayload;
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.StreamPayloadType;
import dev.openfeature.flagd.sync.FlagSyncServiceGrpc;
import dev.openfeature.flagd.sync.SyncService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.ManagedChannel;
import lombok.extern.java.Log;

import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Implements the {@link Connector} contract and emit flags obtained from flagd sync gRPC contract.
 */
@Log
@SuppressFBWarnings(value = {"PREDICTABLE_RANDOM", "EI_EXPOSE_REP"},
        justification = "Random is used to generate a variation & flag configurations require exposing")
public class GrpcStreamConnector implements Connector {
    private static final Random RANDOM = new Random();

    private static final int INIT_BACK_OFF = 2 * 1000;
    private static final int MAX_BACK_OFF = 120 * 1000;

    private static final int QUEUE_SIZE = 5;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final BlockingQueue<StreamPayload> blockingQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);

    private final ManagedChannel channel;
    private final FlagSyncServiceGrpc.FlagSyncServiceStub serviceStub;

    public GrpcStreamConnector(final FlagdOptions options) {
        channel = ChannelBuilder.nettyChannel(options);
        serviceStub = FlagSyncServiceGrpc.newStub(channel);
    }

    /**
     * Initialize gRPC stream connector.
     */
    public void init() {
        Thread listener = new Thread(() -> {
            try {
                observeEventStream(blockingQueue, shutdown, serviceStub);
            } catch (InterruptedException e) {
                log.log(Level.WARNING, "gRPC event stream interrupted, flag configurations are stale", e);
            }
        });

        listener.setDaemon(true);
        listener.start();
    }

    /**
     * Get blocking queue to obtain payloads exposed by this connector.
     */
    public BlockingQueue<StreamPayload> getStream() {
        return blockingQueue;
    }

    /**
     * Shutdown gRPC stream connector.
     */
    public void shutdown() {
        if (shutdown.getAndSet(true)) {
            return;
        }

        channel.shutdown();
    }

    /**
     * Contains blocking calls, to be used concurrently.
     */
    static void observeEventStream(final BlockingQueue<StreamPayload> writeTo,
                                   final AtomicBoolean shutdown,
                                   final FlagSyncServiceGrpc.FlagSyncServiceStub serviceStub)
            throws InterruptedException {

        final BlockingQueue<GrpcResponseModel> streamReceiver = new LinkedBlockingQueue<>(QUEUE_SIZE);
        int retryDelay = INIT_BACK_OFF;

        while (!shutdown.get()) {
            final SyncService.SyncFlagsRequest request = SyncService.SyncFlagsRequest.newBuilder().build();
            serviceStub.syncFlags(request, new GrpcStreamHandler(streamReceiver));

            while (!shutdown.get()) {
                final GrpcResponseModel response = streamReceiver.take();

                if (response.isComplete()) {
                    // The stream is complete. This is not considered as an error
                    break;
                }

                if (response.getError() != null) {
                    log.log(Level.WARNING,
                            String.format("Error from grpc connection, retrying in %dms", retryDelay),
                            response.getError());

                    if (!writeTo.offer(
                            new StreamPayload(StreamPayloadType.ERROR, "Error from stream connection, retrying"))) {
                        log.log(Level.WARNING, "Failed to convey ERROR satus, queue is full");
                    }
                    break;
                }

                final SyncService.SyncFlagsResponse flagsResponse = response.getSyncFlagsResponse();
                switch (flagsResponse.getState()) {
                    case SYNC_STATE_ALL:
                        if (!writeTo.offer(
                                new StreamPayload(StreamPayloadType.DATA, flagsResponse.getFlagConfiguration()))) {
                            log.log(Level.WARNING, "Stream writing failed");
                        }
                        break;
                    case SYNC_STATE_UNSPECIFIED:
                    case SYNC_STATE_ADD:
                    case SYNC_STATE_UPDATE:
                    case SYNC_STATE_DELETE:
                    case SYNC_STATE_PING:
                    case UNRECOGNIZED:
                    default:
                        log.info(
                                String.format("Ignored - received payload of state: %s", flagsResponse.getState()));
                }

                // reset retry delay if we succeeded in a retry attempt
                retryDelay = INIT_BACK_OFF;
            }

            // check for shutdown and avoid sleep
            if (shutdown.get()) {
                log.log(Level.INFO, "Shutdown invoked, exiting event stream listener");
                return;
            }

            // busy wait till next attempt
            Thread.sleep(retryDelay + RANDOM.nextInt(INIT_BACK_OFF));

            if (retryDelay < MAX_BACK_OFF) {
                retryDelay = 2 * retryDelay;
            }
        }

        // log as this can happen after awakened from backoff sleep
        log.log(Level.INFO, "Shutdown invoked, exiting event stream listener");
    }
}