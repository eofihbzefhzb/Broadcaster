package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionResponse;
import com.rtm516.mcxboxbroadcast.core.models.session.member.SessionMember;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import org.java_websocket.util.NamedThreadFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

public class SessionManager extends SessionManagerCore {
    private final ScheduledExecutorService scheduledThreadPool;
    private final Map<String, SubSessionManager> subSessionManagers;

    private CoreConfig.FriendSyncConfig friendSyncConfig;
    private boolean queryFriendsOnStartup = true;
    private Runnable restartCallback;
    private java.util.function.IntFunction<String> shardNetworkIdResolver = shard -> "";

    private Map<String, String> nonces;

    public SessionManager(StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        super(storageManager, notificationManager, logger.prefixed("Primary Session"));
        this.scheduledThreadPool = Executors.newScheduledThreadPool(5, new NamedThreadFactory("MCXboxBroadcast Thread"));
        this.subSessionManagers = new HashMap<>();
        this.nonces = new HashMap<>();
    }

    @Override
    public ScheduledExecutorService scheduledThread() {
        return scheduledThreadPool;
    }

    @Override
    public String getSessionId() {
        return sessionInfo.getSessionId();
    }

    public ExpandedSessionInfo sessionInfo() {
        return sessionInfo;
    }

    public void shardNetworkIdResolver(java.util.function.IntFunction<String> resolver) {
        this.shardNetworkIdResolver = resolver != null ? resolver : (shard -> "");
    }

    public String resolveShardNetworkId(int shardNumber) {
        try {
            String resolved = shardNetworkIdResolver.apply(shardNumber);
            return resolved != null ? resolved : "";
        } catch (Exception e) {
            logger.error("Failed to resolve NetherNet shard network id for shard " + shardNumber, e);
            return "";
        }
    }

