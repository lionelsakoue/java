package com.pubnub.api.managers;

import com.pubnub.api.PubNub;
import com.pubnub.api.builder.dto.PresenceOperation;
import com.pubnub.api.builder.dto.StateOperation;
import com.pubnub.api.builder.dto.SubscribeOperation;
import com.pubnub.api.builder.dto.UnsubscribeOperation;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.callbacks.ReconnectionCallback;
import com.pubnub.api.callbacks.SubscribeCallback;
import com.pubnub.api.endpoints.presence.Heartbeat;
import com.pubnub.api.endpoints.presence.Leave;
import com.pubnub.api.endpoints.pubsub.Subscribe;
import com.pubnub.api.enums.PNHeartbeatNotificationOptions;
import com.pubnub.api.enums.PNStatusCategory;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.server.SubscribeEnvelope;
import com.pubnub.api.models.server.SubscribeMessage;
import com.pubnub.api.workers.SubscribeMessageWorker;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

import static com.pubnub.api.managers.StateManager.MILLIS_IN_SECOND;

@Slf4j
public class SubscriptionManager {
    private static final int TWO_SECONDS = 2 * MILLIS_IN_SECOND;

    private static final int HEARTBEAT_INTERVAL_MULTIPLIER = 1000;

    private volatile boolean connected;

    private PubNub pubnub;
    private TelemetryManager telemetryManager;
    private Subscribe subscribeCall;
    private Heartbeat heartbeatCall;

    private LinkedBlockingQueue<SubscribeMessage> messageQueue;

    private DuplicationManager duplicationManager;

    /**
     * Store the latest timetoken to subscribe with, null by default to get the latest timetoken.
     */
    private Long timetoken;
    private Long storedTimetoken; // when changing the channel mix, store the timetoken for a later date.

    /**
     * Keep track of Region to support PSV2 specification.
     */
    private String region;

    /**
     * Timer for heartbeat operations.
     */
    private Timer timer;

    private StateManager subscriptionState;

    private ListenerManager listenerManager;
    private ReconnectionManager reconnectionManager;
    private DelayedReconnectionManager delayedReconnectionManager;
    private RetrofitManager retrofitManager;

    private Timer temporaryUnavailableChannelsDelayer;

    private Thread consumerThread;

    /**
     * lever to indicate if an announcement to the user about the subscription should be made.
     * the announcement happens only after the channel mix has been changed.
     */
    private boolean subscriptionStatusAnnounced;

    public SubscriptionManager(PubNub pubnubInstance, RetrofitManager retrofitManagerInstance,
                               TelemetryManager telemetry) {
        this.pubnub = pubnubInstance;
        this.telemetryManager = telemetry;

        this.subscriptionStatusAnnounced = false;
        this.messageQueue = new LinkedBlockingQueue<>();
        this.subscriptionState = new StateManager(pubnubInstance);

        this.listenerManager = new ListenerManager(this.pubnub);
        this.reconnectionManager = new ReconnectionManager(this.pubnub);
        this.delayedReconnectionManager = new DelayedReconnectionManager(this.pubnub);
        this.retrofitManager = retrofitManagerInstance;
        this.duplicationManager = new DuplicationManager(this.pubnub.getConfiguration());

        this.timetoken = 0L;
        this.storedTimetoken = null;

        final ReconnectionCallback reconnectionCallback = new ReconnectionCallback() {
            @Override
            public void onReconnection() {
                reconnect();
                PNStatus pnStatus = PNStatus.builder()
                        .error(false)
                        .affectedChannels(subscriptionState.prepareTargetChannelList(true))
                        .affectedChannelGroups(subscriptionState.prepareTargetChannelGroupList(true))
                        .category(PNStatusCategory.PNReconnectedCategory)
                        .build();

                subscriptionStatusAnnounced = true;
                listenerManager.announce(pnStatus);
            }

            @Override
            public void onMaxReconnectionExhaustion() {
                PNStatus pnStatus = PNStatus.builder()
                        .error(false)
                        .category(PNStatusCategory.PNReconnectionAttemptsExhaustedCategory)
                        .affectedChannels(subscriptionState.prepareTargetChannelList(true))
                        .affectedChannelGroups(subscriptionState.prepareTargetChannelGroupList(true))
                        .build();
                listenerManager.announce(pnStatus);

                disconnect();

            }
        };

        this.delayedReconnectionManager.setReconnectionListener(reconnectionCallback);
        this.reconnectionManager.setReconnectionListener(reconnectionCallback);

        if (this.pubnub.getConfiguration().isStartSubscriberThread()) {
            consumerThread = new Thread(new SubscribeMessageWorker(
                    this.pubnub, listenerManager, messageQueue, duplicationManager));
            consumerThread.setName("Subscription Manager Consumer Thread");
            consumerThread.start();
        }
    }

