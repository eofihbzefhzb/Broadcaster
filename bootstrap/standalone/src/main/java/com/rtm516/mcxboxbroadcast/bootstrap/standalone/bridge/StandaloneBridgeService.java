package com.rtm516.mcxboxbroadcast.bootstrap.standalone.bridge;

import com.rtm516.mcxboxbroadcast.core.Constants;
import com.rtm516.mcxboxbroadcast.core.Logger;
import com.rtm516.mcxboxbroadcast.core.SessionInfo;
import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;
import io.netty.bootstrap.Bootstrap;
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
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StandaloneBridgeService {
    private final CoreConfig config;
    private final Logger logger;
    private final Supplier<SessionInfo> sessionInfoSupplier;
    private final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    private final Set<Channel> clients = ConcurrentHashMap.newKeySet();
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
            .childHandler(new BedrockChannelInitializer<BridgeServerSession>() {
                @Override
                protected BridgeServerSession createSession0(BedrockPeer peer, int subClientId) {
                    return new BridgeServerSession(peer, subClientId);
                }

                @Override
                protected void initSession(BridgeServerSession session) {
                    session.setPacketHandler(new BridgeUpstreamPacketHandler(session, StandaloneBridgeService.this, logger));
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
        this.clients.forEach(Channel::disconnect);
        if (this.server != null) {
            this.server.disconnect();
            this.server = null;
        }
        this.eventLoopGroup.shutdownGracefully();
    }

    public void newClient(Consumer<BridgeClientSession> sessionConsumer) {
        Channel channel = new Bootstrap()
            .group(this.eventLoopGroup)
            .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
            .option(RakChannelOption.RAK_PROTOCOL_VERSION, getCodec().getRaknetProtocolVersion())
            .handler(new BedrockChannelInitializer<BridgeClientSession>() {
                @Override
                protected BridgeClientSession createSession0(BedrockPeer peer, int subClientId) {
                    return new BridgeClientSession(peer, subClientId);
                }

                @Override
                protected void initSession(BridgeClientSession session) {
                    sessionConsumer.accept(session);
                }
            })
            .connect(getBackendAddress())
            .awaitUninterruptibly()
            .channel();

        this.clients.add(channel);
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
