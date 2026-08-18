package com.rtm516.mcxboxbroadcast.core;

import com.github.mizosoft.methanol.Methanol;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.rtm516.mcxboxbroadcast.core.exceptions.AgeVerificationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateHandleRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateHandleResponse;
import com.rtm516.mcxboxbroadcast.core.models.session.SessionRef;
import com.rtm516.mcxboxbroadcast.core.models.session.SocialSummaryResponse;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import com.rtm516.mcxboxbroadcast.core.nethernet.BroadcasterChannelInitializer;
import com.rtm516.mcxboxbroadcast.core.nethernet.bridge.BridgeClientSession;
import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.config.NetherChannelOption;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxRpcSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.Bootstrap;
import dev.kastle.webrtc.PortAllocatorConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Simple manager to authenticate and create sessions on Xbox
 */
public abstract class SessionManagerCore {
    private final AuthManager authManager;
    private final FriendManager friendManager;
    protected final HttpClient httpClient;
    protected final Logger logger;
    protected final Logger coreLogger;
    private final StorageManager storageManager;
    private final NotificationManager notificationManager;
    private final GalleryManager galleryManager;

    protected RtaWebsocketClient rtaWebsocket;
    protected ExpandedSessionInfo sessionInfo;
    protected String lastSessionResponse;

    protected boolean initialized = false;

    private volatile Instant lastSuccessfulSessionUpdate;
    private volatile String lastSessionError = "none";
    private volatile int consecutiveSessionFailures;
    private volatile boolean sessionHealthy = true;

    private Channel netherNetChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private NetherNetXboxRpcSignaling signaling;
    private final Set<Channel> bridgeClientChannels = ConcurrentHashMap.newKeySet();

    private PortAllocatorConfig netherNetPortAllocatorConfig;

    /**
     * Create an instance of SessionManager
     *
     * @param storageManager The storage manager to use for storing data
     * @param notificationManager The notification manager to use for sending messages
     * @param logger The logger to use for outputting messages
     */
    public SessionManagerCore(StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        this.httpClient = Methanol.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .requestTimeout(Duration.ofMillis(Integer.getInteger("http.request.timeout", 5000)))
            .build();

        this.logger = logger;
        this.coreLogger = logger.prefixed("");
        this.storageManager = storageManager;
        this.notificationManager = notificationManager;

        this.authManager = new AuthManager(notificationManager, storageManager, logger);

        this.friendManager = new FriendManager(httpClient, logger, this);
        this.galleryManager = new GalleryManager(httpClient, logger, this);
    }

    /**
     * Get the Xbox LIVE friend manager for this session manager
     *
     * @return The friend manager
     */
    public FriendManager friendManager() {
        return friendManager;
    }

    /**
     * Get the notification manager for this session manager
     *
     * @return The notification manager
     */
    public NotificationManager notificationManager() {
        return notificationManager;
    }

    /**
     * Get the gallery manager for this session manager
     *
     * @return The gallery manager
     */
    public GalleryManager galleryManager() {
        return galleryManager;
    }

    /**
     * Get the scheduled thread pool for this session manager
     *
     * @return The scheduled thread pool
     */
    public abstract ScheduledExecutorService scheduledThread();

    /**
     * Get the session ID for this session manager
     *
     * @return The session ID
     */
    public abstract String getSessionId();

    /**
     * Get the logger for this session manager
     * @return The logger
     */
    public Logger logger() {
        return logger;
    }

    /**
     * Get the Bedrock Auth Manager for the current user.
     * Starts the auto auth process if not logged in.
     *
     * @return The authenticated BedrockAuthManager
     */
    protected BedrockAuthManager getAuthManager() {
        return authManager.getManager();
    }

    /**
     * Ensure the Xbox authentication cache is loaded and its refreshable
     * tokens are current. This is intentionally separate from session
     * creation so external-hosted NetherNet mode can refresh authentication
     * before Geyser attempts to bind its signaling channel.
     */
    public void ensureAuthenticated() {
        getAuthManager();
    }

