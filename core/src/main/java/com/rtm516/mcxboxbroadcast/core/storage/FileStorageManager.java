package com.rtm516.mcxboxbroadcast.core.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class FileStorageManager implements StorageManager {
    private final String cacheFolder;
    private final String screenshotPath;
    private final PlayerHistoryStorage playerHistoryStorage;
    private final JoinHistoryStorage joinHistoryStorage;

    public FileStorageManager(String cacheFolder, String screenshotPath) {
        this.cacheFolder = cacheFolder;
        this.screenshotPath = screenshotPath;

        try {
            Files.createDirectories(Paths.get(cacheFolder));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache folder", e);
        }

        this.playerHistoryStorage = new SqlitePlayerHistoryStorage(Paths.get(cacheFolder, "player_history.db"));
        this.joinHistoryStorage = new SqliteJoinHistoryStorage(Paths.get(cacheFolder, "join_history.db"));
    }

    private String read(String file) throws IOException {
        Path cacheFile = Paths.get(cacheFolder, file);
        if (!Files.exists(cacheFile)) {
            return "";
        }
        return Files.readString(cacheFile);
    }

    private void write(String file, String data) throws IOException {
        Path filePath = Paths.get(cacheFolder, file);
        // Cleanup the file if the data is empty
        if (data == null || data.isBlank()) {
            Files.deleteIfExists(filePath);
            return;
        }

        Files.writeString(filePath, data);
    }


    @Override
    public String cache() throws IOException {
        return read("cache.json");
    }

    @Override
    public void cache(String data) throws IOException {
        write("cache.json", data);
    }

    @Override
    public String subSessions() throws IOException {
        return read("sub_sessions.json");
    }

    @Override
    public void subSessions(String data) throws IOException {
        write("sub_sessions.json", data);
    }

    @Override
    public String lastSessionResponse() throws IOException {
        return read("lastSessionResponse.json");
    }

    @Override
    public void lastSessionResponse(String data) throws IOException {
        write("lastSessionResponse.json", data);
    }

    @Override
    public String currentSessionResponse() throws IOException {
        return read("currentSessionResponse.json");
    }

    @Override
    public void currentSessionResponse(String data) throws IOException {
        write("currentSessionResponse.json", data);
    }

    @Override
    public StorageManager subSession(String id) {
        return new FileStorageManager(Paths.get(cacheFolder, id).toString(), screenshotPath);
    }

    @Override
    public File screenshot() {
        return new File(screenshotPath);
    }

    @Override
    public void cleanup() throws IOException {
        Path cache = Paths.get(cacheFolder);
        try (Stream<Path> files = Files.walk(cache)) {
            files.map(Path::toFile)
                .forEach(File::delete);
            cache.toFile().delete();
        }
    }

    @Override
    public PlayerHistoryStorage playerHistory() {
        return playerHistoryStorage;
    }

    @Override
    public JoinHistoryStorage joinHistory() {
        return joinHistoryStorage;
    }

    /**
     * Uses prepared statements throughout, unlike the player history above, because gamertags are
     * free text and an apostrophe in one would otherwise break the statement.
     */
    public static class SqliteJoinHistoryStorage implements JoinHistoryStorage {
        private Connection connection;

        public SqliteJoinHistoryStorage(Path dbFile) {
            try {
                Class.forName("org.sqlite.JDBC");

                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);

                try (Statement createJoinsTable = connection.createStatement()) {
                    createJoinsTable.executeUpdate("CREATE TABLE IF NOT EXISTS joins ("
                        + "xuid VARCHAR(32) PRIMARY KEY, gamertag TEXT, firstJoin INTEGER, "
                        + "lastJoin INTEGER, joinCount INTEGER, source TEXT);");
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to setup join history database", e);
            }
        }

        @Override
        public void record(String xuid, String gamertag, Instant when) throws IOException {
            long epoch = when.getEpochSecond();
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO joins (xuid, gamertag, firstJoin, lastJoin, joinCount) VALUES (?, ?, ?, ?, 1) "
                    + "ON CONFLICT(xuid) DO UPDATE SET gamertag = excluded.gamertag, "
                    + "lastJoin = excluded.lastJoin, joinCount = joinCount + 1;")) {
                statement.setString(1, xuid);
                statement.setString(2, gamertag);
                statement.setLong(3, epoch);
                statement.setLong(4, epoch);
                statement.executeUpdate();
            } catch (Exception e) {
                throw new IOException("Failed to record the join of xuid: " + xuid, e);
            }
        }

        @Override
        public void source(String xuid, String source) throws IOException {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE joins SET source = ? WHERE xuid = ?;")) {
                statement.setString(1, source);
                statement.setString(2, xuid);
                statement.executeUpdate();
            } catch (Exception e) {
                throw new IOException("Failed to store the source of xuid: " + xuid, e);
            }
        }

        @Override
        public boolean hasSource(String xuid) throws IOException {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source FROM joins WHERE xuid = ?;")) {
                statement.setString(1, xuid);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return false;
                    }
                    String source = resultSet.getString("source");
                    return source != null && !source.isBlank();
                }
            } catch (Exception e) {
                throw new IOException("Failed to read the source of xuid: " + xuid, e);
            }
        }

        @Override
        public List<JoinRecord> all() throws IOException {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                     "SELECT xuid, gamertag, firstJoin, lastJoin, joinCount, source FROM joins;")) {
                List<JoinRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new JoinRecord(
                        resultSet.getString("xuid"),
                        resultSet.getString("gamertag"),
                        Instant.ofEpochSecond(resultSet.getLong("firstJoin")),
                        Instant.ofEpochSecond(resultSet.getLong("lastJoin")),
                        resultSet.getInt("joinCount"),
                        resultSet.getString("source")));
                }
                return records;
            } catch (Exception e) {
                throw new IOException("Failed to read the join history", e);
            }
        }
    }

    public class SqlitePlayerHistoryStorage implements PlayerHistoryStorage {
        private Connection connection;
        private boolean firstRun = false;

        public SqlitePlayerHistoryStorage(Path dbFile) {
            try {
                Class.forName("org.sqlite.JDBC");

                if (!Files.exists(dbFile)) {
                    firstRun = true;
                }

                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);

                try (Statement createPlayersTable = connection.createStatement()) {
                    createPlayersTable.executeUpdate("CREATE TABLE IF NOT EXISTS players (xuid VARCHAR(32), lastSeen INTEGER, PRIMARY KEY(xuid));");
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to setup player history database", e);
            }
        }

        @Override
        public boolean isFirstRun() {
            return firstRun;
        }

        @Override
        public void lastSeen(String xuid, Instant lastSeen) throws IOException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT OR REPLACE INTO players (xuid, lastSeen) VALUES ('" + xuid + "', " + lastSeen.getEpochSecond() + ");");
            } catch (Exception e) {
                throw new IOException("Failed to update player history for xuid: " + xuid, e);
            }
        }

        @Override
        public Instant lastSeen(String xuid) throws IOException {
            try (Statement statement = connection.createStatement()) {
                var resultSet = statement.executeQuery("SELECT lastSeen FROM players WHERE xuid = '" + xuid + "';");
                if (resultSet.next()) {
                    long lastSeenEpoch = resultSet.getLong("lastSeen");
                    return Instant.ofEpochSecond(lastSeenEpoch);
                } else {
                    return null;
                }
            } catch (Exception e) {
                throw new IOException("Failed to retrieve player history for xuid: " + xuid, e);
            }
        }

        @Override
        public void clear(String xuid) throws IOException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM players WHERE xuid = '" + xuid + "';");
            } catch (Exception e) {
                throw new IOException("Failed to remove player history for xuid: " + xuid, e);
            }
        }

        @Override
        public Map<String, Instant> all() throws IOException {
            try (Statement statement = connection.createStatement()) {
                var resultSet = statement.executeQuery("SELECT xuid, lastSeen FROM players;");
                Map<String, Instant> playerHistory = new HashMap<>();
                while (resultSet.next()) {
                    String xuid = resultSet.getString("xuid");
                    long lastSeenEpoch = resultSet.getLong("lastSeen");
                    playerHistory.put(xuid, Instant.ofEpochSecond(lastSeenEpoch));
                }
                return playerHistory;
            } catch (Exception e) {
                throw new IOException("Failed to retrieve all player history", e);
            }
        }
    }
}
