package com.rtm516.mcxboxbroadcast.bootstrap.standalone;

import com.rtm516.mcxboxbroadcast.core.Constants;
import com.rtm516.mcxboxbroadcast.core.Logger;
import com.rtm516.mcxboxbroadcast.core.SessionInfo;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import com.rtm516.mcxboxbroadcast.core.nethernet.bridge.BridgeUpstreamPacketHandler;
import com.rtm516.mcxboxbroadcast.core.nethernet.bridge.NetherNetBridgeServerSession;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

public final class StandaloneBridgeService {
    private final CoreConfig config;
    private final Logger logger;
    private final Supplier<SessionInfo> sessionInfoSupplier;
    private final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    private volatile Channel server;

    public StandaloneBridgeService(CoreConfig config, Logger logger, Supplier<SessionInfo> sessionInfoSupplier) {
        this.config = config;
        this.logger = logger;
        this.sessionInfoSupplier = sessionInfoSupplier;
    }

    public void start() {
        if (this.server != null) {
            return;
        }

        InetSocketAddress proxyAddress = new InetSocketAddress(config.bridge().listenAddress(), config.bridge().listenPort());
        BedrockPong advertisement = createAdvertisement(config.bridge().listenPort());

        ChannelFuture bindFuture = new ServerBootstrap()
            .group(this.eventLoopGroup)
            .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
            .option(RakChannelOption.RAK_ADVERTISEMENT, advertisement.toByteBuf())
            .childHandler(new BedrockChannelInitializer<NetherNetBridgeServerSession>() {
                @Override
                protected NetherNetBridgeServerSession createSession0(BedrockPeer peer, int subClientId) {
                    return new NetherNetBridgeServerSession(peer, subClientId);
                }

                @Override
                protected void initSession(NetherNetBridgeServerSession session) {
                    // Standalone mode relays over RakNet, which negotiates SNAPPY
                    session.setPacketHandler(new BridgeUpstreamPacketHandler(session, StandaloneMain.sessionManager, logger, PacketCompressionAlgorithm.SNAPPY));
                }
            })
            .bind(proxyAddress)
            .awaitUninterruptibly();

        if (!bindFuture.isSuccess()) {
            Throwable cause = bindFuture.cause();
            logger.error("Failed to bind bridge listener on " + proxyAddress, cause);
            logger.error("Bridge mode needs exclusive ownership of UDP " + config.bridge().listenPort() + ". Stop Geyser or any other Bedrock listener on that port first.");
            throw new IllegalStateException("Bridge listener bind failed", cause);
        }

        this.server = bindFuture.channel();

        logger.info("Bedrock bridge listening on " + proxyAddress + " and relaying to " + getBackendAddress());
    }

    public void stop() {
        if (this.server != null) {
            this.server.disconnect();
            this.server = null;
        }
        this.eventLoopGroup.shutdownGracefully();
    }

    private InetSocketAddress getBackendAddress() {
        return new InetSocketAddress(config.bridge().backendAddress(), config.bridge().backendPort());
    }

    private BedrockCodec getCodec() {
        return Constants.BEDROCK_CODEC;
    }

    private BedrockPong createAdvertisement(int port) {
        SessionInfo sessionInfo = this.sessionInfoSupplier.get();
        return new BedrockPong()
            .edition("MCPE")
            .gameType(sessionInfo.getWorldType())
            .version(getCodec().getMinecraftVersion())
            .protocolVersion(getCodec().getProtocolVersion())
            .motd(sessionInfo.getWorldName())
            .playerCount(sessionInfo.getPlayers())
            .maximumPlayerCount(sessionInfo.getMaxPlayers())
            .subMotd(sessionInfo.getHostName())
            .nintendoLimited(false)
            .ipv4Port(port)
            .ipv6Port(port);
    }
}