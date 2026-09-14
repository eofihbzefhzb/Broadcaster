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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class StandaloneMain {
    private static final long MAX_EXTERNAL_STATUS_AGE_SECONDS = 180;

    private static CoreConfig config;
    private static StandaloneLoggerImpl logger;
    private static SessionInfo sessionInfo;
    private static NotificationManager notificationManager;
    private static String discoveredExternalNetworkId;

    // Remembers the last network id that was logged, so the discovery loop reports a given id once
    private static String lastLoggedNetworkId = null;

    public static SessionManager sessionManager;

    /**
     * Where to look for Geyser's status file: the path set in config.yml first, then the places it
     * sits when this process runs from a folder next to Velocity's.
     */
    private static Iterable<String> getStatusFileCandidates() {
        List<String> candidates = new ArrayList<>();

        String configPath = config.netherNet().statusFilePath();
        if (!configPath.isBlank()) {
            candidates.add(configPath);
        }

        candidates.addAll(Arrays.asList(
            "./portal-session-status.json",
            "../portal-session-status.json",
            "../plugins/Geyser-Velocity/portal-session-status.json",
            "../../plugins/Geyser-Velocity/portal-session-status.json",
            "../Velocity/plugins/Geyser-Velocity/portal-session-status.json"
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

        // TODO Support multiple notification types
        notificationManager = new SlackNotificationManager(logger, config.notifications());

        sessionManager = new SessionManager(new FileStorageManager("./cache", "./screenshot.jpg"), notificationManager, logger);
        sessionManager.setNetherNetPortRange(config.session().icePortRange().min(), config.session().icePortRange().max());
        logger.info("Refreshing Xbox authentication before NetherNet discovery...");
        sessionManager.ensureAuthenticated();
        logger.info("Xbox authentication is ready for NetherNet signaling.");

        discoveredExternalNetworkId = discoverExternalNetworkId();

        sessionInfo = new SessionInfo(config.session().sessionInfo());
        applyExternalNetherNet(sessionInfo);

        // Wait for Geyser off the main thread, which goes on to start the console below.
        if (effectiveExternalNetworkId().isBlank()) {
            CompletableFuture.runAsync(() -> {
                discoveredExternalNetworkId = waitForExternalNetworkId();

                if (discoveredExternalNetworkId.isBlank()) {
                    logger.error("No Geyser NetherNet network ID is available yet.");
                    logger.error("Start Velocity with the Geyser fork and portal-bridge enabled so it writes portal-session-status.json, then start MCXboxBroadcast again.");
                    sessionManager.shutdown();
                    System.exit(1);
                } else {
                    applyExternalNetherNet(sessionInfo);
                    continueInitialization();
                }
            });
        } else {
            continueInitialization();
        }

        logger.start();
    }

    private static void continueInitialization() {
        logger.info("Xbox Live session publishing is enabled for Geyser's NetherNet ID " + effectiveExternalNetworkId());

        // Fallback to the gamertag if the host name is empty
        if (sessionInfo.getHostName().isEmpty()) {
            sessionInfo.setHostName(sessionManager.getGamertag());
        }

        PingUtil.setWebPingEnabled(config.session().webQueryFallback());

        // Sync the session info from the server if needed
        updateSessionInfo(sessionInfo);

        try {
            createSession();
        } catch (Exception e) {
            logger.error("Failed to create session", e);
        }
    }

    public static void restart() {
        try {
            sessionManager.shutdown();

            // Create a new session manager, but reuse the notification manager as config hasn't been reloaded
            sessionManager = new SessionManager(new FileStorageManager("./cache", "./screenshot.jpg"), notificationManager, logger);
            sessionManager.setNetherNetPortRange(config.session().icePortRange().min(), config.session().icePortRange().max());

            createSession();
        } catch (SessionCreationException | SessionUpdateException e) {
            logger.error("Failed to restart session", e);
        }
    }

    private static void createSession() throws SessionCreationException, SessionUpdateException {
        sessionManager.restartCallback(StandaloneMain::restart);
        boolean initialized = sessionManager.init(sessionInfo, config.friendSync());

        // If the session failed to initialize, don't start the update loop
        // We assume an error has already been logged
        if (!initialized) {
            return;
        }

        sessionManager.scheduledThread().scheduleWithFixedDelay(() -> {
            if (!updateSessionInfo(sessionInfo)) {
                return;
            }

            try {
                // Update the session
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
        if (config.netherNet().externalNetworkId().isBlank() && !hasReadyExternalNetworkStatus()) {

            logger.warn("Geyser NetherNet status is not ready; keeping the Xbox session unchanged until Geyser is ready.");
            return false;
        }
        if (config.session().syncFromGeyser() && isExternalNetherNetEnabled() && updateSessionInfoFromStatusFile(sessionInfo)) {
            return true;
        }

        if (config.session().queryServer() && config.session().syncFromGeyser()) {
            try {
                InetSocketAddress addressToPing = new InetSocketAddress(sessionInfo.getIp(), sessionInfo.getPort());
                BedrockPong pong = PingUtil.ping(addressToPing, 1500, TimeUnit.MILLISECONDS).get();

                // Update the session information
                sessionInfo.setHostName(pong.subMotd());
                sessionInfo.setWorldName(pong.motd());
                sessionInfo.setPlayers(pong.playerCount());
                sessionInfo.setMaxPlayers(pong.maximumPlayerCount());

                // Fallback to the gamertag if the host name is empty
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

                    // Fallback to the gamertag if the host name is empty
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

                // Fallback to the gamertag if the host name is empty
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

    /**
     * Tells the session which NetherNet id Geyser hosts the ingress on, once one is known. Re-applied
     * whenever that id is discovered or changes.
     */
    private static void applyExternalNetherNet(SessionInfo sessionInfo) {
        sessionInfo.setExternalNetherNetHosted(isExternalNetherNetEnabled());
        sessionInfo.setExternalNetherNetId(effectiveExternalNetworkId());
    }

    private static boolean isExternalNetherNetEnabled() {
        return !effectiveExternalNetworkId().isBlank();
    }

    private static String effectiveExternalNetworkId() {
        if (discoveredExternalNetworkId != null && !discoveredExternalNetworkId.isBlank()) {
            return discoveredExternalNetworkId;
        }
        return config.netherNet().externalNetworkId().trim();
    }

    private static String discoverExternalNetworkId() {
        if (!config.netherNet().externalNetworkId().isBlank()) {
            return config.netherNet().externalNetworkId().trim();
        }

        String fileDiscoveredId = discoverStatusNetworkId();
        if (!fileDiscoveredId.isBlank()) {
            return fileDiscoveredId;
        }

        // Deliberately silent: this runs inside the startup discovery loop, so warning here
        // repeated the same line every pass until Geyser published its id.
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
        if (!config.netherNet().externalNetworkId().isBlank()) {
            return;
        }

        String found = discoverStatusNetworkId();
        if (found.isBlank() || found.equals(discoveredExternalNetworkId)) {
            return;
        }

        discoveredExternalNetworkId = found;
        logger.info("Updated external NetherNet ID from local Geyser: " + found);
        if (sessionInfo != null) {
            applyExternalNetherNet(sessionInfo);
        }
    }

    /**
     * Reads the NetherNet id Geyser publishes in portal-session-status.json, or an empty string.
     */
    private static String discoverStatusNetworkId() {
        for (String candidate : getStatusFileCandidates()) {
            try {
                Path path = Path.of(candidate).normalize();
                if (!Files.isRegularFile(path)) {
                    continue;
                }

                JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!isReadyStatus(root)) {
                    logger.debug("Geyser NetherNet status is not ready in " + path);
                    continue;
                }
                if (root.has("netherNetId") && !root.get("netherNetId").isJsonNull()) {
                    String networkId = root.get("netherNetId").getAsString().replaceAll("[^0-9]", "");
                    if (!networkId.isBlank()) {
                        // Logged only when the id changes; this runs on every discovery and update pass
                        if (!networkId.equals(lastLoggedNetworkId)) {
                            logger.info("Discovered local Geyser NetherNet ID " + networkId + " from " + path);
                            lastLoggedNetworkId = networkId;
                        }
                        return networkId;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return "";
    }

    private static boolean hasReadyExternalNetworkStatus() {
        return !discoverStatusNetworkId().isBlank();
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
}