    /**
     * Initialize the session manager with the given session information
     *
     * @throws SessionCreationException If the session failed to create either because it already exists or some other reason
     * @throws SessionUpdateException   If the session data couldn't be set due to some issue
     */
    public void init() throws SessionCreationException, SessionUpdateException {
        if (this.initialized) {
            throw new SessionCreationException("Already initialized!");
        }

        logger.info("Starting SessionManager...");

        // Make sure we are logged in and get info
        try {
            BedrockAuthManager manager = getAuthManager();
        } catch (AgeVerificationException e) {
            logger.error("Authentication failed due to the account requiring age verification. Please login to xbox.com and complete the age verification process, then try again.");
            logger.error("You can skip it/opt out and continue using the tool, but some features may not work correctly.");
            shutdown();
            return;
        }

        int friendCount = -1;
        if (shouldQueryFriendsOnStartup()) {
            try {
                friendCount = friendManager.get().size();
            } catch (Exception ignored) {
                logger.debug("Unable to query the Xbox friends list during startup; continuing with session publishing.");
            }
        } else {
            logger.info("Friend synchronization is disabled; skipping the startup friends-list request.");
        }

        String friendSummary = shouldQueryFriendsOnStartup()
            ? friendCount + "/" + Constants.MAX_FRIENDS
            : "not queried";
        logger.info("Successfully authenticated as " + getGamertag() + " (" + getXuid() + ") with " + friendSummary + " friends");

        if (handleFriendship()) {
            logger.info("Waiting for friendship to be processed...");
            try {
                Thread.sleep(5000); // TODO Do a real callback not just wait
            } catch (InterruptedException e) {
                logger.error("Failed to wait for friendship to be processed", e);
            }
        }

        logger.info("Creating Xbox LIVE session...");

        // Create the session
        createSession();

        // Update the presence
        updatePresence();

        // Let the user know we are done
        logger.info("Creation of Xbox LIVE session was successful!");

        authManager.setOnDeviceTokenRefreshCallback(() -> {
            try {
                logger.debug("Device token refreshed, recreating session...");
                createSession();
                logger.debug("Session recreated after device token refresh");
            } catch (Exception e) {
                logger.error("Failed to recreate session after device token refresh", e);
            }
        });

        initialized = true;
    }

    /**
     * Handle the friendship of the current user to the main session if needed
     *
     * @return True if the friendship is being handled, false otherwise
     */
    protected abstract boolean handleFriendship();

    /**
     * Whether startup should make a People API request. Session publishing does
     * not require a friends-list request, so safe publisher configurations skip it.
     */
    protected boolean shouldQueryFriendsOnStartup() {
        return true;
    }

