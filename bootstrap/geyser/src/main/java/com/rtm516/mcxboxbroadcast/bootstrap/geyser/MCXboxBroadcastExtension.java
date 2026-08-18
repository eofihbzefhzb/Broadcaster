package com.rtm516.mcxboxbroadcast.bootstrap.geyser;

import com.rtm516.mcxboxbroadcast.core.BuildData;
import com.rtm516.mcxboxbroadcast.core.Constants;
import com.rtm516.mcxboxbroadcast.core.Logger;
import com.rtm516.mcxboxbroadcast.core.SessionInfo;
import com.rtm516.mcxboxbroadcast.core.SessionManager;
import com.rtm516.mcxboxbroadcast.core.configs.ConfigLoader;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.notifications.NotificationManager;
import com.rtm516.mcxboxbroadcast.core.notifications.SlackNotificationManager;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionCreationException;
import com.rtm516.mcxboxbroadcast.core.exceptions.SessionUpdateException;
import com.rtm516.mcxboxbroadcast.core.storage.FileStorageManager;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.event.connection.GeyserBedrockPingEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

public class MCXboxBroadcastExtension implements Extension {
    Logger logger;
    NotificationManager notificationManager;
    SessionManager sessionManager;
    SessionInfo sessionInfo;
    CoreConfig config;
    boolean broadcastEnabled;

    @Subscribe
    public void onCommandDefine(GeyserDefineCommandsEvent event) {
        event.register(Command.builder(this)
            .source(CommandSource.class)
            .name("restart")
            .description("Restart the connection to Xbox Live.")
            .executor((source, command, args) -> {
                if (!source.isConsole()) {
                    source.sendMessage("This command can only be ran from the console.");
                    return;
                }
                if (!broadcastEnabled || sessionManager == null) {
                    source.sendMessage("MCXboxBroadcast is disabled in config.yml.");
                    return;
                }

                restart();
            })
            .build());

        event.register(Command.builder(this)
            .source(CommandSource.class)
            .name("dumpsession")
            .description("Dump the current session to json files.")
            .executor((source, command, args) -> {
                if (!source.isConsole()) {
                    source.sendMessage("This command can only be ran from the console.");
                    return;
                }
                if (!broadcastEnabled || sessionManager == null) {
                    source.sendMessage("MCXboxBroadcast is disabled in config.yml.");
                    return;
                }

                logger.info("Dumping session responses to 'lastSessionResponse.json' and 'currentSessionResponse.json'");

                sessionManager.dumpSession();
            })
            .build());

        event.register(Command.builder(this)
            .source(CommandSource.class)
            .name("accounts")
            .description("Manage sub-accounts.")
            .executor((source, command, args) -> {
                if (!source.isConsole()) {
                    source.sendMessage("This command can only be ran from the console.");
                    return;
                }
                if (!broadcastEnabled || sessionManager == null) {
                    source.sendMessage("MCXboxBroadcast is disabled in config.yml.");
                    return;
                }

                if (args.length < 2) {
                    if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                        sessionManager.listSessions();
                        return;
                    }

                    source.sendMessage("Usage:");
                    source.sendMessage("accounts list");
                    source.sendMessage("accounts add/remove <sub-session-id>");
                    return;
                }

                switch (args[0].toLowerCase()) {
                    case "add":
                        sessionManager.addSubSession(args[1]);
                        break;
                    case "remove":
                        sessionManager.removeSubSession(args[1]);
                        break;
                    default:
                        source.sendMessage("Unknown accounts command: " + args[0]);
                }
            })
            .build());