    public void addListener(SubscribeCallback listener) {
        listenerManager.addListener(listener);
    }

    public void removeListener(SubscribeCallback listener) {
        listenerManager.removeListener(listener);
    }


    public synchronized void reconnect() {
        connected = true;
        this.startSubscribeLoop();
        this.registerHeartbeatTimer();
    }

    public synchronized void disconnect() {
        connected = false;
        cancelDelayedLoopIterationForTemporaryUnavailableChannels();
        subscriptionState.resetTemporaryUnavailableChannelsAndGroups();
        delayedReconnectionManager.stop();
        stopHeartbeatTimer();
        stopSubscribeLoop();
    }


    @Deprecated
    public synchronized void stop() {
        this.disconnect();
        consumerThread.interrupt();
    }

    public synchronized void destroy(boolean forceDestroy) {
        this.disconnect();
        if (forceDestroy && consumerThread != null) {
            consumerThread.interrupt();
        }
    }

    public synchronized void adaptStateBuilder(StateOperation stateOperation) {
        this.subscriptionState.adaptStateBuilder(stateOperation);
        reconnect();
    }

    public synchronized void adaptSubscribeBuilder(SubscribeOperation subscribeOperation) {
        this.subscriptionState.adaptSubscribeBuilder(subscribeOperation);
        // the channel mix changed, on the successful subscribe, there is going to be announcement.
        this.subscriptionStatusAnnounced = false;

        this.duplicationManager.clearHistory();

        if (subscribeOperation.getTimetoken() != null) {
            this.timetoken = subscribeOperation.getTimetoken();
        }

        // if the timetoken is not at starting position, reset the timetoken to get a connected event
        // and store the old timetoken to be reused later during subscribe.
        if (timetoken != 0L) {
            storedTimetoken = timetoken;
        }
        timetoken = 0L;

        reconnect();
    }

    public void adaptPresenceBuilder(PresenceOperation presenceOperation) {
        this.subscriptionState.adaptPresenceBuilder(presenceOperation);

        if (!this.pubnub.getConfiguration().isSuppressLeaveEvents() && !presenceOperation.isConnected()) {
            new Leave(pubnub, this.telemetryManager, this.retrofitManager)
                    .channels(presenceOperation.getChannels()).channelGroups(presenceOperation.getChannelGroups())
                    .async(new PNCallback<Boolean>() {
                        @Override
                        public void onResponse(Boolean result, @NotNull PNStatus status) {
                            listenerManager.announce(status);
                        }
                    });
        }

        registerHeartbeatTimer();
    }

    public synchronized void adaptUnsubscribeBuilder(UnsubscribeOperation unsubscribeOperation) {
        this.subscriptionState.adaptUnsubscribeBuilder(unsubscribeOperation);

        this.subscriptionStatusAnnounced = false;

        if (!this.pubnub.getConfiguration().isSuppressLeaveEvents()) {
            new Leave(pubnub, this.telemetryManager, this.retrofitManager)
                    .channels(unsubscribeOperation.getChannels()).channelGroups(unsubscribeOperation.getChannelGroups())
                    .async(new PNCallback<Boolean>() {
                        @Override
                        public void onResponse(Boolean result, @NotNull PNStatus status) {
                            //In case we get PNAccessDeniedCategory while sending Leave event we do not announce it.
                            //Client did initiate it explicitly,
                            if (status.isError() && status.getCategory() == PNStatusCategory.PNAccessDeniedCategory) {
                                return;
                            }
                            listenerManager.announce(status);
                        }
                    });
        }


        // if we unsubscribed from all the channels, reset the timetoken back to zero and remove the region.
        if (this.subscriptionState.isEmpty()) {
            region = null;
            storedTimetoken = null;
            timetoken = 0L;
        } else {
            storedTimetoken = timetoken;
            timetoken = 0L;
        }

        reconnect();
    }

