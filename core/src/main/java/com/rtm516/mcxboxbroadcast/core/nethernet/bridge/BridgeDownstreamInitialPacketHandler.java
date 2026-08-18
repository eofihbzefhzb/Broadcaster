package com.rtm516.mcxboxbroadcast.core.nethernet.bridge;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.bedrock.util.JsonUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.json.JsonUtil;
import org.jose4j.json.internal.json_simple.JSONObject;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwx.HeaderParameterNames;

import javax.crypto.SecretKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

final class BridgeDownstreamInitialPacketHandler implements BedrockPacketHandler {
    private final BridgeClientSession session;
    private final BridgePlayerSession player;
    private final LoginPacket loginPacket;

    BridgeDownstreamInitialPacketHandler(BridgeClientSession session, BridgePlayerSession player, LoginPacket loginPacket) {
        this.session = session;
        this.player = player;
        this.loginPacket = loginPacket;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (packet instanceof NetworkSettingsPacket networkSettingsPacket) {
            return handle(networkSettingsPacket);
        }
        if (packet instanceof ServerToClientHandshakePacket handshakePacket) {
            return handle(handshakePacket);
        }

        this.session.setPacketHandler(new BridgeDownstreamPacketHandler(this.session, this.player));
        this.player.getLogger().info("Backend handshake complete; enabling Xbox relay");
        return PacketSignal.UNHANDLED;
    }

    public PacketSignal handle(NetworkSettingsPacket packet) {
        this.session.setCompression(packet.getCompressionAlgorithm());
        this.session.sendPacketImmediately(this.loginPacket);
        return PacketSignal.HANDLED;
    }

    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        try {
            JsonWebSignature jws = new JsonWebSignature();
            jws.setCompactSerialization(packet.getJwt());
            JSONObject saltJwt = new JSONObject(JsonUtil.parseJson(jws.getUnverifiedPayload()));
            String x5u = jws.getHeader(HeaderParameterNames.X509_URL);
            ECPublicKey serverKey = EncryptionUtils.parseKey(x5u);
            SecretKey key = EncryptionUtils.getSecretKey(
                this.player.getProxyKeyPair().getPrivate(),
                serverKey,
                Base64.getDecoder().decode(JsonUtils.childAsType(saltJwt, "salt", String.class))
            );
            this.session.enableEncryption(key);
        } catch (Exception e) {
            throw new RuntimeException("Unable to establish backend encryption", e);
        }

        ClientToServerHandshakePacket clientToServerHandshake = new ClientToServerHandshakePacket();
        this.session.sendPacketImmediately(clientToServerHandshake);
        this.session.setPacketHandler(new BridgeDownstreamPacketHandler(this.session, this.player));
        this.player.getLogger().info("Backend encryption established; enabling Xbox relay");
        return PacketSignal.HANDLED;
    }
}
