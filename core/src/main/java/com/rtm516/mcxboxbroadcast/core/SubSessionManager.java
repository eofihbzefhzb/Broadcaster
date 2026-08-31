package com.rtm516.mcxboxbroadcast.core;

import com.google.gson.JsonParseException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.models.session.CreateSessionRequest;
import com.rtm516.mcxboxbroadcast.core.models.session.JoinSessionRequest;
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
    /**
     * Join nonces, only used in separate-session mode where this account hosts its own session.
     * <p>
     * Deliberately a copy of {@link SessionManager}'s logic rather than a shared helper: keeping it
     * here means nothing on the primary account's code path changes while separate sessions are off,
     * which is the whole point of the flag. Fold the two together if the mode ever becomes default.
     */
    private final Map<String, String> nonces = new HashMap<>();

    /**
     * Create a new session manager for a sub-session
     *
     * @param id The id of the sub-session
     * @param parent The parent session manager
     * @param storageManager The storage manager to use for storing data
     * @param notificationManager The notification manager to use for sending messages
     * @param logger The logger to use for outputting messages
     */
    public SubSessionManager(String id, SessionManager parent, StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        super(storageManager, notificationManager, logger.prefixed("Sub-Session " + id));
        this.parent = parent;
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
        if (separateSession() && this.sessionInfo != null) {
            return this.sessionInfo.getSessionId();
        }
        return parent.getSessionId();
    }

    /**
     * Whether this account hosts its own session instead of joining the primary one as a member.
     * <p>
     * Read from the parent rather than from config directly because the flag travels on
     * {@link SessionInfo}, the same way the external-NetherNet flags do.
     */
    private boolean separateSession() {
        ExpandedSessionInfo parentInfo = parent.sessionInfo();
        return parentInfo != null && parentInfo.isSeparateSubSessions();
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
     * Minimal SessionInfo for this account.
     * <p>
     * A member's {@link JoinSessionRequest} carries only {@code members.me}, built from the xuid and
     * connection id, so the world/player/NetherNet fields that used to be mirrored from the parent
     * here are never transmitted any more.
     * <p>
     * Host and world name are still filled in: {@link ExpandedSessionInfo}'s constructor calls
     * {@code getHostName().isEmpty()} and {@code getWorldName().isEmpty()} without a null check, so
     * leaving them unset throws before this object is ever used.
     */
    private SessionInfo buildShardSessionInfo() {
        ExpandedSessionInfo parentInfo = parent.sessionInfo();

        SessionInfo shardInfo = new SessionInfo();
        String hostName = parentInfo != null ? parentInfo.getHostName() : null;
        shardInfo.setHostName(hostName == null || hostName.isBlank() ? "MCXboxBroadcast" : hostName);
        String worldName = parentInfo != null ? parentInfo.getWorldName() : null;
        shardInfo.setWorldName(worldName == null || worldName.isBlank() ? shardInfo.getHostName() : worldName);

        // MUST be carried over from the parent. SessionManagerCore#createSession only skips
        // setupNetherNet() when this flag is set; without it every sub-session spins up its own
        // local NetherNet listener on an id Geyser never bound, and anyone joining through that
        // account hangs on "Searching for game session" because nothing answers on that id.
        if (parentInfo != null && parentInfo.isExternalNetherNetHosted()) {
            shardInfo.setExternalNetherNetHosted(true);
            shardInfo.setExternalNetherNetId(parentInfo.getExternalNetherNetId());
        }

        if (parentInfo != null) {
            // Inert in shared mode - a member's JoinSessionRequest carries none of this - but
            // required in separate-session mode, where this account posts its own properties and
            // would otherwise advertise SessionInfo's hardcoded defaults instead of the config.
            shardInfo.setSeparateSubSessions(parentInfo.isSeparateSubSessions());
            shardInfo.setJoinability(parentInfo.getJoinability());
            shardInfo.setReadRestriction(parentInfo.getReadRestriction());
            shardInfo.setJoinRestriction(parentInfo.getJoinRestriction());
            shardInfo.setWorldType(parentInfo.getWorldType());
            shardInfo.setEditorWorld(parentInfo.isEditorWorld());
            shardInfo.setHardcore(parentInfo.isHardcore());
            shardInfo.setPlayers(parentInfo.getPlayers());
            shardInfo.setMaxPlayers(parentInfo.getMaxPlayers());
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
        if (!separateSession()) {
            // Shared mode: nonces belong to the host, which is the primary account.
            return;
        }

        HttpRequest getSessionRequest = HttpRequest.newBuilder()
            .uri(URI.create(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId())))
            .header("Content-Type", "application/json")
            .header("Authorization", getTokenHeader())
            .header("x-xbl-contract-version", "107")
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(getSessionRequest, HttpResponse.BodyHandlers.ofString());
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(response.body(), CreateSessionResponse.class);
            if (sessionResponse == null) {
                throw new SessionUpdateException("Failed to get session for nonces, joining will not work: sessionResponse is null");
            }

            Set<String> activeXuids = new HashSet<>();
            for (Map.Entry<String, SessionMember> entry : sessionResponse.members().entrySet()) {
                activeXuids.add(entry.getValue().constants().get("system").xuid());
            }
            activeXuids.remove(sessionInfo.getXuid());

            boolean hasChanges = nonces.keySet().retainAll(activeXuids);

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

    /**
     * Re-assert this account's membership in the primary session.
     * <p>
     * Called on the same schedule as the primary session update. Two things depend on it:
     * <ul>
     *   <li>{@code updateSession()} starts with {@link #checkConnection()}, so a dead RTA websocket
     *       is detected here and the session (and the activity handle behind it) is rebuilt. Without
     *       a periodic call nothing ever notices a sub-account's websocket dropping - the presence
     *       heartbeat keeps running on its own schedule, so the account still shows as playing
     *       Minecraft while its world card has disappeared from everyone's friends list.</li>
     *   <li>MPSD drops a member whose {@code members.me} block is not refreshed. Re-PUTting it keeps
     *       the sub-account inside the primary session.</li>
     * </ul>
     * Failures are logged rather than thrown: one sub-account being unable to refresh must not stop
     * the others or the primary session update.
     */
    public void refresh() {
        if (!initialized) {
            return;
        }

        try {
            if (separateSession()) {
                // This account advertises its own session, so the world name and player counts have
                // to be pulled from the primary each cycle. In shared mode there is nothing to
                // mirror: the primary session IS the advertisement.
                this.sessionInfo.updateSessionInfo(buildShardSessionInfo());
            }
            updateSession();
        } catch (SessionUpdateException e) {
            logger.error("Failed to refresh session membership", e);
        }
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

        // Separate-session mode: this account owns the session, so it posts the full properties to
        // its own id. Shared mode: only the members.me block, to the primary's id.
        String responseBody = separateSession()
            ? super.updateSessionInternal(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId()), new CreateSessionRequest(this.sessionInfo, nonces))
            : super.updateSessionInternal(Constants.CREATE_SESSION.formatted(parent.getSessionId()), new JoinSessionRequest(this.sessionInfo));
        try {
            // Just confirm the response parses; unlike the primary session we don't restart on
            // high player counts here - the primary session already handles that for the shared backend
            Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }
}