    /**
     * Setup a new session and its prerequisites
     *
     * @throws SessionCreationException If the initial creation of the session fails
     * @throws SessionUpdateException If the updating of the session information fails
     */
    private void createSession() throws SessionCreationException, SessionUpdateException {
        // Get the token for authentication
        BedrockAuthManager manager = getAuthManager();
        String token;
        try {
            token = manager.getXboxLiveXstsToken().getUpToDate().getAuthorizationHeader();
        } catch (Exception e) {
             throw new SessionCreationException("Failed to get authorization headers: " + e.getMessage());
        }

        // We only need a websocket for the primary session manager
        if (this.sessionInfo != null) {
            // Update the current session XUID
            this.sessionInfo.setXuid(getXuid());

            // Create the RTA websocket connection
            setupRtaWebsocket();

            try {
                // Wait and get the connection ID from the websocket
                String connectionId = waitForConnectionId();

                // Update the current session connection ID
                this.sessionInfo.setConnectionId(connectionId);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                throw new SessionCreationException("Unable to get connectionId for session: " + e.getMessage());
            }

            if (this.sessionInfo.isExternalNetherNetHosted()) {
                // External-hosted mode still publishes a Minecraft JSON-RPC
                // connection. setupNetherNet() normally initializes this
                // value, but that method is intentionally skipped when the
                // actual NetherNet listener lives in Geyser.
                this.sessionInfo.setPmsgId(manager.getMinecraftSession().getCached().getParsedToken().getPayload().reqString("pmid"));
                if (this.sessionInfo.getNetherNetId() == null || this.sessionInfo.getNetherNetId().signum() < 1) {
                    throw new SessionCreationException("External NetherNet mode has no valid NetherNet ID. Wait for Geyser readiness before publishing.");
                }
                if (this.sessionInfo.getPmsgId() == null || this.sessionInfo.getPmsgId().isBlank()) {
                    throw new SessionCreationException("External NetherNet mode has no PmsgId in the Minecraft session token.");
                }
                logger.info("Using externally hosted NetherNet ID: " + this.sessionInfo.getNetherNetId());
            } else {
                setupNetherNet();

                if (this.netherNetChannel == null || !this.netherNetChannel.isOpen()) {
                    throw new SessionCreationException("Unable to start NetherNet channel");
                }
            }
        }

        // Set the showcase image to the current screenshot
        File imageFile = storageManager.screenshot();
        if (imageFile.exists()) {
            logger.info("Setting showcase image");
            if (galleryManager.setShowcase(imageFile)) {
                logger.info("Successfully set showcase image");
            }
        }

        // Push the session information to the session directory
        updateSession();

        // Create the session handle request
        CreateHandleRequest createHandleContent = new CreateHandleRequest(
            1,
            "activity",
            new SessionRef(
                Constants.SERVICE_CONFIG_ID,
                Constants.TEMPLATE_NAME,
                getSessionId()
            )
        );

        // Make the request to create the session handle
        HttpRequest createHandleRequest;
        try {
            createHandleRequest = HttpRequest.newBuilder()
                .uri(Constants.CREATE_HANDLE)
                .header("Content-Type", "application/json")
                .header("Authorization", token)
                .header("x-xbl-contract-version", "107")
                .POST(HttpRequest.BodyPublishers.ofString(Constants.GSON.toJson(createHandleContent)))
                .build();
        } catch (JsonParseException e) {
            throw new SessionCreationException("Unable to create session handle, error parsing json: " + e.getMessage());
        }

        // Read the handle response
        HttpResponse<String> createHandleResponse;
        try {
            createHandleResponse = httpClient.send(createHandleRequest, HttpResponse.BodyHandlers.ofString());
            if (this.sessionInfo != null) {
                CreateHandleResponse parsedResponse = Constants.GSON.fromJson(createHandleResponse.body(), CreateHandleResponse.class);
                sessionInfo.setHandleId(parsedResponse.id());
            }
        } catch (JsonParseException | IOException | InterruptedException e) {
            throw new SessionCreationException(e.getMessage());
        }

        lastSessionResponse = createHandleResponse.body();

        // Check to make sure the handle was created
        if (createHandleResponse.statusCode() != 200 && createHandleResponse.statusCode() != 201) {
            logger.debug("Failed to create session handle '"  + lastSessionResponse + "' (" + createHandleResponse.statusCode() + ")");
            throw new SessionCreationException("Unable to create session handle, got status " + createHandleResponse.statusCode() + " trying to create: " + createHandleResponse.body());
        }
    }

    /**
     * Update the session information using the stored information
     *
     * @throws SessionUpdateException If the update fails
     */
    protected abstract void updateSession() throws SessionUpdateException;

    /**
     * Update the nonces in the session based on the current players
     *
     * @throws SessionUpdateException If the update fails
     */
    public void updateNonces() throws SessionUpdateException {
        // Nothing by default
    }

