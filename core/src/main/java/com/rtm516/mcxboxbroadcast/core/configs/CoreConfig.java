package com.rtm516.mcxboxbroadcast.core.configs;

import com.rtm516.mcxboxbroadcast.core.Constants;
import org.spongepowered.configurate.interfaces.meta.defaults.DefaultBoolean;
import org.spongepowered.configurate.interfaces.meta.defaults.DefaultNumeric;
import org.spongepowered.configurate.interfaces.meta.defaults.DefaultString;
import org.spongepowered.configurate.interfaces.meta.range.NumericRange;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public interface CoreConfig {
    @Comment("Core session settings")
    SessionConfig session();

    @Comment("Advanced NetherNet publishing settings")
    NetherNetConfig netherNet();

    @Comment("Friend/follower list sync settings")
    FriendSyncConfig friendSync();

    @Comment("Notification settings (e.g., Slack/Discord webhook)")
    NotificationConfig notifications();

    @Comment("Enable debug logging")
    @ExcludePlatform(platforms = {"Extension"})
    @DefaultBoolean(false)
    boolean debugMode();

    @Comment("Suppresses \"Updated session!\" log into debug")
    @ExcludePlatform(platforms = {"Extension"})
    @DefaultBoolean(false)
    boolean suppressSessionUpdateMessage();

    @Comment("Do not change!")
    @SuppressWarnings("unused")
    default int configVersion() {
        return Constants.CONFIG_VERSION;
    }

    @ConfigSerializable
    interface SessionConfig {
        @Comment("""
            The IP address to broadcast, you likely want to change this to
            your servers public IP""")
        @ExcludePlatform(platforms = {"Standalone"})
        @DefaultString("auto")
        String remoteAddress();

        @Comment("""
            The port to broadcast, this should be left as auto unless your
            manipulating the port using network rules or reverse proxies""")
        @ExcludePlatform(platforms = {"Standalone"})
        @DefaultString("auto")
        String remotePort();

        @Comment("""
            The amount of time in seconds to update session information
            Warning: This can be no lower than 20 due to Xbox rate limits""")
        @DefaultNumeric(30)
        @NumericRange(from = 20, to = Integer.MAX_VALUE)
        int updateInterval();

        @Comment("Should we query the bedrock server to sync the session information")
        @ExcludePlatform(platforms = {"Extension"})
        @DefaultBoolean(true)
        boolean queryServer();

        @Comment("Whether live Geyser or Bedrock ping data should update the advertised Xbox session")
        @DefaultBoolean(true)
        boolean syncFromGeyser();

        @Comment("""
            This uses checker.geysermc.org for querying if the native ping fails
            This can be useful in the case of docker networks or routing problems causing the native ping to fail""")
        @ExcludePlatform(platforms = {"Extension"})
        @DefaultBoolean(false)
        boolean webQueryFallback();

        @Comment("Fallback to config values if all other server query methods fail")
        @ExcludePlatform(platforms = {"Extension"})
        @DefaultBoolean(false)
        boolean configFallback();

        @Comment("The data to broadcast over xbox live. This is used as the base config and as the fallback if live query data is unavailable")
        @ExcludePlatform(platforms = {"Extension"})
        SessionInfo sessionInfo();

        // No ice-port-range: it only limits the ports of upstream's own NetherNet listener, which this
        // fork never starts - Geyser accepts the WebRTC connections.

        @ConfigSerializable
        interface SessionInfo {
            @Comment("The host name to broadcast")
            @DefaultString("Geyser Test Server")
            String hostName();

            @Comment("The world name to broadcast")
            @DefaultString("GeyserMC Demo & Test Server")
            String worldName();

            @Comment("The current number of players")
            @DefaultNumeric(0)
            int players();

            @Comment("The maximum number of players")
            @DefaultNumeric(20)
            int maxPlayers();

            @Comment("The IP address of the server")
            @DefaultString("127.0.0.1")
            String ip();

            @Comment("The port of the server")
            @DefaultNumeric(19132)
            @NumericRange(from = 1, to = 65535)
            int port();
        }
    }

    @ConfigSerializable
    interface NetherNetConfig {
        @Comment("""
            The externally hosted NetherNet network id to advertise in the Xbox session.
            This must match the listener that accepts the NetherNet/WebRTC join.
            Leave empty to auto-discover it.""")
        @DefaultString("")
        String externalNetworkId();

        @Comment("""
            The absolute path to the Geyser portal-session-status.json file.
            If left empty, Broadcaster will attempt to guess the path relative to its own folder.
            Example: C:\\path\\to\\Velocity\\plugins\\Geyser-Velocity\\portal-session-status.json""")
        @DefaultString("")
        String statusFilePath();

        @Comment("""
            How long standalone mode should wait for the local Geyser portal bridge to publish its
            automatically generated NetherNet ID when external-network-id is empty. MCXboxBroadcast
            can start first as long as Geyser is ready within this time; otherwise it exits.""")
        @DefaultNumeric(120)
        @NumericRange(from = 0, to = Integer.MAX_VALUE)
        int discoveryTimeoutSeconds();
    }

    @ConfigSerializable
    interface FriendSyncConfig {
        @Comment("""
            The amount of time in seconds to update session information
            Warning: This can be no lower than 20 due to Xbox rate limits""")
        @DefaultNumeric(60)
        @NumericRange(from = 20, to = Integer.MAX_VALUE)
        int updateInterval();

        @Comment("Should we automatically follow people that follow us")
        @DefaultBoolean(true)
        boolean autoFollow();

        @Comment("Should we automatically unfollow people that no longer follow us")
        @DefaultBoolean(true)
        boolean autoUnfollow();

        @Comment("Should we automatically send an invite when a friend is added")
        @DefaultBoolean(true)
        boolean initialInvite();

        @Comment("Friend expiry settings")
        ExpiryConfig expiry();

        @ConfigSerializable
        interface ExpiryConfig {
            @Comment("""
                Should we unfriend people that haven't joined the server in a while.
                Leave this off. Upstream records a friend's last visit when its own NetherNet listener
                transfers them; this fork never starts that listener - players join through Geyser -
                so nothing refreshes the record after it is first written. Turned on, this would
                unfriend every friend 'days' after they were first seen - daily players included -
                and take the server out of all of their friends lists.""")
            @DefaultBoolean(false)
            boolean enabled();

            @Comment("The amount of time in days before a friend is considered expired")
            @DefaultNumeric(15)
            @NumericRange(from = 1, to = Integer.MAX_VALUE)
            int days();

            @Comment("How often to check in seconds for expired friends")
            @DefaultNumeric(1800)
            @NumericRange(from = 1, to = Integer.MAX_VALUE)
            int check();
        }
    }

    @ConfigSerializable
    interface NotificationConfig {
        @Comment("Should we send a message to a slack webhook when the session is updated")
        @DefaultBoolean(false)
        boolean enabled();

        @Comment("""
            The webhook url to send the message to
            If you are using discord add "/slack" to the end of the webhook url""")
        @DefaultString("")
        String webhookUrl();

        @Comment("The message to send when the session is expired and needs to be updated")
        @DefaultString("""
            <!here> Xbox Session expired, sign in again to update it.
            
            Use the following link to sign in: %s
            Enter the code: %s""")
        String sessionExpiredMessage();

        @Comment("The message to send when a friend has restrictions in place that prevent them from being friends with our account")
        @DefaultString("""
            %s (%s) has restrictions in place that prevent them from being friends with our account.""")
        String friendRestrictionMessage();
    }
}
