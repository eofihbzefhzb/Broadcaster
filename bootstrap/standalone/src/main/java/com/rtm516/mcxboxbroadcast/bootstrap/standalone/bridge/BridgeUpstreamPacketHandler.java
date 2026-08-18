package com.rtm516.mcxboxbroadcast.bootstrap.standalone.bridge;

import com.rtm516.mcxboxbroadcast.core.Constants;
import com.rtm516.mcxboxbroadcast.core.Logger;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;

import java.security.interfaces.ECPublicKey;

final class BridgeUpstreamPacketHandler implements BedrockPacketHandler {
    private final BridgeServerSession session;
    private final StandaloneBridgeService bridgeService;
    private final Logger logger;
    private JSONObject skinData;
    private ChainValidationResult chain;
    private String clientJwt;

    BridgeUpstreamPacketHandler(BridgeServerSession session, StandaloneBridgeService bridgeService, Logger logger) {
        this.session = session;
        this.bridgeService = bridgeService;
        this.logger = logger;
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        int protocolVersion = packet.getProtocolVersion();
        int serverProtocolVersion = Constants.BEDROCK_CODEC.getProtocolVersion();

        if (protocolVersion != serverProtocolVersion) {
            PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(protocolVersion > serverProtocolVersion
                ? PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD
                : PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            session.sendPacketImmediately(status);
            session.disconnect();
            return PacketSignal.HANDLED;
        }

        session.setCodec(Constants.BEDROCK_CODEC);

        NetworkSettingsPacket networkSettingsPacket = new NetworkSettingsPacket();
        networkSettingsPacket.setCompressionThreshold(0);
        networkSettingsPacket.setCompressionAlgorithm(PacketCompressionAlgorithm.SNAPPY);

        session.sendPacketImmediately(networkSettingsPacket);
        session.setCompression(PacketCompressionAlgorithm.SNAPPY);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        try {
            chain = EncryptionUtils.validatePayload(packet.getAuthPayload());
            clientJwt = packet.getClientJwt();

            ECPublicKey identityPublicKey = (ECPublicKey) chain.identityClaims().parsedIdentityPublicKey();
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(clientJwt);
            EncryptionUtils.verifyClientData(clientJwt, identityPublicKey);
            skinData = new JSONObject(JsonUtil.parseJson(jws.getUnverifiedPayload()));

            initializeBridgeSession();
        } catch (Exception e) {
            session.disconnect("disconnectionScreen.internalError.cantConnect");
            throw new RuntimeException("Unable to complete bridge login", e);
        }
        return PacketSignal.HANDLED;
    }

    private void initializeBridgeSession() {
        logger.info("Initializing backend relay session");
        bridgeService.newClient(downstream -> {
            downstream.setCodec(Constants.BEDROCK_CODEC);
            downstream.setSendSession(this.session);
            downstream.getPeer().getCodecHelper().setEncodingSettings(EncodingSettings.CLIENT);
            this.session.setSendSession(downstream);

            BridgePlayerSession proxySession = new BridgePlayerSession(
                this.session,
                downstream,
                this.chain.identityClaims().extraData,
                logger
            );

            downstream.setPlayer(proxySession);
            this.session.setPlayer(proxySession);

            String authToken = BridgeForgeryUtils.forgeToken(proxySession.getProxyKeyPair(), this.chain.identityClaims().extraData);
            String forgedSkinData = BridgeForgeryUtils.forgeSkinData(proxySession.getProxyKeyPair(), this.skinData);

            LoginPacket login = new LoginPacket();
            login.setAuthPayload(new TokenPayload(authToken, AuthType.SELF_SIGNED));
            login.setClientJwt(forgedSkinData);
            login.setProtocolVersion(Constants.BEDROCK_CODEC.getProtocolVersion());

            downstream.setPacketHandler(new BridgeDownstreamInitialPacketHandler(downstream, proxySession, login));

            RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
            request.setProtocolVersion(Constants.BEDROCK_CODEC.getProtocolVersion());
            downstream.sendPacketImmediately(request);
        });
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        if (this.session.isConnected() && this.session.getDisconnectReason() != null) {
            logger.info("Upstream disconnected: " + this.session.getDisconnectReason());
        }
        BridgePlayerSession player = this.session.getPlayer();
        if (player != null) {
            player.closeDownstream(reason);
        }
    }
}