    private void registerHeartbeatTimer() {
        // make sure only one timer is running at a time.
        stopHeartbeatTimer();

        // if the interval is 0 or less, do not start the timer
        if (pubnub.getConfiguration().getHeartbeatInterval() <= 0) {
            return;
        }

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                performHeartbeatLoop();
            }
        }, 0, pubnub.getConfiguration().getHeartbeatInterval() * HEARTBEAT_INTERVAL_MULTIPLIER);

    }

    private void stopHeartbeatTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private synchronized void cancelDelayedLoopIterationForTemporaryUnavailableChannels() {
        if (temporaryUnavailableChannelsDelayer != null) {
            temporaryUnavailableChannelsDelayer.cancel();
            temporaryUnavailableChannelsDelayer = null;
        }
    }

    private synchronized void scheduleDelayedLoopIterationForTemporaryUnavailableChannels() {
        cancelDelayedLoopIterationForTemporaryUnavailableChannels();

        temporaryUnavailableChannelsDelayer = new Timer();
        temporaryUnavailableChannelsDelayer.schedule(new TimerTask() {
            @Override
            public void run() {
                startSubscribeLoop();
            }
        }, TWO_SECONDS);
    }

    private void startSubscribeLoop() {
        if (!connected) {
            return;
        }

        // this function can be called from different points, make sure any old loop is closed
        stopSubscribeLoop();

        Map<String, Object> stateStorage = this.subscriptionState.createStatePayload();

        synchronized (subscriptionState) {
            if (!subscriptionState.hasAnythingToSubscribe()) {
                return;
            }

            if (subscriptionState.subscribedToOnlyTemporaryUnavailable()) {
                scheduleDelayedLoopIterationForTemporaryUnavailableChannels();
                return;
            }

            final List<String> effectiveChannels = subscriptionState.effectiveChannels();
            final List<String> effectiveChannelGroups = subscriptionState.effectiveChannelGroups();

            subscribeCall = new Subscribe(pubnub, this.retrofitManager)
                    .channels(effectiveChannels).channelGroups(effectiveChannelGroups)
                    .timetoken(timetoken).region(region)
                    .filterExpression(pubnub.getConfiguration().getFilterExpression())
                    .state(stateStorage);
        }

        subscribeCall.async(new PNCallback<SubscribeEnvelope>() {
            @Override
            public void onResponse(SubscribeEnvelope result, @NotNull PNStatus status) {
                if (status.isError()) {
                    final PNStatusCategory category = status.getCategory();

                    switch (category) {
                        case PNTimeoutCategory:
                            startSubscribeLoop();
                            break;
                        case PNUnexpectedDisconnectCategory:
                            // stop all announcements and ask the reconnection manager to start polling for connection
                            // restoration..
                            disconnect();
                            listenerManager.announce(status);
                            reconnectionManager.startPolling();
                            break;
                        case PNBadRequestCategory:
                        case PNURITooLongCategory:
                            disconnect();
                            listenerManager.announce(status);
                            break;
                        case PNAccessDeniedCategory:
                            listenerManager.announce(status);
                            final List<String> affectedChannels = status.getAffectedChannels();
                            final List<String> affectedChannelGroups = status.getAffectedChannelGroups();
                            if (affectedChannels != null || affectedChannelGroups != null) {
                                if (affectedChannels != null) {
                                    for (final String channelToMoveToTemporaryUnavailable : affectedChannels) {
                                        subscriptionState.addTemporaryUnavailableChannel(channelToMoveToTemporaryUnavailable);
                                    }
                                }
                                if (affectedChannelGroups != null) {
                                    for (final String channelGroupToMoveToTemporaryUnavailable : affectedChannelGroups) {
                                        subscriptionState.addTemporaryUnavailableChannelGroup(channelGroupToMoveToTemporaryUnavailable);
                                    }
                                }
                                startSubscribeLoop();
                            }

                            break;
                        default:
                            listenerManager.announce(status);
                            delayedReconnectionManager.scheduleDelayedReconnection();
                            break;
                    }
                } else {
                    if (status.getCategory() == PNStatusCategory.PNAcknowledgmentCategory) {
                        synchronized (subscriptionState) {
                            final List<String> affectedChannels = status.getAffectedChannels();
                            final List<String> affectedChannelGroups = status.getAffectedChannelGroups();
                            if (affectedChannels != null) {
                                for (final String affectedChannel : affectedChannels) {
                                    subscriptionState.removeTemporaryUnavailableChannel(affectedChannel);
                                }
                            }
                            if (affectedChannelGroups != null) {
                                for (final String affectedChannelGroup : affectedChannelGroups) {
                                    subscriptionState.removeTemporaryUnavailableChannelGroup(affectedChannelGroup);
                                }
                            }
                        }
                    }

                    if (!subscriptionStatusAnnounced) {
                        PNStatus pnStatus = createPublicStatus(status)
                                .category(PNStatusCategory.PNConnectedCategory)
                                .error(false)
                                .build();
                        subscriptionStatusAnnounced = true;
                        listenerManager.announce(pnStatus);
                    }

                    Integer requestMessageCountThreshold = pubnub.getConfiguration().getRequestMessageCountThreshold();
                    if (requestMessageCountThreshold != null && requestMessageCountThreshold <= result.getMessages().size()) {
                        PNStatus pnStatus = createPublicStatus(status)
                                .category(PNStatusCategory.PNRequestMessageCountExceededCategory)
                                .error(false)
                                .build();

                        listenerManager.announce(pnStatus);
                    }

                    if (result.getMessages().size() != 0) {
                        messageQueue.addAll(result.getMessages());
                    }

                    if (storedTimetoken != null) {
                        timetoken = storedTimetoken;
                        storedTimetoken = null;
                    } else {
                        timetoken = result.getMetadata().getTimetoken();
                    }

                    region = result.getMetadata().getRegion();
                    startSubscribeLoop();
                }
            }
        });

    }

    private void stopSubscribeLoop() {
        cancelDelayedLoopIterationForTemporaryUnavailableChannels();
        if (subscribeCall != null) {
            subscribeCall.silentCancel();
            subscribeCall = null;
        }
    }

    private void performHeartbeatLoop() {
        if (heartbeatCall != null) {
            heartbeatCall.silentCancel();
            heartbeatCall = null;
        }

        List<String> presenceChannels = this.subscriptionState.prepareTargetChannelList(false);
        List<String> presenceChannelGroups = this.subscriptionState.prepareTargetChannelGroupList(false);

        List<String> heartbeatChannels = this.subscriptionState.prepareTargetHeartbeatChannelList(false);
        List<String> heartbeatChannelGroups = this.subscriptionState.prepareTargetHeartbeatChannelGroupList(false);


        // do not start the loop if we do not have any presence channels or channel groups enabled.
        if (presenceChannels.isEmpty()
                && presenceChannelGroups.isEmpty()
                && heartbeatChannels.isEmpty()
                && heartbeatChannelGroups.isEmpty()) {
            return;
        }

        List<String> channels = new ArrayList<>();
        channels.addAll(presenceChannels);
        channels.addAll(heartbeatChannels);

        List<String> groups = new ArrayList<>();
        groups.addAll(presenceChannelGroups);
        groups.addAll(heartbeatChannelGroups);

        heartbeatCall = new Heartbeat(pubnub, this.telemetryManager, this.retrofitManager)
                .channels(channels)
                .channelGroups(groups);

        heartbeatCall.async(new PNCallback<Boolean>() {
            @Override
            public void onResponse(Boolean result, @NotNull PNStatus status) {
                PNHeartbeatNotificationOptions heartbeatVerbosity =
                        pubnub.getConfiguration().getHeartbeatNotificationOptions();

                if (status.isError()) {
                    if (heartbeatVerbosity == PNHeartbeatNotificationOptions.ALL
                            || heartbeatVerbosity == PNHeartbeatNotificationOptions.FAILURES) {
                        listenerManager.announce(status);
                    }

                    // stop the heartbeating logic since an error happened.
                    stopHeartbeatTimer();

                } else {
                    if (heartbeatVerbosity == PNHeartbeatNotificationOptions.ALL) {
                        listenerManager.announce(status);
                    }
                }
            }
        });

    }

    public synchronized List<String> getSubscribedChannels() {
        return subscriptionState.prepareTargetChannelList(false);
    }

    public synchronized List<String> getSubscribedChannelGroups() {
        return subscriptionState.prepareTargetChannelGroupList(false);
    }

    public synchronized void unsubscribeAll() {
        adaptUnsubscribeBuilder(UnsubscribeOperation.builder()
                .channelGroups(subscriptionState.prepareTargetChannelGroupList(false))
                .channels(subscriptionState.prepareTargetChannelList(false))
                .build());
    }

    private PNStatus.PNStatusBuilder createPublicStatus(PNStatus privateStatus) {
        return PNStatus.builder()
                .statusCode(privateStatus.getStatusCode())
                .authKey(privateStatus.getAuthKey())
                .operation(privateStatus.getOperation())
                .affectedChannels(privateStatus.getAffectedChannels())
                .affectedChannelGroups(privateStatus.getAffectedChannelGroups())
                .clientRequest(privateStatus.getClientRequest())
                .origin(privateStatus.getOrigin())
                .tlsEnabled(privateStatus.isTlsEnabled());
    }

}
