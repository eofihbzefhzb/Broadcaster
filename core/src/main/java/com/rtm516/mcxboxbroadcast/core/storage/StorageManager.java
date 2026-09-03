package com.rtm516.mcxboxbroadcast.core.storage;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface StorageManager {
    String cache() throws IOException;
    void cache(String data) throws IOException;

    String subSessions() throws IOException;
    void subSessions(String data) throws IOException;

    String lastSessionResponse() throws IOException;
    void lastSessionResponse(String data) throws IOException;

    String currentSessionResponse() throws IOException;
    void currentSessionResponse(String data) throws IOException;

    StorageManager subSession(String id);

    File screenshot();

    void cleanup() throws IOException;

    PlayerHistoryStorage playerHistory();

    /**
     * Record of who has joined the session and, where it can be worked out, which account's
     * friends list brought them in.
     */
    JoinHistoryStorage joinHistory();

    /**
     * One row per player who has ever joined, rather than one row per join, so the table stays
     * proportional to the audience rather than to uptime.
     */
    interface JoinHistoryStorage {
        /**
         * Insert the player, or bump their last join and join count if they are already known.
         */
        void record(String xuid, String gamertag, Instant when) throws IOException;

        /**
         * Store which account(s) the player follows, as worked out after their first join.
         */
        void source(String xuid, String source) throws IOException;

        /**
         * Whether this player has already been attributed, so it is only ever done once each.
         */
        boolean hasSource(String xuid) throws IOException;

        List<JoinRecord> all() throws IOException;
    }

    record JoinRecord(String xuid, String gamertag, Instant firstJoin, Instant lastJoin, int joinCount, String source) {
    }

    interface PlayerHistoryStorage {
        boolean isFirstRun();
        Instant lastSeen(String xuid) throws IOException;
        void lastSeen(String xuid, Instant lastSeen) throws IOException;
        void clear(String xuid) throws IOException;
        Map<String, Instant> all() throws IOException;
    }
}