    /**
     * The internal method for making the web request to update the session
     *
     * @param url The url to send the PUT request containing the session data
     * @param data The data to update the session with
     * @return The response body from the request
     * @throws SessionUpdateException If the update fails
     */
    protected String updateSessionInternal(String url, Object data) throws SessionUpdateException {
        HttpRequest createSessionRequest;
        try {
            createSessionRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", getTokenHeader())
                .header("x-xbl-contract-version", "107")
                .PUT(HttpRequest.BodyPublishers.ofString(Constants.GSON.toJson(data)))
                .build();
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Unable to update session information, error parsing json: " + e.getMessage());
        }

        String lastFailure = "unknown update failure";
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpResponse<String> createSessionResponse;
            try {
                createSessionResponse = httpClient.send(createSessionRequest, HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                lastFailure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (attempt < 3) {
                    sleepBeforeRetry(attempt, 0);
                    continue;
                }
                markSessionUpdateFailure(lastFailure);
                throw new SessionUpdateException(lastFailure);
            }

            if (createSessionResponse.statusCode() == 200 || createSessionResponse.statusCode() == 201) {
                markSessionUpdateSuccess();
                // Keep a live, sanitized-by-construction copy of the Xbox session
                // response. This is used by the local harness and diagnostics; it
                // contains the API response only and never request headers/tokens.
                try {
                    storageManager.currentSessionResponse(createSessionResponse.body());
                } catch (IOException exception) {
                    logger.warn("Xbox session updated, but the live session snapshot could not be saved: " + exception.getMessage());
                }
                return createSessionResponse.body();
            }

            lastFailure = "Unable to update session information, got status " + createSessionResponse.statusCode();
            if (createSessionResponse.statusCode() == 429 || createSessionResponse.statusCode() >= 500) {
                if (attempt < 3) {
                    int retryAfter = createSessionResponse.headers().firstValue("Retry-After")
                        .map(value -> {
                            try {
                                return Integer.parseInt(value);
                            } catch (NumberFormatException ignored) {
                                return 0;
                            }
                        })
                        .orElse(0);
                    sleepBeforeRetry(attempt, retryAfter);
                    continue;
                }
            }

            logger.warn("Xbox session update failed: " + lastFailure);
            markSessionUpdateFailure(lastFailure);
            throw new SessionUpdateException(lastFailure);
        }

        markSessionUpdateFailure(lastFailure);
        throw new SessionUpdateException(lastFailure);
    }

