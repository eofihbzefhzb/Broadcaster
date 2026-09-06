package com.rtm516.mcxboxbroadcast.core.storage;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;

public interface StorageManager {
    String cache() throws IOException;
    void cache(String data) throws IOException;

    /**
     * The Xbox session id used last time, so a restart can publish into the same session document
     * rather than a fresh one. Members are held by Xbox against that document, and players already
     * in game do not re-add themselves to a new session, so minting a new id on every start threw
     * away every member that was making the server visible to their friends.
     */
    String sessionId() throws IOException;

    void sessionId(String data) throws IOException;

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

    interface PlayerHistoryStorage {
        boolean isFirstRun();
        Instant lastSeen(String xuid) throws IOException;
        void lastSeen(String xuid, Instant lastSeen) throws IOException;
        void clear(String xuid) throws IOException;
        Map<String, Instant> all() throws IOException;
    }
}
