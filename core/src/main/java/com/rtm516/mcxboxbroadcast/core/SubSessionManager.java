package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionResponse;
import com.rtm516.mcxboxbroadcast.core.models.session.member.SessionMember;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import dev.kastle.webrtc.PortAllocatorConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Session manager for a sub-session.
 * <p>
 * Each sub-session is its own independently published Xbox session with its own NetherNet shard
 * (resolved via the parent's shard network id resolver), relaying into the same backend server as
 * the primary session. This spreads join/NetherNet capacity across multiple Xbox accounts/shards
 * while sub-sessions still friend the primary account to help scale past the 2000-friend cap.
 */
public class SubSessionManager extends SessionManagerCore {
    private final SessionManager parent;
    private final int shardNumber;
    private final Map<String, String> nonces = new HashMap<>();

    /**
     * Create a new session manager for a sub-session
     *
     * @param id The id of the sub-session
     * @param shardNumber The NetherNet portal-bridge shard number this sub-session uses (1 is
     *                     reserved for the primary session, so this is always 2 or greater).
     *                     Also used as the "(<n>)" suffix on this sub-session's advertised MOTD.
     * @param parent The parent session manager
     * @param storageManager The storage manager to use for storing data
     * @param notificationManager The notification manager to use for sending messages
     * @param logger The logger to use for outputting messages
     */
    public SubSessionManager(String id, int shardNumber, SessionManager parent, StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        super(storageManager, notificationManager, logger.prefixed("Sub-Session " + id));
        this.parent = parent;
        this.shardNumber = shardNumber;
    }

    @Override
    public ScheduledExecutorService scheduledThread() {
        return parent.scheduledThread();
    }

    @Override
    protected PortAllocatorConfig netherNetPortAllocatorConfig() {
        return parent.netherNetPortAllocatorConfig();
    }

    @Override
    public String getSessionId() {
        return sessionInfo.getSessionId();
    }

    /**
     * The NetherNet portal-bridge shard number this sub-session uses, and the "(&lt;n&gt;)"
     * suffix on its advertised secondary MOTD.
     *
     * @return The shard number (2 or greater)
     */
    public int shardNumber() {
        return shardNumber;
    }

    /**
     * Initialize this sub-session as its own independently published Xbox session, using its
     * own NetherNet shard and relaying into the same backend server as the parent.
     */
    @Override
    public void init() throws SessionCreationException, SessionUpdateException {
        this.sessionInfo = new ExpandedSessionInfo("", "", buildShardSessionInfo());

        super.init();
    }

    /**
     * Refresh this sub-session's advertised state (players, max players, etc.) from the parent's
     * current session info, keeping this shard's own host name suffix and NetherNet id.
     *
     * @throws SessionUpdateException If the update fails
     */
    public void syncFromParent() throws SessionUpdateException {
        this.sessionInfo.updateSessionInfo(buildShardSessionInfo());
        updateSession();
    }

    /**
     * Build the SessionInfo this sub-session should advertise - a copy of the parent's current
     * session, but with its own shard's NetherNet id (when externally hosted) and "(&lt;n&gt;)"
     * appended to the host name so it's distinguishable from the primary and other sub-sessions
     * in the Xbox session list.
     */
    private SessionInfo buildShardSessionInfo() {
        ExpandedSessionInfo parentInfo = parent.sessionInfo();

        SessionInfo shardInfo = new SessionInfo();
        shardInfo.setWorldName(parentInfo.getWorldName());
        shardInfo.setPlayers(parentInfo.getPlayers());
        shardInfo.setMaxPlayers(parentInfo.getMaxPlayers());
        shardInfo.setIp(parentInfo.getIp());
        shardInfo.setPort(parentInfo.getPort());
        shardInfo.setJoinability(parentInfo.getJoinability());
        shardInfo.setWorldType(parentInfo.getWorldType());
        shardInfo.setEditorWorld(parentInfo.isEditorWorld());
        shardInfo.setHardcore(parentInfo.isHardcore());
        shardInfo.setProxyBridgeEnabled(parentInfo.isProxyBridgeEnabled());
        shardInfo.setRelayTargetAddress(parentInfo.getRelayTargetAddress());
        shardInfo.setRelayTargetPort(parentInfo.getRelayTargetPort());

        String baseHostName = parentInfo.getHostName();
        if (baseHostName == null || baseHostName.isBlank()) {
            baseHostName = "MCXboxBroadcast";
        }
        String suffix = " (" + shardNumber + ")";
        shardInfo.setHostName(baseHostName.endsWith(suffix) ? baseHostName : baseHostName + suffix);

        if (parentInfo.isExternalNetherNetHosted()) {
            shardInfo.setExternalNetherNetHosted(true);
            String shardNetworkId = parent.resolveShardNetworkId(shardNumber);
            shardInfo.setExternalNetherNetId(!shardNetworkId.isBlank() ? shardNetworkId : parentInfo.getExternalNetherNetId());
        }

        return shardInfo;
    }

    @Override
    protected boolean handleFriendship() {
        // TODO Some form of force flag just in case the master friends list is full

        // Add the main account
        boolean subAdd = friendManager().addIfRequired(parent.getXuid(), parent.getGamertag());

        // Get the main account to add us
        boolean mainAdd = parent.friendManager().addIfRequired(getXuid(), getGamertag());

        return subAdd || mainAdd;
    }

    /**
     * Generate join nonces for this sub-session's own session members - mirrors the primary
     * session's nonce handling since this sub-session now hosts its own real, joinable session.
     */
    @Override
    public void updateNonces() throws SessionUpdateException {
        HttpRequest getSessionRequest = HttpRequest.newBuilder()
            .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
            .header("Content-Type", "application/json")
            .header("Authorization", getTokenHeader())
            .header("x-xbl-contract-version", "107")
            .GET()
            .build();

        try {
            HttpResponse<String> getSessionResponse = httpClient.send(getSessionRequest, HttpResponse.BodyHandlers.ofString());
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(getSessionResponse.body(), CreateSessionResponse.class);

            if (sessionResponse == null) {
                throw new SessionUpdateException("Failed to get session for nonces, joining will not work: sessionResponse is null");
            }

            boolean hasChanges;

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
            // Just confirm the response parses; unlike the primary session we don't restart on
            // high player counts here - the primary session already handles that for the shared backend
            Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }
}