        event.register(Command.builder(this)
            .source(CommandSource.class)
            .name("version")
            .description("Get the version of the extension.")
            .executor((source, command, args) -> {
                source.sendMessage("MCXboxBroadcast Extension " + BuildData.VERSION);
            })
            .build());
    }

    private void restart() {
        if (!broadcastEnabled || sessionManager == null) {
            logger.info("MCXboxBroadcast is disabled in config.yml");
            return;
        }

        sessionManager.shutdown();

        // Create a new session manager, but reuse the notification manager as config hasn't been reloaded
        sessionManager = new SessionManager(new FileStorageManager(this.dataFolder().toString(), this.dataFolder().resolve("screenshot.jpg").toString()), notificationManager, logger);
        sessionManager.setNetherNetPortRange(config.session().icePortRange().min(), config.session().icePortRange().max());

        // Pull onto another thread so we don't hang the main thread
        sessionManager.scheduledThread().execute(this::createSession);
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        logger = new ExtensionLoggerImpl(this.logger());

        logger.info("Starting MCXboxBroadcast Extension " + BuildData.VERSION + " for Bedrock " + Constants.BEDROCK_CODEC.getMinecraftVersion() + " (" + Constants.BEDROCK_CODEC.getProtocolVersion() + ")");

        // Load the config file
        File configFile = dataFolder().resolve("config.yml").toFile();

        // Ensure the data folder exists
        if (!dataFolder().toFile().exists()) {
            if (!dataFolder().toFile().mkdirs()) {
                logger.error("Failed to create data folder, extension will not start!");
                this.disable();
                return;
            }
        }

        try {
            config = ConfigLoader.loadConfig(configFile, "Extension");
        } catch (IOException e) {
            logger.error("Failed to load config, extension will not start!", e);
            this.disable();
            return;
        }

        broadcastEnabled = config.enabled();
        if (!broadcastEnabled) {
            logger.info("MCXboxBroadcast is installed but disabled in config.yml. Set enabled: true to start broadcasting.");
            return;
        }

        // TODO Support multiple notification types
        notificationManager = new SlackNotificationManager(logger, config.notifications());

        // Create the session manager
        sessionManager = new SessionManager(new FileStorageManager(this.dataFolder().toString(), this.dataFolder().resolve("screenshot.jpg").toString()), notificationManager, logger);
        sessionManager.setNetherNetPortRange(config.session().icePortRange().min(), config.session().icePortRange().max());

        // Pull onto another thread so we don't hang the main thread
        sessionManager.scheduledThread().execute(() -> {
            // Get the ip to broadcast
            String ip = config.session().remoteAddress();
            if (ip.equals("auto")) {
                ip = this.geyserApi().bedrockListener().address();

                try {
                    InetAddress address = InetAddress.getByName(ip);

                    // Get the public IP if the config ip is a non-public address
                    if (address.isSiteLocalAddress() || address.isAnyLocalAddress() || address.isLoopbackAddress()) {
                        HttpRequest ipRequest = HttpRequest.newBuilder()
                            .uri(URI.create("https://ipv4.icanhazip.com"))
                            .GET()
                            .build();

                        ip = HttpClient.newHttpClient().send(ipRequest, HttpResponse.BodyHandlers.ofString()).body().trim();
                    }
                } catch (IOException | InterruptedException e) {
                    // Silently ignore
                }
            }

            // Get the port to broadcast
            int port = this.geyserApi().bedrockListener().port();
            if (!config.session().remotePort().equals("auto")) {
                port = Integer.parseInt(config.session().remotePort());
            }

            sessionInfo = buildSessionInfo(
                this.geyserApi().bedrockListener().secondaryMotd(),
                this.geyserApi().bedrockListener().primaryMotd(),
                this.geyserApi().onlineConnections().size(),
                GeyserImpl.getInstance().config().motd().maxPlayers(),
                ip,
                port
            );

            createSession();
        });
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
    }

    @Subscribe
    public void onBedrockPing(GeyserBedrockPingEvent event) {
        if (sessionInfo == null || !config.session().syncFromGeyser()) {
            return;
        }

        SessionInfo updated = buildSessionInfo(
            event.secondaryMotd(),
            event.primaryMotd(),
            event.playerCount(),
            event.maxPlayerCount(),
            sessionInfo.getIp(),
            sessionInfo.getPort()
        );

        sessionInfo.setHostName(updated.getHostName());
        sessionInfo.setWorldName(updated.getWorldName());
        sessionInfo.setPlayers(updated.getPlayers());
        sessionInfo.setMaxPlayers(updated.getMaxPlayers());
        sessionInfo.setJoinability(updated.getJoinability());
        sessionInfo.setWorldType(updated.getWorldType());
        sessionInfo.setEditorWorld(updated.isEditorWorld());
        sessionInfo.setHardcore(updated.isHardcore());
    }

    private SessionInfo buildSessionInfo(String liveHostName, String liveWorldName, int livePlayers, int liveMaxPlayers, String ip, int port) {
        SessionInfo info = new SessionInfo();

        String hostName = config.session().sessionInfo().hostName().isBlank() ? liveHostName : config.session().sessionInfo().hostName();
        if (hostName == null || hostName.isBlank()) {
            hostName = sessionManager.getGamertag();
        }

        String worldName = config.session().sessionInfo().worldName().isBlank() ? liveWorldName : config.session().sessionInfo().worldName();
        if (worldName == null || worldName.isBlank()) {
            worldName = hostName;
        }

        int players = config.session().sessionInfo().players() > 0 ? config.session().sessionInfo().players() : livePlayers;
        int maxPlayers = config.session().sessionInfo().maxPlayers() > 0 ? config.session().sessionInfo().maxPlayers() : liveMaxPlayers;

        info.setHostName(hostName);
        info.setWorldName(worldName);
        info.setPlayers(players);
        info.setMaxPlayers(maxPlayers);
        info.setIp(ip);
        info.setPort(port);
        info.setJoinability(config.xboxSession().joinability());
        info.setWorldType(config.xboxSession().worldType());
        info.setEditorWorld(config.xboxSession().editorWorld());
        info.setHardcore(config.xboxSession().hardcore());
        info.setExternalNetherNetHosted(config.netherNet().externalHosted() && !config.netherNet().externalNetworkId().isBlank());
        info.setExternalNetherNetId(config.netherNet().externalNetworkId());
        return info;
    }


    private void createSession() {
        // Create the Xbox session
        sessionManager.restartCallback(this::restart);
        try {
            boolean initialized = sessionManager.init(sessionInfo, config.friendSync());
            if (!initialized) {
                // We assume an error has already been logged
                this.setEnabled(false);
                return;
            }
        } catch (SessionCreationException | SessionUpdateException e) {
            sessionManager.logger().error("Failed to create xbox session!", e);
            return;
        }

        // Start the update timer
        sessionManager.scheduledThread().scheduleWithFixedDelay(this::tick, config.session().updateInterval(), config.session().updateInterval(), TimeUnit.SECONDS);
    }

    private void tick() {
        try {
            sessionManager.updateSession(sessionInfo);
        } catch (SessionUpdateException e) {
            sessionManager.logger().error("Failed to update session information!", e);
        }
    }
}
