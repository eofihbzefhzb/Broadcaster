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
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * The session the primary rotated away from, still hosted for the players left in it; null when
     * there is none. See maintainRetiredSession().
     * <p>
     * Only one is ever kept. The one just left is where nearly everyone still playing is, and every
     * hosted session costs a read on each membership change plus one per update cycle - the same Xbox
     * limits that answered the member-cap restart storm with a 429 are the ones this would run into
     * if it held on to every session since startup.
     */
    private final AtomicReference<RetiredSession> retiredSession = new AtomicReference<>();

    /**
     * Held by maintainRetiredSession() for its network calls, so two runs never interleave.
     * <p>
     * rotateSession() never takes it: it can run on the RTA websocket thread that issues nonces for
     * the current session, and waiting there behind a slow read of the old session would stop
     * anyone joining the new one. It swaps retiredSession atomically instead.
     */
    private final Object retiredSessionLock = new Object();

    /** Set while a maintenance run is queued; see scheduleRetiredMaintenance(). */
    private final AtomicBoolean retiredMaintenanceQueued = new AtomicBoolean();

    /** Whether the queued run must PUT even without a nonce change. */
    private final AtomicBoolean retiredMaintenanceForced = new AtomicBoolean();

    /** Bounds every request made for the previous session, since they run under a lock. */
    private static final Duration RETIRED_SESSION_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** A backstop, not the normal way out: a retired session is dropped as soon as it has no players. */
    private static final Duration RETIRED_SESSION_MAX_AGE = Duration.ofHours(12);

    private static final class RetiredSession {
        final String id;
        /** Xuid -> nonce, carried over from when this was the current session, and kept issuing. */
        final Map<String, String> nonces;
        /** Xuid -> gamertag as of the last read, or null before the first; for arrival logs. */
        Map<String, String> members;
        final Instant retiredAt = Instant.now();

        RetiredSession(String id, Map<String, String> nonces) {
            this.id = id;
            this.nonces = nonces;
        }
    }

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

            // Unconditionally here, not only on change: if checkConnection() has replaced the
            // websocket since the last cycle, the membership in the previous session still names
            // the old connection, and until it is re-PUT that session's events never arrive.
            scheduleRetiredMaintenance(true);
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
        // The event behind this call can come from the previous session as well as this one, and
        // nothing in it says which, so give the previous one a look too. On the pool, so a friend
        // joining the current session never waits behind a read of the old one.
        scheduleRetiredMaintenance(false);

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
                    String nonce = newNonce();
                    nonces.put(xuid, nonce);

                    logger.debug("Generated nonce for XUID " + xuid + ": " + nonce);

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

        Map<String, String> current = memberNames(sessionResponse);

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
     * the previous one) and publishes the new session.
     * <p>
     * The sub-accounts then have to be pointed at it. Each one advertises the server through an
     * activity handle, and that handle names a session id: it is created once, in createSession(),
     * with whatever id the primary held at that moment. Their membership follows the primary on its
     * own, because SubSessionManager#updateSession reads parent.getSessionId() on every refresh, but
     * the handle does not - left alone, five of the six doors would keep leading to the old session,
     * which is the full one, until each account's websocket happened to drop. See
     * SubSessionManager#republish().
     * <p>
     * The previous session is not abandoned. The players still in it show it to their own friends,
     * and those friends can only get in if the host keeps writing nonces there, so the primary goes
     * on hosting it until its last player has left - see maintainRetiredSession(). The sub-accounts
     * leave it as they move, which frees their seats for exactly those friends.
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
        Map<String, String> previousNonces = this.nonces;
        try {
            this.sessionInfo.setSessionId(UUID.randomUUID().toString());
            // The new session issues its own nonces. The ones already handed out belong to the
            // previous session, which keeps them below.
            this.nonces = new HashMap<>();

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

            RetiredSession dropped = retiredSession.getAndSet(new RetiredSession(previous, previousNonces));

            logger.info("Published a new Xbox session; pointing " + subSessionManagers.size()
                + " sub-account(s) at it and still hosting the previous one for the players in it");

            // On the pool, one task per account, as refreshSubSessions() does: each republish waits
            // on its own RTA connection id, and this method can be running on the primary's
            // websocket thread, which must not sit through several of those in series.
            for (SubSessionManager subSessionManager : subSessionManagers.values()) {
                scheduledThreadPool.execute(() -> subSessionManager.republish(previous));
            }

            // createSession() gave this account a new websocket, so its membership in the previous
            // session names a connection that no longer exists. Re-register it now rather than a
            // whole update cycle later, before Xbox marks the host inactive there.
            scheduleRetiredMaintenance(true);

            if (dropped != null) {
                // Under the lock, on the pool: a maintenance run still working on the dropped session
                // could otherwise PUT this account back into it just after it left, and nothing would
                // ever look at that session again to take it back out.
                scheduledThreadPool.execute(() -> {
                    synchronized (retiredSessionLock) {
                        leaveRetiredSession(dropped.id, "a newer one took its place");
                    }
                });
            }
        } catch (Exception e) {
            this.sessionInfo.setSessionId(previous);
            this.nonces = previousNonces;
            logger.error("Failed to rotate the full session, keeping the current one", e);
        }
    }

    /**
     * Keeps the previous session joinable for as long as players are still in it.
     * <p>
     * Everyone playing in a session shows it on their friends' lists, and joining it needs a nonce
     * that the host writes into it - updateNonces() only ever does that for the current session. Left
     * alone, the session the primary rotated away from would go on showing for all the friends of
     * everyone still playing in it, and each friend who picked it would sit on the loading screen
     * waiting for a nonce that never comes. This does for the previous session what updateNonces()
     * does for the current one, and the PUT that carries the nonces also keeps the primary a member
     * with a live connection, which is what keeps that session's change events arriving at all.
     * <p>
     * Hosting ends once no player is left - own accounts do not count - or at
     * RETIRED_SESSION_MAX_AGE, and the primary then leaves the session. Nothing here throws: this runs
     * beside the current session's update and nonce paths and must never be able to break them.
     *
     * @param always true to PUT even when no nonce changed, which re-registers the membership against
     *               whichever websocket is live now
     */
    private void maintainRetiredSession(boolean always) {
        synchronized (retiredSessionLock) {
            RetiredSession retired = retiredSession.get();
            if (retired == null) {
                return;
            }

            try {
                if (Duration.between(retired.retiredAt, Instant.now()).compareTo(RETIRED_SESSION_MAX_AGE) > 0) {
                    closeRetiredSession(retired, "it has been hosted for " + RETIRED_SESSION_MAX_AGE.toHours() + " hours");
                    return;
                }

                HttpResponse<String> read = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(Constants.CREATE_SESSION.formatted(retired.id)))
                    .timeout(RETIRED_SESSION_REQUEST_TIMEOUT)
                    .header("Authorization", getTokenHeader())
                    .header("x-xbl-contract-version", "107")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());

                if (read.statusCode() == 404 || read.statusCode() == 403) {
                    // Gone, or this account may no longer read it: either way there is nothing to host.
                    if (retiredSession.compareAndSet(retired, null)) {
                        logger.info("Stopped hosting the previous Xbox session: Xbox no longer has it");
                    }
                    return;
                }
                if (read.statusCode() != 200) {
                    logger.debug("Could not read the previous Xbox session (" + read.statusCode() + "), trying again next time");
                    return;
                }

                CreateSessionResponse session = Constants.GSON.fromJson(read.body(), CreateSessionResponse.class);
                if (session == null || session.members() == null) {
                    return;
                }
                Map<String, String> members = memberNames(session);

                // The same announcements as the current session, labelled so the two cannot be confused.
                if (retired.members != null) {
                    for (Map.Entry<String, String> entry : members.entrySet()) {
                        if (!retired.members.containsKey(entry.getKey()) && !isOwnAccount(entry.getKey())) {
                            logger.info(entry.getValue() + " joined the previous Xbox session (" + members.size() + " members)");
                        }
                    }
                    for (Map.Entry<String, String> entry : retired.members.entrySet()) {
                        if (!members.containsKey(entry.getKey()) && !isOwnAccount(entry.getKey())) {
                            logger.info(entry.getValue() + " is no longer in the previous Xbox session (" + members.size() + " members)");
                        }
                    }
                }
                retired.members = members;

                if (members.keySet().stream().allMatch(this::isOwnAccount)) {
                    closeRetiredSession(retired, "its last player has left");
                    return;
                }

                // The rule updateNonces() applies to the current session.
                Set<String> activeXuids = new HashSet<>(members.keySet());
                activeXuids.remove(sessionInfo.getXuid());
                boolean changed = retired.nonces.keySet().retainAll(activeXuids);
                for (String xuid : activeXuids) {
                    if (!retired.nonces.containsKey(xuid)) {
                        retired.nonces.put(xuid, newNonce());
                        changed = true;
                    }
                }

                // A rotation may have replaced this session while it was being read. The run queued
                // behind this one picks up the replacement, and the dropped session is being left.
                if (retiredSession.get() != retired) {
                    return;
                }

                if (changed || always) {
                    // Sent directly instead of through updateSessionInternal(), which saves every
                    // response as currentSessionResponse.json: the snapshot would then describe a
                    // session the server no longer advertises.
                    HttpResponse<String> write = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create(Constants.CREATE_SESSION.formatted(retired.id)))
                        .timeout(RETIRED_SESSION_REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Authorization", getTokenHeader())
                        .header("x-xbl-contract-version", "107")
                        .PUT(HttpRequest.BodyPublishers.ofString(Constants.GSON.toJson(new CreateSessionRequest(sessionInfo, retired.nonces))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                    if (write.statusCode() != 200 && write.statusCode() != 201) {
                        logger.warn("Could not issue nonces in the previous Xbox session (" + write.statusCode() + "), so friends joining it may not get in: " + write.body());
                    }
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                logger.debug("Could not maintain the previous Xbox session: " + e.getMessage());
            }
        }
    }

    /**
     * Stops hosting the given retired session and leaves it - only if it is still the one held. If a
     * rotation has already replaced it, that rotation is leaving it and announcing why.
     */
    private void closeRetiredSession(RetiredSession retired, String reason) {
        if (retiredSession.compareAndSet(retired, null)) {
            leaveRetiredSession(retired.id, reason);
        }
    }

    /**
     * Queues a maintainRetiredSession() run on the pool, coalescing requests that arrive while one is
     * already waiting.
     * <p>
     * It is asked for on every membership change as well as every update cycle, and each run holds
     * the lock through its network calls. Dispatching one task per request would let a burst of
     * arrivals park most of the pool's five threads on that lock, starving the sub-account refreshes
     * and republishes that share it. Coalescing keeps it to at most one run working and one waiting.
     *
     * @param always whether the run must PUT even without a nonce change; sticky until a run takes it
     */
    private void scheduleRetiredMaintenance(boolean always) {
        if (retiredSession.get() == null) {
            return;
        }
        if (always) {
            retiredMaintenanceForced.set(true);
        }
        if (retiredMaintenanceQueued.compareAndSet(false, true)) {
            scheduledThreadPool.execute(() -> {
                // Cleared before running, so a request arriving mid-run queues exactly one more.
                retiredMaintenanceQueued.set(false);
                maintainRetiredSession(retiredMaintenanceForced.getAndSet(false));
            });
        }
    }

    private void leaveRetiredSession(String sessionId, String reason) {
        logger.info("Stopped hosting the previous Xbox session: " + reason);
        try {
            leaveSession(sessionId);
        } catch (SessionUpdateException e) {
            logger.debug("Could not leave the previous Xbox session: " + e.getMessage());
        }
    }

    /** A fresh join nonce: eight random bytes as sixteen hex digits, the format updateNonces() issues. */
    private static String newNonce() {
        byte[] bytes = new byte[8];
        ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(16);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Xuid -> gamertag for every member of a session document, falling back to the xuid when the
     * document leaves the gamertag out.
     */
    private static Map<String, String> memberNames(CreateSessionResponse sessionResponse) {
        Map<String, String> names = new HashMap<>();
        for (SessionMember member : sessionResponse.members().values()) {
            if (member == null || member.constants() == null) {
                continue;
            }
            var system = member.constants().get("system");
            if (system == null || system.xuid() == null) {
                continue;
            }
            String name = member.gamertag() == null || member.gamertag().isBlank()
                ? system.xuid()
                : member.gamertag();
            names.put(system.xuid(), name);
        }
        return names;
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