    private void sleepBeforeRetry(int attempt, int retryAfterSeconds) throws SessionUpdateException {
        long exponentialSeconds = 1L << Math.min(attempt - 1, 3);
        long delaySeconds = Math.min(30L, Math.max(exponentialSeconds, retryAfterSeconds));
        logger.warn("Retrying Xbox session update in " + delaySeconds + " second(s) (attempt " + (attempt + 1) + "/3).");
        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markSessionUpdateFailure("Interrupted while waiting to retry session update");
            throw new SessionUpdateException("Interrupted while waiting to retry session update");
        }
    }

    protected void markSessionUpdateSuccess() {
        lastSuccessfulSessionUpdate = Instant.now();
        lastSessionError = "none";
        consecutiveSessionFailures = 0;
        sessionHealthy = true;
    }

    protected void markSessionUpdateFailure(String message) {
        lastSessionError = message == null || message.isBlank() ? "unknown update failure" : message;
        consecutiveSessionFailures++;
        if (consecutiveSessionFailures >= 3) {
            sessionHealthy = false;
        }
    }

    public boolean isSessionHealthy() {
        return sessionHealthy;
    }

    public void markUnhealthy(String reason) {
        sessionHealthy = false;
        markSessionUpdateFailure(reason);
    }

    public String statusSummary() {
        String id = sessionInfo == null ? "<none>" : sessionInfo.getSessionId();
        String netherNetId = sessionInfo == null || sessionInfo.getNetherNetId() == null
            ? "<none>" : sessionInfo.getNetherNetId().toString();
        boolean pmsgPresent = sessionInfo != null && sessionInfo.getPmsgId() != null && !sessionInfo.getPmsgId().isBlank();
        return "healthy=" + sessionHealthy
            + ", sessionId=" + id
            + ", netherNetId=" + netherNetId
            + ", pmsgIdPresent=" + pmsgPresent
            + ", lastUpdate=" + (lastSuccessfulSessionUpdate == null ? "never" : lastSuccessfulSessionUpdate)
            + ", consecutiveFailures=" + consecutiveSessionFailures
            + ", lastError=" + lastSessionError;
    }

    /**
     * Check the connection to the websocket and if its closed re-open it and re-create the session
     * This should be called before any updates to the session otherwise they might fail
     */
    protected void checkConnection() {
        boolean rtaIsOpen = this.rtaWebsocket != null && this.rtaWebsocket.isOpen();
        boolean rtcIsOpen = this.sessionInfo != null && this.sessionInfo.isExternalNetherNetHosted()
            || this.netherNetChannel != null && this.netherNetChannel.isOpen();

        // Check if the connection is Lost
        if (!rtaIsOpen || !rtcIsOpen) {
            try {
                logger.warn("Connection to websocket lost, re-creating session...");
                logger.debug("WebSocket status: RTA Open: " + rtaIsOpen + " RTC Open: " + rtcIsOpen);

                createSession();
                logger.info("WebSocket session reconnected");
            } catch (SessionCreationException | SessionUpdateException e) {
                logger.error("Session is dead and hit exception trying to re-create it", e);
            }
        }
    }

    /**
     * Use the data in the cache to get the Xbox authentication header
     *
     * @return The formatted XBL3.0 authentication header
     */
    public String getTokenHeader() {
        try {
            return getAuthManager().getXboxLiveXstsToken().getUpToDate().getAuthorizationHeader();
        } catch (Exception e) {
            logger.error("Failed to get auth header", e);
            return "";
        }
    }

    /**
     * Wait for the RTA websocket to receive a connection ID
     *
     * @return The received connection ID
     */
    protected String waitForConnectionId() throws InterruptedException, ExecutionException, TimeoutException {
        return this.rtaWebsocket.getConnectionIdFuture().get(Constants.WEBSOCKET_CONNECTION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Setup the RTA websocket connection
     */
    protected void setupRtaWebsocket() {
        if (rtaWebsocket != null) {
            rtaWebsocket.close();
        }
        rtaWebsocket = new RtaWebsocketClient(this);
        rtaWebsocket.connect();
    }

    /**
     * Restrict the local UDP port range used for WebRTC (NetherNet) ICE candidates.
     * Passing 0 for both min and max keeps the transport default (the OS ephemeral
     * range).
     *
     * @param min The lowest UDP port to use, or 0 for the OS default
     * @param max The highest UDP port to use, or 0 for the OS default
     */
    public void setNetherNetPortRange(int min, int max) {
        if (min <= 0 && max <= 0) {
            this.netherNetPortAllocatorConfig = null;
            return;
        }

        // Setting the channel option replaces the whole PortAllocatorConfig, so the
        // transport's default flags (see DefaultNetherChannelConfig) are mirrored
        // here and only the port range is overridden.
        PortAllocatorConfig config = new PortAllocatorConfig()
            .setDisableTcp(true)
            .setEnableIpv6(true)
            .setEnableIpv6OnWifi(true)
            .setEnableAnyAddressPorts(true)
            .setEnableSharedSocket(true);
        config.minPort = min;
        config.maxPort = max;

        this.netherNetPortAllocatorConfig = config;
    }

    /**
     * @return The WebRTC port allocator config to use for NetherNet, or null to use
     *         the transport default
     */
    protected PortAllocatorConfig netherNetPortAllocatorConfig() {
        return netherNetPortAllocatorConfig;
    }

    protected void setupNetherNet() {
        shutdownNetherNet();

        long netherNetId = this.sessionInfo.getNetherNetId().longValue();

        this.signaling = new NetherNetXboxRpcSignaling(netherNetId, getMCTokenHeader());
        this.sessionInfo.setPmsgId(getAuthManager().getMinecraftSession().getCached().getParsedToken().getPayload().reqString("pmid"));

        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channelFactory(NetherNetChannelFactory.server(new PeerConnectionFactory(), signaling))
                .childHandler(new BroadcasterChannelInitializer(this, logger));

            PortAllocatorConfig portAllocatorConfig = netherNetPortAllocatorConfig();
            if (portAllocatorConfig != null) {
                b.option(NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG, portAllocatorConfig);
            }

            this.netherNetChannel = b.bind(new InetSocketAddress(0)).sync().channel();

            logger.info("NetherNet Broadcaster started on ID: " + netherNetId
                + (portAllocatorConfig != null
                    ? " (ICE ports " + portAllocatorConfig.minPort + "-" + portAllocatorConfig.maxPort + ")"
                    : ""));
        } catch (Exception e) {
            logger.error("Failed to start NetherNet", e);
        }
    }

    public void newBridgeClient(Consumer<BridgeClientSession> sessionConsumer) {
        if (this.workerGroup == null) {
            throw new IllegalStateException("NetherNet worker group is not initialized");
        }

        String host = sessionInfo.getRelayTargetAddress() != null && !sessionInfo.getRelayTargetAddress().isBlank()
            ? sessionInfo.getRelayTargetAddress()
            : sessionInfo.getIp();
        int port = sessionInfo.getRelayTargetPort() > 0
            ? sessionInfo.getRelayTargetPort()
            : sessionInfo.getPort();

        Channel channel = new Bootstrap()
            .group(this.workerGroup)
            .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
            .option(RakChannelOption.RAK_PROTOCOL_VERSION, Constants.BEDROCK_CODEC.getRaknetProtocolVersion())
            .handler(new BedrockChannelInitializer<BridgeClientSession>() {
                @Override
                protected BridgeClientSession createSession0(BedrockPeer peer, int subClientId) {
                    return new BridgeClientSession(peer, subClientId);
                }

                @Override
                protected void initSession(BridgeClientSession session) {
                    sessionConsumer.accept(session);
                }
            })
            .connect(new InetSocketAddress(host, port))
            .awaitUninterruptibly()
            .channel();

        this.bridgeClientChannels.add(channel);
    }

    /**
     * Stop the current session and close the websocket
     */
    public void shutdown() {
        if (rtaWebsocket != null) {
            rtaWebsocket.close();
        }
        
        shutdownNetherNet();
        
        this.initialized = false;
    }

    private void shutdownNetherNet() {
        for (Channel bridgeClientChannel : bridgeClientChannels) {
            bridgeClientChannel.close();
        }
        bridgeClientChannels.clear();
        if (netherNetChannel != null) {
            netherNetChannel.close();
            netherNetChannel = null;
        }
        if (signaling != null) {
            signaling.close();
            signaling = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    /**
     * Update the presence of the current user on Xbox LIVE
     */
    protected void updatePresence() {
        HttpRequest updatePresenceRequest = HttpRequest.newBuilder()
            .uri(URI.create(Constants.USER_PRESENCE.formatted(getXuid())))
            .header("Content-Type", "application/json")
            .header("Authorization", getTokenHeader())
            .header("x-xbl-contract-version", "3")
            .POST(HttpRequest.BodyPublishers.ofString("{\"state\": \"active\"}"))
            .build();

        int heartbeatAfter = 300;
        try {
            HttpResponse<Void> updatePresenceResponse = httpClient.send(updatePresenceRequest, HttpResponse.BodyHandlers.discarding());

            if (updatePresenceResponse.statusCode() != 200) {
                logger.error("Failed to update presence, got status " + updatePresenceResponse.statusCode());
            } else {
                // Read X-Heartbeat-After header to get the next time we should update presence
                try {
                    heartbeatAfter = Integer.parseInt(updatePresenceResponse.headers().firstValue("X-Heartbeat-After").orElse("300"));
                } catch (NumberFormatException e) {
                    logger.debug("Failed to parse heartbeat after header, using default of 300");
                }
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to update presence", e);
        }

        // Schedule the next presence update
        logger.debug("Presence update successful, scheduling presence update in " + heartbeatAfter + " seconds");
        scheduledThread().schedule(this::updatePresence, heartbeatAfter, TimeUnit.SECONDS);
    }

    /**
     * Get the current follower count for the current user
     * @return The current follower count
     */
    public SocialSummaryResponse socialSummary() {
        HttpRequest socialSummaryRequest = HttpRequest.newBuilder()
            .uri(Constants.SOCIAL_SUMMARY)
            .header("Authorization", getTokenHeader())
            .GET()
            .build();

        try {
            return Constants.GSON.fromJson(httpClient.send(socialSummaryRequest, HttpResponse.BodyHandlers.ofString()).body(), SocialSummaryResponse.class);
        } catch (JsonParseException | IOException | InterruptedException e) {
            logger.error("Unable to get current friend count", e);
        }

        return new SocialSummaryResponse(-1, -1, false, false, false, false, "", -1, -1, "");
    }

    /**
     * Get the XUID of the current user
     *
     * @return The XUID of the current user
     */
    public String getXuid() {
        return authManager.getXuid();
    }

    /**
     * Get the Gamertag of the current user
     *
     * @return The Gamertag of the current user
     */
    public String getGamertag() {
        return authManager.getGamertag();
    }

    /**
     * Get the current MC token for the session
     *
     * @return The current MC token
     */
    public String getMCTokenHeader() {
        try {
            return getAuthManager().getMinecraftSession().getUpToDate().getAuthorizationHeader();
        } catch (Exception e) {
            logger.error("Failed to get MC token header", e);
            return null;
        }
    }

    /**
     * Get the storage manager for this session manager
     *
     * @return The storage manager
     */
    public StorageManager storageManager() {
        return storageManager;
    }
}
