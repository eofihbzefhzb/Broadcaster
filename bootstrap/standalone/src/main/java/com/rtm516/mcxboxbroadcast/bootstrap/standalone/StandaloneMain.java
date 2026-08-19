package com.rtm516.mcxboxbroadcast.bootstrap.standalone;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rtm516.mcxboxbroadcast.core.BuildData;
import com.rtm516.mcxboxbroadcast.core.Constants;
import com.rtm516.mcxboxbroadcast.core.SessionInfo;
import com.rtm516.mcxboxbroadcast.core.SessionManager;
import com.rtm516.mcxboxbroadcast.core.configs.ConfigLoader;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.notifications.SlackNotificationManager;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.ping.PingUtil;
import com.rtm516.mcxboxbroadcast.core.storage.FileStorageManager;
import com.rtm516.mcxboxbroadcast.bootstrap.standalone.bridge.StandaloneBridgeService;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class StandaloneMain {
    private static final long MAX_EXTERNAL_STATUS_AGE_SECONDS = 180;
    private static final String REQUIRED_JOINABILITY = "joinable_by_friends";
    
    private static CoreConfig config;
    private static StandaloneLoggerImpl logger;
    private static SessionInfo sessionInfo;
    private static NotificationManager notificationManager;
    private static StandaloneBridgeService bridgeService;
    private static String discoveredExternalNetworkId;

    public static SessionManager sessionManager;

    /**
     * Dynamically resolves candidate paths for the Geyser status file.
     * Prioritizes user-defined JVM arguments and environment variables before falling back to common relative paths.
     */
    private static Iterable<String> getStatusFileCandidates() {
        List<String> candidates = new ArrayList<>();

        // 1. Check for a JVM argument: java -Dgeyser.status.file="/path/to/file.json" -jar Broadcaster.jar
        String sysProp = System.getProperty("geyser.status.file");
        if (sysProp != null && !sysProp.isBlank()) {
            candidates.add(sysProp);
        }

        // 2. Check for an Environment Variable
        String envVar = System.getenv("GEYSER_STATUS_FILE");
        if (envVar != null && !envVar.isBlank()) {
            candidates.add(envVar);
        }

        // 3. Fallback to common relative and home directory paths
        candidates.addAll(Arrays.asList(
            "./portal-session-status.json",
            "../portal-session-status.json",
            "../plugins/Geyser-Velocity/portal-session-status.json",
            "../../plugins/Geyser-Velocity/portal-session-status.json",
            "../Velocity/plugins/Geyser-Velocity/portal-session-status.json",
            System.getProperty("user.home") + "/mc/plugins/Geyser-Velocity/portal-session-status.json",
            System.getProperty("user.home") + "/mc/server/plugins/Geyser-Velocity/portal-session-status.json"
        ));

        return candidates;
    }

    public static void main(String[] args) throws Exception {
        logger = new StandaloneLoggerImpl(LoggerFactory.getLogger(StandaloneMain.class));

        logger.info("Starting MCXboxBroadcast Standalone " + BuildData.VERSION + " for Bedrock " + Constants.BEDROCK_CODEC.getMinecraftVersion() + " (" + Constants.BEDROCK_CODEC.getProtocolVersion() + ")");

        String configFileName = "config.yml";
        File configFile = new File(configFileName);

        try {
            config = ConfigLoader.loadConfig(configFile, "Standalone");
        } catch (IOException e) {
            logger.error("Failed to load config", e);
            return;
        }

        logger.setDebug(config.debugMode());

        notificationManager = new SlackNotificationManager(logger, config.notifications());
        if (config.enabled()) {
            sessionManager = new SessionManager(new FileStorageManager("./cache", "./screenshot.jpg"), notificationManager, logger);
            sessionManager.setNetherNetPortRange(config.session().icePortRange().min(), config.session().icePortRange().max());
            sessionManager.shardNetworkIdResolver(StandaloneMain::discoverShardNetworkId);
            logger.info("Refreshing Xbox authentication before NetherNet discovery...");
            sessionManager.ensureAuthenticated();
            logger.info("Xbox authentication is ready for NetherNet signaling.");
        }

        discoveredExternalNetworkId = discoverExternalNetworkId();
        if (config.netherNet().externalHosted() && effectiveExternalNetworkId().isBlank()) {
            discoveredExternalNetworkId = waitForExternalNetworkId();
        }
        logMode();

        sessionInfo = new SessionInfo(config.session().sessionInfo());
        applySessionSettings(sessionInfo);

        if (config.netherNet().externalHosted() && effectiveExternalNetworkId().isBlank()) {
            logger.error("Geyser-backed mode is enabled, but no NetherNet network ID is available yet.");
            logger.error("Restart Paper/Geyser once so the updated Geyser fork can start NetherNet ingress and write portal-session-status.json, then start MCXboxBroadcast again.");
            if (sessionManager != null) {
                sessionManager.shutdown();
                sessionManager = null;
            }
            return;
        }

        if (isLocalBridgeEnabled()) {
            bridgeService = new StandaloneBridgeService(config, logger.prefixed("bridge"), () -> sessionInfo);
            try {
                bridgeService.start();
            } catch (IllegalStateException exception) {
                String fallbackNetworkId = discoverExternalNetworkId();
                if (!fallbackNetworkId.isBlank()) {
                    discoveredExternalNetworkId = fallbackNetworkId;
                    applySessionSettings(sessionInfo);
                    logger.warn("UDP " + config.bridge().listenPort() + " is already in use. Switching to external-hosted NetherNet publish mode using network ID " + discoveredExternalNetworkId + ".");
                } else {
                    throw exception;
                }
            }
        }

        if (config.enabled()) {
            if (sessionInfo.getHostName().isEmpty()) {
                sessionInfo.setHostName(sessionManager.getGamertag());
            }

            PingUtil.setWebPingEnabled(config.session().webQueryFallback());

            updateSessionInfo(sessionInfo);

            createSession();
        } else {
            logger.info("Xbox session publishing is disabled in config.yml");
        }

        logger.start();
    }

    public static void restart() {
        if (!config.enabled()) {
            logger.info("Xbox session publishing is disabled in config.yml");
            return;
        }

        try {
            sessionManager.shutdown();

            sessionManager = new SessionManager(new FileStorageManager("./cache", "./screenshot.jpg"), notificationManager, logger);
            sessionManager.setNetherNetPortRange(config.session().icePortRange().min(), config.session().icePortRange().max());
            sessionManager.shardNetworkIdResolver(StandaloneMain::discoverShardNetworkId);

            createSession();
        } catch (SessionCreationException | SessionUpdateException e) {
            logger.error("Failed to restart session", e);
        }
    }

    private static void createSession() throws SessionCreationException, SessionUpdateException {
        sessionManager.restartCallback(StandaloneMain::restart);
        boolean initialized = sessionManager.init(sessionInfo, config.friendSync());

        if (!initialized) {
            return;
        }

        sessionManager.scheduledThread().scheduleWithFixedDelay(() -> {
            if (!updateSessionInfo(sessionInfo)) {
                return;
            }

            try {
                sessionManager.updateSession(sessionInfo);
                if (config.suppressSessionUpdateMessage()) {
                    sessionManager.logger().debug("Updated session!");
                } else {
                    sessionManager.logger().info("Updated session!");
                }
            } catch (SessionUpdateException e) {
                sessionManager.logger().error("Failed to update session", e);
            }
        }, config.session().updateInterval(), config.session().updateInterval(), TimeUnit.SECONDS);
    }

    private static boolean updateSessionInfo(SessionInfo sessionInfo) {
        refreshExternalNetworkId();
        if (config.netherNet().externalHosted()
            && config.netherNet().externalNetworkId().isBlank()
            && !hasReadyExternalNetworkStatus()) {
            sessionManager.markUnhealthy("Geyser NetherNet status is missing, stale, or not ready");
            logger.warn("Geyser NetherNet status is not ready; keeping the Xbox session unchanged until Geyser is ready.");
            return false;
        }
        if (config.session().syncFromGeyser() && isExternalNetherNetEnabled() && updateSessionInfoFromStatusFile(sessionInfo)) {
            return true;
        }

        if (config.session().queryServer() && config.session().syncFromGeyser()) {
            try {
                InetSocketAddress addressToPing = isLocalBridgeEnabled()
                    ? new InetSocketAddress(config.bridge().backendAddress(), config.bridge().backendPort())
                    : new InetSocketAddress(sessionInfo.getIp(), sessionInfo.getPort());
                BedrockPong pong = PingUtil.ping(addressToPing, 1500, TimeUnit.MILLISECONDS).get();

                sessionInfo.setHostName(pong.subMotd());
                sessionInfo.setWorldName(pong.motd());
                sessionInfo.setPlayers(pong.playerCount());
                sessionInfo.setMaxPlayers(pong.maximumPlayerCount());
                applySessionSettings(sessionInfo);

                if (sessionInfo.getHostName().isEmpty()) {
                    sessionInfo.setHostName(sessionManager.getGamertag());
                }
            } catch (InterruptedException | ExecutionException e) {
                if (config.session().configFallback()) {
                    sessionManager.logger().error("Failed to ping server, falling back to config values", e);

                    sessionInfo.setHostName(config.session().sessionInfo().hostName());
                    sessionInfo.setWorldName(config.session().sessionInfo().worldName());
                    sessionInfo.setPlayers(config.session().sessionInfo().players());
                    sessionInfo.setMaxPlayers(config.session().sessionInfo().maxPlayers());
                    applySessionSettings(sessionInfo);

                    if (sessionInfo.getHostName().isEmpty()) {
                        sessionInfo.setHostName(sessionManager.getGamertag());
                    }
                } else {
                    sessionManager.logger().error("Failed to ping server", e);
                }
            }
        }
        return true;
    }

    private static boolean updateSessionInfoFromStatusFile(SessionInfo sessionInfo) {
        for (String candidate : getStatusFileCandidates()) {
            try {
                Path path = Path.of(candidate).normalize();
                if (!Files.isRegularFile(path)) {
                    continue;
                }

                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!isReadyStatus(root)) {
                    logger.warn("Ignoring non-ready Geyser NetherNet status file " + path);
                    continue;
                }
                sessionInfo.setHostName(readStatusString(root, "hostName", config.session().sessionInfo().hostName()));
                sessionInfo.setWorldName(readStatusString(root, "worldName", config.session().sessionInfo().worldName()));
                sessionInfo.setPlayers(readStatusInt(root, "players", config.session().sessionInfo().players()));
                sessionInfo.setMaxPlayers(readStatusInt(root, "maxPlayers", config.session().sessionInfo().maxPlayers()));
                applySessionSettings(sessionInfo);

                if (sessionInfo.getHostName().isEmpty()) {
                    sessionInfo.setHostName(sessionManager.getGamertag());
                }
                return true;
            } catch (Exception exception) {
                logger.debug("Failed to read external session status file " + candidate + ": " + exception.getMessage());
            }
        }

        return false;
    }

    private static String readStatusString(JsonObject root, String key, String fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsString();
    }

    private static int readStatusInt(JsonObject root, String key, int fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsInt();
    }

    private static void applySessionSettings(SessionInfo sessionInfo) {
        sessionInfo.setJoinability(REQUIRED_JOINABILITY);
        sessionInfo.setWorldType(config.xboxSession().worldType());
        sessionInfo.setEditorWorld(config.xboxSession().editorWorld());
        sessionInfo.setHardcore(config.xboxSession().hardcore());
        sessionInfo.setExternalNetherNetHosted(isExternalNetherNetEnabled());
        sessionInfo.setExternalNetherNetId(effectiveExternalNetworkId());
        if (isLocalBridgeEnabled()) {
            sessionInfo.setProxyBridgeEnabled(true);
            sessionInfo.setRelayTargetAddress(config.bridge().backendAddress());
            sessionInfo.setRelayTargetPort(config.bridge().backendPort());
            sessionInfo.setPort(config.bridge().listenPort());
        } else {
            sessionInfo.setProxyBridgeEnabled(false);
            sessionInfo.setRelayTargetAddress(null);
            sessionInfo.setRelayTargetPort(0);
        }

        if (sessionInfo.getHostName().isEmpty()) {
            sessionInfo.setHostName("MCXboxBroadcast");
        }
        if (sessionInfo.getWorldName().isEmpty()) {
            sessionInfo.setWorldName(sessionInfo.getHostName());
        }

        applySubseasonSuffix(sessionInfo);
    }

    private static void applySubseasonSuffix(SessionInfo sessionInfo) {
        int subseason = config.netherNet().subseason();
        if (subseason <= 0) {
            return;
        }

        String suffix = " (" + subseason + ")";
        String hostName = sessionInfo.getHostName();
        if (hostName != null && !hostName.isBlank() && !hostName.endsWith(suffix)) {
            sessionInfo.setHostName(hostName + suffix);
        }
    }

    private static void logMode() {
        boolean bridgeEnabled = isLocalBridgeEnabled();
        boolean publishEnabled = config.enabled();
        boolean externalNetherNet = isExternalNetherNetEnabled();
        boolean waitingForExternalNetherNet = config.netherNet().externalHosted() && effectiveExternalNetworkId().isBlank();

        if (waitingForExternalNetherNet) {
            logger.info("Mode: PUBLISH + EXTERNAL NETHERNET (WAITING)");
            logger.info("Geyser-backed mode is selected, but the NetherNet network ID has not been discovered yet.");
            return;
        }

        if (bridgeEnabled && publishEnabled) {
            logger.info("Mode: BRIDGE + PUBLISH");
            logger.info("Bedrock joins terminate at this proxy and relay to " + config.bridge().backendAddress() + ":" + config.bridge().backendPort());
            logger.info("Xbox Live session publishing is enabled for the proxy endpoint " + config.session().sessionInfo().ip() + ":" + config.bridge().listenPort());
            return;
        }

        if (bridgeEnabled) {
            logger.info("Mode: BRIDGE");
            logger.info("Bedrock joins terminate at this proxy and relay to " + config.bridge().backendAddress() + ":" + config.bridge().backendPort());
            return;
        }

        if (publishEnabled && externalNetherNet) {
            logger.info("Mode: PUBLISH + EXTERNAL NETHERNET");
            logger.info("Xbox Live session publishing is enabled for externally hosted NetherNet ID " + effectiveExternalNetworkId());
            return;
        }

        if (publishEnabled) {
            logger.info("Mode: PUBLISH");
            logger.info("Xbox Live session publishing is enabled without a Bedrock relay proxy.");
            return;
        }

        logger.info("Mode: DISABLED");
    }

    private static boolean isLocalBridgeEnabled() {
        return !isExternalNetherNetEnabled();
    }

    private static boolean isExternalNetherNetEnabled() {
        return config.netherNet().externalHosted() && !effectiveExternalNetworkId().isBlank();
    }

    private static String effectiveExternalNetworkId() {
        if (discoveredExternalNetworkId != null && !discoveredExternalNetworkId.isBlank()) {
            return discoveredExternalNetworkId;
        }
        return config.netherNet().externalNetworkId().trim();
    }

    private static String discoverExternalNetworkId() {
        if (!config.netherNet().externalHosted()) {
            return "";
        }
        if (!config.netherNet().externalNetworkId().isBlank()) {
            return config.netherNet().externalNetworkId().trim();
        }

        String fileDiscoveredId = discoverExternalNetworkIdFromFile();
        if (!fileDiscoveredId.isBlank()) {
            return fileDiscoveredId;
        }

        logger.warn("external-hosted is enabled but no NetherNet network ID is configured and none was auto-discovered from the local Geyser ID file.");
        return "";
    }

    private static String waitForExternalNetworkId() {
        int timeoutSeconds = config.netherNet().discoveryTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return "";
        }

        logger.info("Waiting up to " + timeoutSeconds + " seconds for the local Geyser NetherNet ID...");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            String found = discoverExternalNetworkId();
            if (!found.isBlank()) {
                return found;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return "";
            }
        }

        logger.warn("Timed out waiting for the local Geyser NetherNet ID.");
        return "";
    }

    private static void refreshExternalNetworkId() {
        if (!config.netherNet().externalHosted() || !config.netherNet().externalNetworkId().isBlank()) {
            return;
        }

        String found = discoverExternalNetworkIdFromFile();
        if (found.isBlank() || found.equals(discoveredExternalNetworkId)) {
            return;
        }

        discoveredExternalNetworkId = found;
        logger.info("Updated external NetherNet ID from local Geyser: " + found);
        if (sessionInfo != null) {
            applySessionSettings(sessionInfo);
        }
    }

    private static String discoverExternalNetworkIdFromFile() {
        int subseason = config.netherNet().subseason();
        if (subseason > 0) {
            String statusNetworkId = discoverStatusNetworkId(subseason);
            if (!statusNetworkId.isBlank()) {
                return statusNetworkId;
            }
            logger.warn("Subseason " + subseason + " is configured, but no matching ready shard was found in the Geyser status files.");
        } else {
            String statusNetworkId = discoverStatusNetworkId(0);
            if (!statusNetworkId.isBlank()) {
                return statusNetworkId;
            }
        }

        return "";
    }

    private static String discoverStatusNetworkId(int subseason) {
        for (String candidate : getStatusFileCandidates()) {
            try {
                Path path = Path.of(candidate).normalize();
                if (!Files.isRegularFile(path)) {
                    continue;
                }

                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!isReadyStatus(root)) {
                    logger.warn("Geyser NetherNet status is not ready in " + path);
                    continue;
                }
                if (subseason > 0 && root.has("netherNetIds") && root.get("netherNetIds").isJsonArray()) {
                    var ids = root.getAsJsonArray("netherNetIds");
                    int shardIndex = subseason - 1;
                    if (shardIndex >= 0 && shardIndex < ids.size()) {
                        String networkId = ids.get(shardIndex).getAsString().replaceAll("[^0-9]", "");
                        if (!networkId.isBlank()) {
                            logger.info("Discovered NetherNet shard #" + subseason + " network ID " + networkId + " from " + path);
                            return networkId;
                        }
                    }
                }

                if (root.has("netherNetId") && !root.get("netherNetId").isJsonNull()) {
                    String networkId = root.get("netherNetId").getAsString().replaceAll("[^0-9]", "");
                    if (!networkId.isBlank()) {
                        logger.info("Discovered local Geyser NetherNet ID " + networkId + " from " + path);
                        return networkId;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return "";
    }

    private static boolean hasReadyExternalNetworkStatus() {
        return !discoverExternalNetworkIdFromFile().isBlank();
    }

    private static boolean isReadyStatus(JsonObject root) {
        if (!root.has("ready") || !root.get("ready").getAsBoolean()
            || !root.has("generatedAt") || root.get("generatedAt").isJsonNull()) {
            return false;
        }

        try {
            Instant generatedAt = Instant.parse(root.get("generatedAt").getAsString());
            long age = Duration.between(generatedAt, Instant.now()).getSeconds();
            return age >= 0 && age <= MAX_EXTERNAL_STATUS_AGE_SECONDS;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String discoverShardNetworkId(int subseason) {
        String readyStatusNetworkId = discoverStatusNetworkId(subseason);
        if (!readyStatusNetworkId.isBlank()) {
            return readyStatusNetworkId;
        }

        return "";
    }
}