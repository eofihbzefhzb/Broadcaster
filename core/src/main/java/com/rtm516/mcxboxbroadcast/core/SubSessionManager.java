package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.JoinSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionResponse;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.storage.StorageManager;
import dev.kastle.webrtc.PortAllocatorConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Session manager for a sub-session.
 * <p>
 * A sub-session does NOT publish a session of its own. It joins the primary session as a member and
 * points its Xbox "activity" handle at the primary session, so Minecraft shows the sub-account as
 * playing inside the primary account's world rather than hosting a separate one. Someone opening the
 * sub-account's Xbox profile therefore sees the primary world and can join through it.
 * <p>
 * Sub-accounts still exist to scale past the 2000-friend cap: each one carries its own friends list
 * and funnels those players into the single session the primary account hosts. Because there is only
 * one session, there is only one NetherNet ingress - the Geyser side should run a single shard.
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

    /**
     * The primary session's id, not this account's own.
     * <p>
     * This is what makes the whole thing work: {@code createSessionHandle()} in the base class builds
     * the "activity" handle from this value, so the sub-account's Xbox presence points at the primary
     * session. Returning a private id here is what previously made each sub-account look like the host
     * of its own separate world.
     */
    @Override
    public String getSessionId() {
        return parent.getSessionId();
    }

    /**
     * The NetherNet portal-bridge shard number this sub-session uses.
     *
     * @return The shard number (2 or greater)
     */
    public int shardNumber() {
        return shardNumber;
    }

    /**
     * Joins the primary session and starts advertising this account's presence in it.
     * <p>
     * The periodic "sync from parent" loop that used to live here is gone: it existed to copy the
     * parent's world name and player counts into this account's own advertisement, and there is no
     * separate advertisement any more. The primary session is the single source of truth, so there
     * is nothing left to mirror.
     */
    @Override
    public void init() throws SessionCreationException, SessionUpdateException {
        this.sessionInfo = new ExpandedSessionInfo("", "", buildShardSessionInfo());
        super.init();
    }

    /**
     * Build the SessionInfo this sub-session should advertise - a copy of the parent's current
     * session, but with its own shard's NetherNet id (when externally hosted).
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
        
        // Garde le même nom propre sans suffixe numérique
        shardInfo.setHostName(baseHostName);

        if (parentInfo.isExternalNetherNetHosted()) {
            shardInfo.setExternalNetherNetHosted(true);
            String shardNetworkId = parent.resolveShardNetworkId(shardNumber);
            shardInfo.setExternalNetherNetId(!shardNetworkId.isBlank() ? shardNetworkId : parentInfo.getExternalNetherNetId());
        }

        return shardInfo;
    }

    @Override
    protected boolean handleFriendship() {
        // Add the main account
        boolean subAdd = friendManager().addIfRequired(parent.getXuid(), parent.getGamertag());

        // Get the main account to add us
        boolean mainAdd = parent.friendManager().addIfRequired(getXuid(), getGamertag());

        return subAdd || mainAdd;
    }

    /**
     * No-op: nonces belong to whoever hosts the session, and that is the primary account now.
     * <p>
     * A member issuing nonces for a session it does not own would fight the host's own bookkeeping.
     */
    @Override
    public void updateNonces() throws SessionUpdateException {
        // Intentionally empty - see javadoc.
    }

    /**
     * Registers this account as a member of the primary session.
     * <p>
     * Sends a {@link JoinSessionRequest} (a {@code members.me} block only) to the PRIMARY session id.
     * It must not send a {@link CreateSessionRequest}: that carries the session properties - host
     * name, world, player counts, the NetherNet connection - and posting those to the primary session
     * id would have each sub-account overwrite the host's own advertisement several times a minute.
     */
    @Override
    protected void updateSession() throws SessionUpdateException {
        checkConnection();

        String responseBody = super.updateSessionInternal(Constants.CREATE_SESSION.formatted(parent.getSessionId()), new JoinSessionRequest(this.sessionInfo));
        try {
            // Just confirm the response parses; unlike the primary session we don't restart on
            // high player counts here - the primary session already handles that for the shared backend
            Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }
}