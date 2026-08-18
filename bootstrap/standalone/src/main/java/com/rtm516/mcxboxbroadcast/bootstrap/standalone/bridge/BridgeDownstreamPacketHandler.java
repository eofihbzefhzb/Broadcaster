package com.rtm516.mcxboxbroadcast.bootstrap.standalone.bridge;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.common.PacketSignal;

final class BridgeDownstreamPacketHandler implements BedrockPacketHandler {
    private final BridgeClientSession session;
    private final BridgePlayerSession player;

    BridgeDownstreamPacketHandler(BridgeClientSession session, BridgePlayerSession player) {
        this.session = session;
        this.player = player;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        this.session.disconnect();
        return PacketSignal.UNHANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        if (player.getUpstream().isConnected()) {
            player.getUpstream().disconnect(reason);
        }
    }
}
