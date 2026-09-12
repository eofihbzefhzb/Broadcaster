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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple manager to authenticate and create sessions on Xbox
 */
public class SessionManager extends SessionManagerCore {
    private final ScheduledExecutorService scheduledThreadPool;
    private final Map<String, SubSessionManager> subSessionManagers;

    /** Long enough for a rotation to finish and the new session's member list to settle. */
    private static final long RESTART_COOLDOWN_MS = 60_000L;

    /** When the last session rotation was claimed; see claimRestartSlot(). */
    private final AtomicLong lastRestartAttempt = new AtomicLong(0L);

    private CoreConfig.FriendSyncConfig friendSyncConfig;
    private Runnable restartCallback;

    private Map<String, String> nonces;
    /**
     * Xuid -> gamertag of the session's members as of the last update, or null before the first one.
     * Read and written from both the scheduled session update and the RTA websocket thread, so every
     * access goes through the synchronized logMemberChanges().
     */
    private Map<String, String> knownMembers;

    /**
     * Create an instance of SessionManager
     *
     * @param storageManager The storage manager to use for storing data
     * @param notificationManager The notification manager to use for sending messages
     * @param logger The logger to use for outputting messages
     */
    public SessionManager(StorageManager storageManager, NotificationManager notificationManager, Logger logger) {
        super(storageManager, notificationManager, logger.prefixed("Primary Session"));
        this.scheduledThreadPool = Executors.newScheduledThreadPool(5, new NamedThreadFactory("MCXboxBroadcast Thread"));
        // Concurrent: the periodic update loop iterates this map from the scheduled pool while
        // addSubSession/removeSubSession can mutate it from the console thread.
        this.subSessionManagers = new ConcurrentHashMap<>();
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

    /**
     * Get the current session information
     *
     * @return The current session information
     */
    public ExpandedSessionInfo sessionInfo() {
        return sessionInfo;
    }

    /**
     * Ensure the primary session AND all configured sub-sessions are authenticated
     * and their cache files are fully refreshed BEFORE waiting for Geyser's NetherNet ID.
     */
    @Override
    public void ensureAuthenticated() {
        super.ensureAuthenticated();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                List<String> subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
                for (int i = 0; i < subSessions.size(); i++) {
                    String subSession = subSessions.get(i);
                    logger.debug("Refreshing Xbox authentication for sub-session " + subSession + "...");
                    SubSessionManager subManager = new SubSessionManager(subSession, this, storageManager().subSession(subSession), notificationManager(), logger);
                    subManager.ensureAuthenticated();
                    logger.debug("Sub-session " + subSession + " authentication is ready.");
                }
            }
        } catch (IOException e) {
            // No sub-sessions are configured
        } catch (Exception e) {
            logger.error("Failed to pre-authenticate sub-sessions", e);
        }
    }

    /**
     * Initialize the session manager with the given session information
     *
     * @param sessionInfo      The session information to use
     * @param friendSyncConfig The friend sync configuration to use
     * @throws SessionCreationException If the session failed to create either because it already exists or some other reason
     * @throws SessionUpdateException   If the session data couldn't be set due to some issue
     */
    public boolean init(SessionInfo sessionInfo, CoreConfig.FriendSyncConfig friendSyncConfig) throws SessionCreationException, SessionUpdateException {
        // Set the internal session information based on the session info
        this.sessionInfo = new ExpandedSessionInfo("", "", sessionInfo);
        reuseStoredSessionId();

        super.init();

        // If we failed to initialize, don't continue with the rest of the setup
        if (!this.initialized) {
            return this.initialized;
        }

        // Set up the auto friend sync
        this.friendSyncConfig = friendSyncConfig;
        friendManager().init(this.friendSyncConfig);

        // Load sub-sessions from cache
        List<String> subSessions = new ArrayList<>();
        try {
            String subSessionsJson = storageManager().subSessions();
            if (!subSessionsJson.isBlank()) {
                subSessions = Arrays.asList(Constants.GSON.fromJson(subSessionsJson, String[].class));
            }
        } catch (IOException ignored) { }

        // Create the sub-sessions in a new thread so we don't block the main thread
        List<String> finalSubSessions = subSessions;
        scheduledThreadPool.execute(() -> {
            // Create the sub-session manager for each sub-session
            for (int i = 0; i < finalSubSessions.size(); i++) {
                String subSession = finalSubSessions.get(i);

                SubSessionManager subSessionManager = new SubSessionManager(subSession, this, storageManager().subSession(subSession), notificationManager(), logger);
                // Register before init(), not after. init() is what joins the MPSD session, so the
                // primary's periodic update can see this account arrive as a member while the map
                // is still missing it - which is exactly how our own bots ended up announced as
                // players. refresh() ignores a manager that has not finished initialising, so
                // publishing it early is safe.
                subSessionManagers.put(subSession, subSessionManager);
                try {
                    subSessionManager.init();
                    subSessionManager.friendManager().init(this.friendSyncConfig);
                } catch (SessionCreationException | SessionUpdateException e) {
                    subSessionManagers.remove(subSession);
                    logger.error("Failed to create sub-session " + subSession, e);
                }
            }
        });

        return this.initialized;
    }

    @Override
    protected boolean handleFriendship() {
        // Don't do anything as we are the main session
        return false;
    }


    /**
     * Update the current session with new information
     *
     * @param sessionInfo The information to update the session with
     * @throws SessionUpdateException If the update failed
     */
    public void updateSession(SessionInfo sessionInfo) throws SessionUpdateException {
        this.sessionInfo.updateSessionInfo(sessionInfo);
        try {
            updateSession();
        } finally {
            // Even if the primary update failed, the sub-accounts still need their membership
            // refreshed - their own websockets may be perfectly healthy.
            refreshSubSessions();
        }
    }

    /**
     * Re-assert every sub-account's membership in the primary session.
     * <p>
     * Only reached from the periodic update above, never from {@link #updateNonces()}: nonce updates
     * are driven by RTA events and fire at an unpredictable rate, which would hammer the People/MPSD
     * endpoints with one request per sub-account each time.
     * <p>
     * Each refresh is dispatched to the scheduled pool instead of running inline. A refresh can block
     * on an HTTP retry chain of up to ~30 seconds, and running several of those in series would delay
     * the primary session's own update loop.
     */
    private void refreshSubSessions() {
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            scheduledThreadPool.execute(subSessionManager::refresh);
        }
    }

    @Override
    public void updateNonces() throws SessionUpdateException {
        // Get session
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

            // Xbox pushes a change event for every membership change and RtaWebsocketClient turns it
            // into this call, so this is the fastest view of the member list we get. Reading it here
            // reports arrivals and departures within seconds; the periodic PUT below would otherwise
            // be the only source and it only runs on the session update interval.
            logMemberChanges(sessionResponse);

            boolean hasChanges = false;

            // Collect active XUIDs from the session
            Set<String> activeXuids = new HashSet<>();
            for (Map.Entry<String, SessionMember> entry : sessionResponse.members().entrySet()) {
                activeXuids.add(entry.getValue().constants().get("system").xuid());
            }

            // Remove our own xuid
            activeXuids.remove(sessionInfo.getXuid());

            // Remove stale nonces
            hasChanges = nonces.keySet().retainAll(activeXuids);

            for (String xuid : activeXuids) {
                if (!nonces.containsKey(xuid)) {
                    // Generate a nonce
                    byte[] bytes = new byte[8];
                    ThreadLocalRandom.current().nextBytes(bytes);
                    StringBuilder hex = new StringBuilder(16);
                    for (byte b : bytes) {
                        hex.append(String.format("%02x", b));
                    }

                    // Put the nonce
                    nonces.put(xuid, hex.toString());

                    logger.debug("Generated nonce for XUID " + xuid + ": " + hex);

                    hasChanges = true;
                }
            }

            // Only update the session properties if something changed
            if (hasChanges) {
                updateSession();
            }
        } catch (IOException | InterruptedException e) {
            throw new SessionUpdateException("Failed to get session for nonces, joining will not work: " + e.getMessage());
        }
    }

    @Override
    protected void updateSession() throws SessionUpdateException {
        // Make sure the websocket connection is still active
        checkConnection();

        String responseBody = super.updateSessionInternal(Constants.CREATE_SESSION.formatted(this.sessionInfo.getSessionId()), new CreateSessionRequest(this.sessionInfo, nonces));
        try {
            CreateSessionResponse sessionResponse = Constants.GSON.fromJson(responseBody, CreateSessionResponse.class);

            logMemberChanges(sessionResponse);

            // Rotate onto a fresh session once we reach 28 of the 30 member cap
            int players = sessionResponse.members().size();
            if (players >= 28 && claimRestartSlot()) {
                logger.info("Rotating session due to " + players + "/30 players");
                rotateSession();
            }
        } catch (JsonParseException e) {
            throw new SessionUpdateException("Failed to parse session response: " + e.getMessage());
        }
    }

    /**
     * Reports players entering and leaving the Xbox session.
     * <p>
     * This is the only join activity this process can see. The gameplay connection is NetherNet
     * straight into Geyser, so nothing about it passes through here - Geyser's own log is where a
     * join is confirmed. What the session document does show is MPSD membership, and that matters
     * on its own: every member's follower list can see the world, so each line here is one more
     * door opening or closing.
     * <p>
     * Both directions are close to live, because this also runs from Xbox's own change events and
     * not only on the session update cycle: measured at roughly five seconds behind the real
     * disconnect. It remains Xbox's view rather than the game's, and a peer that vanishes without
     * telling Xbox is only dropped once Xbox notices, so a slow departure is possible. These lines
     * are a statement about reach - who can currently see the world through a member's friends
     * list - and the proxy log holds the authoritative join and leave timings.
     * <p>
     * Members already present on the first update after a restart - the bot accounts, and anyone
     * mid-game - are adopted silently rather than announced as arrivals.
     */
    private synchronized void logMemberChanges(CreateSessionResponse sessionResponse) {
        if (sessionResponse == null || sessionResponse.members() == null) {
            return;
        }

        Map<String, String> current = new HashMap<>();
        for (SessionMember member : sessionResponse.members().values()) {
            if (member == null || member.constants() == null) {
                continue;
            }
            var system = member.constants().get("system");
            if (system == null || system.xuid() == null) {
                continue;
            }
            // The session document carries the gamertag; fall back to the xuid when it is absent.
            String name = member.gamertag() == null || member.gamertag().isBlank()
                ? system.xuid()
                : member.gamertag();
            current.put(system.xuid(), name);
        }

        if (knownMembers == null) {
            knownMembers = current;
            return;
        }

        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (!knownMembers.containsKey(entry.getKey()) && !isOwnAccount(entry.getKey())) {
                logger.info(entry.getValue() + " joined the Xbox session (" + current.size() + " members)");
            }
        }
        for (Map.Entry<String, String> entry : knownMembers.entrySet()) {
            if (!current.containsKey(entry.getKey()) && !isOwnAccount(entry.getKey())) {
                // Measured at a few seconds behind the real disconnect now that this also runs on
                // Xbox's push events, but it is still Xbox's view rather than the game's: a peer
                // that vanishes without telling Xbox is only dropped when Xbox notices. The proxy
                // log holds the authoritative timing; what this line marks is the moment their
                // follower list stops being a way in.
                logger.info(entry.getValue() + " is no longer in the Xbox session (" + current.size() + " members)");
            }
        }

        knownMembers = current;
    }

    /**
     * Whether this xuid is one of our own publishing accounts.
     * <p>
     * The sub-accounts register one after another over the first minute, so they all land after the
     * first snapshot and were announced as arrivals - six lines of noise per startup that say
     * nothing, and that bury the real players among them. They are still counted in the member
     * total, because each of them genuinely holds a door open.
     */
    private boolean isOwnAccount(String xuid) {
        if (xuid.equals(xuidOrNull(this))) {
            return true;
        }
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            // A null here simply means "not this one": an account that has not authenticated cannot
            // be an MPSD member yet.
            if (xuid.equals(xuidOrNull(subSessionManager))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The account's xuid, or null when its authentication is not available.
     * <p>
     * getXuid() reads a cached profile and dereferences it without checking, so it throws rather
     * than returning null once that cache has been cleared. A restart leaves the previous RTA
     * websocket alive for a moment after shutdown has emptied it, and a member update arriving in
     * exactly that window took the websocket thread down with a NullPointerException. Nothing is
     * lost by treating it as unknown: an account whose authentication is gone is not a member of
     * the session we are comparing against.
     */
    private static String xuidOrNull(SessionManagerCore manager) {
        try {
            return manager.getXuid();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Stop the current session and close the websocket
     */
    public void shutdown() {
        // Shutdown all sub-sessions
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            subSessionManager.shutdown();
        }

        // Shutdown self
        super.shutdown();
        scheduledThreadPool.shutdownNow();
    }

    /**
     * Dump the current and last session responses to json files
     */
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

    /**
     * Create a sub-session for the given ID
     *
     * @param id The ID of the sub-session to create
     */
    public void addSubSession(String id) {
        // Make sure we don't already have that ID
        if (subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session already exists with that ID");
            return;
        }

        // Create the sub-session manager
        SubSessionManager subSessionManager = new SubSessionManager(id, this, storageManager().subSession(id), notificationManager(), logger);
        // Registered before init() for the same reason as above: init() joins the MPSD session.
        subSessionManagers.put(id, subSessionManager);
        try {
            subSessionManager.init();
            subSessionManager.friendManager().init(friendSyncConfig);
        } catch (SessionCreationException | SessionUpdateException e) {
            subSessionManagers.remove(id);
            coreLogger.error("Failed to create sub-session", e);
            return;
        }

        // Update the list of sub-sessions
        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
        }
    }

    /**
     * Remove a sub-session for the given ID
     *
     * @param id The ID of the sub-session to remove
     */
    public void removeSubSession(String id) {
        // Make sure we have that ID
        if (!subSessionManagers.containsKey(id)) {
            coreLogger.error("Sub-session does not exist with that ID");
            return;
        }

        // Remove the sub-session manager
        subSessionManagers.get(id).shutdown();
        subSessionManagers.remove(id);

        // Delete the sub-session cache file
        try {
            storageManager().subSession(id).cleanup();
        } catch (IOException e) {
            coreLogger.error("Failed to delete sub-session cache file", e);
        }

        // Update the list of sub-sessions
        try {
            storageManager().subSessions(Constants.GSON.toJson(subSessionManagers.keySet()));
        } catch (JsonParseException | IOException e) {
            coreLogger.error("Failed to update sub-session list", e);
        }

        coreLogger.info("Removed sub-session with ID " + id);
    }

    /**
     * Publish into the same Xbox session document as the previous run, when one is known.
     * <p>
     * The id is otherwise drawn fresh on every start, which hands Xbox a brand new and empty
     * session. Players already in game keep playing - Geyser owns their connection, not this
     * process - but they are not members of the new session, and it is membership that keeps the
     * server visible to their friends. Restarting therefore silently threw away every player who
     * was advertising the server, and they only came back by disconnecting and rejoining.
     * <p>
     * Only the primary session needs this: the sub-sessions join its session rather than holding
     * one of their own, so its document is where every member lives.
     * <p>
     * Safe when it does not work out. If Xbox has already discarded the old session, the update
     * simply recreates it and the run behaves exactly as a fresh id would have.
     */
    /**
     * Publishes a brand new, empty Xbox session under a fresh id, in place.
     * <p>
     * This replaces the full restart() the member cap used to trigger. A restart tears down this
     * manager, every sub-session and the shared thread pool, then builds all of them again - close
     * to thirty seconds during which not one of the six accounts advertises the server, so nobody
     * can find it. Since only the primary session ever accumulates members, throwing away the five
     * sub-sessions to clear the primary's member list bought nothing and closed every door at once.
     * <p>
     * createSession() is the same call the device-token refresh already makes to republish in
     * place, so this path is well travelled. It swaps the RTA websocket (setupRtaWebsocket closes
     * the previous one) and publishes the new session while the sub-sessions keep running
     * untouched, which is what keeps the server discoverable throughout.
     * <p>
     * The id has to change: reusing it is what lets members survive a process restart, but here it
     * would bring all 28 members straight back, leaving the cap check true and the rotation
     * looping. That loop is what produced 58 restarts inside a minute, a thread pool torn down
     * under its own users, and an Xbox 429 that kept the session dark for over an hour.
     * <p>
     * On failure the previous id is put back, so the session that is still live stays the one this
     * manager talks about, and the cooldown lets the next update try again.
     */
    private void rotateSession() {
        String previous = this.sessionInfo.getSessionId();
        try {
            this.sessionInfo.setSessionId(UUID.randomUUID().toString());

            // Before createSession(), not after: it ends by calling updateSession(), which diffs
            // the member list. Left alone, that diff would compare the new session's empty list
            // against the 28 members of the old one and announce every one of them as having left.
            // Null is the "no baseline yet" state, so the diff adopts whichever list it finds in
            // silence - correct both for the new session and for the old one if this throws.
            synchronized (this) {
                this.knownMembers = null;
            }

            createSession();

            try {
                storageManager().sessionId(this.sessionInfo.getSessionId());
            } catch (IOException e) {
                logger.debug("Could not store the rotated Xbox session id: " + e.getMessage());
            }

            logger.info("Published a new Xbox session; sub-sessions stayed up throughout");
        } catch (Exception e) {
            this.sessionInfo.setSessionId(previous);
            logger.error("Failed to rotate the full session, keeping the current one", e);
        }
    }

    private void reuseStoredSessionId() {
        try {
            String stored = storageManager().sessionId();
            if (stored != null && !stored.isBlank()) {
                this.sessionInfo.setSessionId(stored.trim());
                logger.debug("Reusing the previous Xbox session id " + stored.trim());
            } else {
                storageManager().sessionId(this.sessionInfo.getSessionId());
            }
        } catch (IOException e) {
            logger.debug("Could not reuse the stored Xbox session id: " + e.getMessage());
        }
    }

    /**
     * List all sessions and their information
     */
    public void listSessions() {
        List<String> messages = new ArrayList<>();
        coreLogger.info("Loading status of sessions...");

        messages.add("Primary Session:");
        messages.add(" - Gamertag: " + getGamertag());
        messages.add("   Following: " + socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);

        if (!subSessionManagers.isEmpty()) {
            messages.add("Sub-sessions: (" + subSessionManagers.size() + ")");
            for (Map.Entry<String, SubSessionManager> subSession : subSessionManagers.entrySet()) {
                messages.add(" - ID: " + subSession.getKey());
                messages.add("   Gamertag: " + subSession.getValue().getGamertag());
                messages.add("   Following: " + subSession.getValue().socialSummary().targetFollowingCount() + "/" + Constants.MAX_FRIENDS);
            }
        } else {
            messages.add("No sub-sessions");
        }

        for (String message : messages) {
            coreLogger.info(message);
        }
    }

    /**
     * Set the callback to run when the session manager needs to be restarted
     *
     * @param restart The callback to run
     */
    public void restartCallback(Runnable restart) {
        this.restartCallback = restart;
    }

    /**
     * Restart the session manager
     */
    /**
     * Lets one session rotation through and turns away anything that arrives during the
     * cooldown, returning true only to the caller that wins the slot.
     * <p>
     * The member cap check that calls this sits in updateSession(), which runs from the scheduled
     * update, the RTA websocket thread and the nonce refresh. A restart does not empty the member
     * list instantly, so without this every one of those paths sees the session still over the cap
     * and asks for another restart. On a busy session that produced a storm: several
     * SessionManagers starting at once, one tearing down the scheduled thread pool while another
     * was still submitting to it, and the resulting RejectedExecutionException and "RTA Websocket
     * [null] disconnected before connectionId was received" left the session dead until the process
     * was restarted by hand. Members joining stopped being tracked or published from then on.
     * <p>
     * A timestamp rather than a one-shot flag because a restart can fail, and a session stuck at
     * the cap with no way to retry would be just as broken. Compare-and-set rather than a plain
     * read and write because the callers are concurrent threads.
     */
    private boolean claimRestartSlot() {
        long now = System.currentTimeMillis();
        long last = lastRestartAttempt.get();
        return now - last >= RESTART_COOLDOWN_MS && lastRestartAttempt.compareAndSet(last, now);
    }

    public void restart() {
        if (restartCallback != null) {
            restartCallback.run();
        } else {
            logger.error("No restart callback set");
        }
    }
}