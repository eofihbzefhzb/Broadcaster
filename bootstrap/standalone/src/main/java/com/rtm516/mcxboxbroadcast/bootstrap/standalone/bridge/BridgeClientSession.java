package com.rtm516.mcxboxbroadcast.bootstrap.standalone.bridge;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket;
import org.cloudburstmc.protocol.common.PacketSignal;

final class BridgeClientSession extends BedrockClientSession {
    private BedrockSession sendSession;
    private BridgePlayerSession player;

    BridgeClientSession(BedrockPeer peer, int subClientId) {
        super(peer, subClientId);
    }

    void setSendSession(BedrockSession sendSession) {
        this.sendSession = sendSession;
    }

    void setPlayer(BridgePlayerSession player) {
        this.player = player;
    }

    @Override
    protected void onPacket(BedrockPacketWrapper wrapper) {
        BedrockPacket packet = wrapper.getPacket();
        if (this.packetHandler == null) {
            return;
        }

        if (this.sendSession != null) {
            if (packet instanceof StartGamePacket startGamePacket) {
                BridgeDefinitionSync.applyStartGame(this, this.sendSession, startGamePacket);
            } else if (packet instanceof ItemComponentPacket itemComponentPacket) {
                BridgeDefinitionSync.applyItemComponents(this, this.sendSession, itemComponentPacket);
            }
        }

        if (this.packetHandler.handlePacket(packet) == PacketSignal.UNHANDLED && this.sendSession != null) {
            ByteBuf buffer = wrapper.getPacketBuffer().retainedSlice().skipBytes(wrapper.getHeaderLength());

            UnknownPacket sendPacket = new UnknownPacket();
            sendPacket.setPayload(buffer);
            sendPacket.setPacketId(wrapper.getPacketId());
            this.sendSession.sendPacket(sendPacket);
        }
    }
}