    // --- CORRECTION: Rafraîchir aussi les sous-sessions avant d'attendre Geyser ---
    @Override
    public void ensureAuthenticated() {
        super.ensureAuthenticated();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                List<String> subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
                for (int i = 0; i < subSessions.size(); i++) {
                    String subSession = subSessions.get(i);
                    int shardNumber = i + 2;
                    SubSessionManager subManager = new SubSessionManager(subSession, shardNumber, this, storageManager().subSession(subSession), notificationManager(), logger);
                    subManager.ensureAuthenticated();
                }
            }
        } catch (IOException e) {
            // Fichier non trouvé, pas de sous-sessions
        } catch (Exception e) {
            logger.error("Failed to pre-authenticate sub-sessions", e);
        }
    }
    // --------------------------------------------------------------------------------

    public boolean init(SessionInfo sessionInfo, CoreConfig.FriendSyncConfig friendSyncConfig) throws SessionCreationException, SessionUpdateException {
        this.sessionInfo = new ExpandedSessionInfo("", "", sessionInfo);
        this.queryFriendsOnStartup = friendSyncConfig.autoFollow()
            || friendSyncConfig.autoUnfollow()
            || friendSyncConfig.initialInvite()
            || friendSyncConfig.expiry().enabled();

        super.init();

        if (!this.initialized) {
            return this.initialized;
        }

        this.friendSyncConfig = friendSyncConfig;
        friendManager().init(this.friendSyncConfig);

        List<String> subSessions = new ArrayList<>();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
            }
        } catch (IOException ignored) { }

        List<String> finalSubSessions = subSessions;
        scheduledThreadPool.execute(() -> {
            for (int i = 0; i < finalSubSessions.size(); i++) {
                String subSession = finalSubSessions.get(i);
                int shardNumber = i + 2;
                try {
                    SubSessionManager subSessionManager = new SubSessionManager(subSession, shardNumber, this, storageManager().subSession(subSession), notificationManager(), logger);
                    subSessionManager.init();
                    subSessionManager.friendManager().init(this.friendSyncConfig);
                    subSessionManagers.put(subSession, subSessionManager);
                } catch (SessionCreationException | SessionUpdateException e) {
                    logger.error("Failed to create sub-session " + subSession, e);
                }
            }
        });

        return this.initialized;
    }

    @Override
    protected boolean handleFriendship() {
        return false;
    }

    @Override
    protected boolean shouldQueryFriendsOnStartup() {
        return queryFriendsOnStartup;
    }

    public void updateSession(SessionInfo sessionInfo) throws SessionUpdateException {
        this.sessionInfo.updateSessionInfo(sessionInfo);
        updateSession();
    }

    @Override
    public void updateNonces() throws SessionUpdateException {
        HttpRequest createSessionRequest = HttpRequest.newBuilder()
            .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
            .header("Content-Type", "application/json")
            .header("Authorization", getTokenHeader())
            .header("x-xbl-contract-version", "107")
            .GET()
            .build();

        try {
            HttpResponse<String> createSessionResponse = httpClient.send(createSessionRequest, HttpResponse.BodyHandlers.ofString());
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(createSessionResponse.body(), CreateSessionResponse.class);

            if (sessionResponse == null) {
                throw new SessionUpdateException("Failed to get session for nonces, joining will not work: sessionResponse is null");
            }

            boolean hasChanges = false;
            Set<String> activeXuids = new HashSet<>();
            for (Map.Entry<String, SessionMember> entry : sessionResponse.members().entrySet()) {
                activeXuids.add(entry.getValue().constants().get("system").xuid());
            }

            activeXuids.remove(sessionInfo.getXuid());
            hasChanges = nonces.keySet().retainAll(activeXuids);

            for (String xuid : activeXuids) {
                if (!nonces.containsKey(xuid)) {
                    byte[] bytes = new byte[8];
                    ThreadLocalRandom.current().nextBytes(bytes);
                    StringBuilder hex = new StringBuilder(16);
                    for (byte b : bytes) {
                        hex.append(String.format("%02x", b));
                    }

                    nonces.put(xuid, hex.toString());
                    logger.debug("Generated nonce for XUID " + xuid + ": " + hex);
                    hasChanges = true;
                }
            }

            if (hasChanges) {
                updateSession();
            }
        } catch (IOException | InterruptedException e) {
            throw new SessionUpdateException("Failed to get session for nonces, joining will not work: " + e.getMessage());
        }
    }

    @Override
    protected void updateSession() throws SessionUpdateException {
        checkConnection();
        String responseBody = super.updateSessionInternal(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId()), new CreateSessionRequest(this.sessionInfo, nonces));
        try {
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);
            int players = sessionResponse.members().size();
            if (players >= 28) {
                logger.info("Restarting session due to " + players + "/30 players");
                restart();
            }
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }

    public void shutdown() {
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            subSessionManager.shutdown();
        }
        super.shutdown();
        scheduledThreadPool.shutdownNow();
    }

    public void dumpSession() {
        try {
            storageManager().lastSessionResponse(lastSessionResponse);
        } catch (IOException e) {
            logger.error("Error dumping last session: " + e.getMessage());
        }

        HttpRequest createSessionRequest = HttpRequest.newBuilder()
                .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
                .header("Content-Type", "application/json")
                .header("Authorization", getTokenHeader())
                .header("x-xbl-contract-version", "107")
                .GET()
                .build();

        try {
            HttpResponse<String> createSessionResponse = httpClient.send(createSessionRequest, HttpResponse.BodyHandlers.ofString());
            storageManager().currentSessionResponse(createSessionResponse.body());
        } catch (IOException | InterruptedException e) {
            logger.error("Error dumping current session: " + e.getMessage());
        }
    }

    public void addSubSession(String id) {
        if (subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session already exists with that ID");
            return;
        }

        int shardNumber = subSessionManagers.values().stream()
            .mapToInt(SubSessionManager::shardNumber)
            .max()
            .orElse(1) + 1;
        try {
            SubSessionManager subSessionManager = new SubSessionManager(id, shardNumber, this, storageManager().subSession(id), notificationManager(), logger);
            subSessionManager.init();
            subSessionManager.friendManager().init(friendSyncConfig);
            subSessionManagers.put(id, subSessionManager);
        } catch (SessionCreationException | SessionUpdateException e) {
            coreLogger.error("Failed to create sub-session", e);
            return;
        }

        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
        }
    }

    public void removeSubSession(String id) {
        if (!subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session does not exist with that ID");
            return;
        }

        subSessionManagers.get(id).shutdown();
        subSessionManagers.remove(id);

        try {
            storageManager().subSession(id).cleanup();
        } catch (IOException e) {
            coreLogger.error("Failed to delete sub-session cache file", e);
        }

        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
        }

        coreLogger.info("Removed sub-session with ID " + id);
    }

    public void listSessions() {
        List<String> messages = new ArrayList<>();
        coreLogger.info("Loading status of sessions...");

        messages.add("Primary Session:");
        messages.add(" - Gamertag: " + getGamertag());
        messages.add("   Shard: 1");
        messages.add("   Following: " + socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);

        if (!subSessionManagers.isEmpty()) {
            messages.add("Sub-sessions: (" + subSessionManagers.size() + ")");
            for (Map.Entry<String, SubSessionManager> subSession : subSessionManagers.entrySet()) {
                messages.add(" - ID: " + subSession.getKey());
                messages.add("   Gamertag: " + subSession.getValue().getGamertag());
                messages.add("   Shard: " + subSession.getValue().shardNumber());
                messages.add("   Following: " + subSession.getValue().socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);
            }
        } else {
            messages.add("No sub-sessions");
        }

        for (String message : messages) {
            coreLogger.info(message);
        }
    }

    public void restartCallback(Runnable restart) {
        this.restartCallback = restart;
    }

    public void restart() {
        if (restartCallback != null) {
            restartCallback.run();
        } else {
            logger.error("No restart callback set");
        }
    }
}