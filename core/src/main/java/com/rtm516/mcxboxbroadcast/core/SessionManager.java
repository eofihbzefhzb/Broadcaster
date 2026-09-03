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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple manager to authenticate and create sessions on Xbox
 */
public class SessionManager extends SessionManagerCore {
    private final ScheduledExecutorService scheduledThreadPool;
    private final Map<String, SubSessionManager> subSessionManagers;

    private CoreConfig.FriendSyncConfig friendSyncConfig;
    private boolean queryFriendsOnStartup = true;
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
            // Pas de sous-sessions configurées
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
        this.queryFriendsOnStartup = friendSyncConfig.autoFollow()
            || friendSyncConfig.autoUnfollow()
            || friendSyncConfig.initialInvite()
            || friendSyncConfig.expiry().enabled();

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

    @Override
    protected boolean shouldQueryFriendsOnStartup() {
        return queryFriendsOnStartup;
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

            // Restart if we have 28/30 session members
            int players = sessionResponse.members().size();
            if (players >= 28) {
                logger.info("Restarting session due to " + players + "/30 players");
                restart();
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
                recordJoin(entry.getKey(), entry.getValue());
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
        if (xuid.equals(getXuid())) {
            return true;
        }
        for (SubSessionManager subSessionManager : subSessionManagers.values()) {
            // getXuid() is null until that account authenticates. It cannot be an MPSD member before
            // then, so a null here simply means "not this one" rather than an unknown account.
            if (xuid.equals(subSessionManager.getXuid())) {
                return true;
            }
        }
        return false;
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
     * Persist a join, then work out in the background which account brought the player in.
     * <p>
     * The sub-sessions join the primary's Xbox session rather than creating their own, so there is
     * only ever one member list and it cannot say which bot a player found the server through. What
     * it can be derived from is who follows whom: a player only sees the session through an account
     * they follow. That costs one request per account, so it happens off the session update thread
     * and only once per player.
     */
    private void recordJoin(String xuid, String gamertag) {
        try {
            storageManager().joinHistory().record(xuid, gamertag, Instant.now());
        } catch (IOException e) {
            logger.debug("Failed to record the join of " + gamertag + ": " + e.getMessage());
            return;
        }

        scheduledThread().execute(() -> attributeJoin(xuid, gamertag));
    }

    /**
     * Work out which of our accounts the given player follows, and store it against their join.
     */
    private void attributeJoin(String xuid, String gamertag) {
        try {
            if (storageManager().joinHistory().hasSource(xuid)) {
                return;
            }

            List<String> sources = new ArrayList<>();
            if (friendManager().isFollowedBy(xuid)) {
                sources.add(getGamertag());
            }
            for (SubSessionManager subSessionManager : subSessionManagers.values()) {
                if (subSessionManager.friendManager().isFollowedBy(xuid)) {
                    sources.add(subSessionManager.getGamertag());
                }
            }

            // "unknown" rather than nothing, so the player is not probed again on every later join:
            // they reached the session some other way, most likely an invite or a friend of a member.
            storageManager().joinHistory().source(xuid, sources.isEmpty() ? "unknown" : String.join(", ", sources));
        } catch (IOException e) {
            logger.debug("Failed to attribute the join of " + gamertag + ": " + e.getMessage());
        }
    }

    /**
     * Print how many distinct players have joined recently and which account they came through.
     * <p>
     * A player following several of our accounts is counted under all of them together as one
     * combination, rather than being split arbitrarily between them, because there is no way to
     * tell which of the two they actually clicked.
     */
    public void playerStats() {
        List<StorageManager.JoinRecord> records;
        try {
            records = storageManager().joinHistory().all();
        } catch (IOException e) {
            coreLogger.error("Failed to read the join history: " + e.getMessage());
            return;
        }

        if (records.isEmpty()) {
            coreLogger.info("No joins recorded yet.");
            return;
        }

        Instant now = Instant.now();
        List<String> messages = new ArrayList<>();
        messages.add("Distinct players: " + countSince(records, now, 1) + " in 24h, "
            + countSince(records, now, 7) + " in 7d, "
            + countSince(records, now, 30) + " in 30d, "
            + records.size() + " all time");

        Map<String, Integer> bySource = new TreeMap<>();
        for (StorageManager.JoinRecord record : records) {
            String source = record.source() == null || record.source().isBlank()
                ? "not yet attributed"
                : record.source();
            bySource.merge(source, 1, Integer::sum);
        }

        messages.add("Players by the account they follow:");
        bySource.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(entry -> messages.add(" - " + entry.getKey() + ": " + entry.getValue()));

        for (String message : messages) {
            coreLogger.info(message);
        }
    }

    private static long countSince(List<StorageManager.JoinRecord> records, Instant now, int days) {
        Instant cutoff = now.minus(Duration.ofDays(days));
        return records.stream().filter(record -> record.lastJoin().isAfter(cutoff)).count();
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
     * Probe the followers endpoint of every account and print what Xbox answers for each.
     * <p>
     * Auto-follow depends on being able to enumerate followers, and that enumeration can break on
     * one account while every other part of its session keeps working normally. The account goes on
     * gaining followers and publishing its session, so nothing looks wrong until someone asks it
     * directly - which is what this does.
     */
    public void diagnoseFollowers() {
        coreLogger.info("Probing the Xbox followers endpoints, this takes a few seconds per account...");

        List<String> messages = new ArrayList<>();

        messages.add("Primary Session (" + getGamertag() + "):");
        messages.addAll(friendManager().diagnoseFollowers());

        for (Map.Entry<String, SubSessionManager> subSession : subSessionManagers.entrySet()) {
            messages.add("Sub-Session " + subSession.getKey() + " (" + subSession.getValue().getGamertag() + "):");
            messages.addAll(subSession.getValue().friendManager().diagnoseFollowers());
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
    public void restart() {
        if (restartCallback != null) {
            restartCallback.run();
        } else {
            logger.error("No restart callback set");
        }
    }
}