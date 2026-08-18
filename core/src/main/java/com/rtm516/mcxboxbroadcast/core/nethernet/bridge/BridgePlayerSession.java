package com.rtm516.mcxboxbroadcast.core.nethernet.bridge;

import com.rtm516.mcxboxbroadcast.core.Logger;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import java.security.KeyPair;

final class BridgePlayerSession {
    private final NetherNetBridgeServerSession upstream;
    private final BridgeClientSession downstream;
    private final ChainValidationResult.IdentityData identityData;
    private final KeyPair proxyKeyPair = EncryptionUtils.createKeyPair();
    private final Logger logger;

    BridgePlayerSession(NetherNetBridgeServerSession upstream, BridgeClientSession downstream, ChainValidationResult.IdentityData identityData, Logger logger) {
        this.upstream = upstream;
        this.downstream = downstream;
        this.identityData = identityData;
        this.logger = logger.prefixed(identityData.displayName);
    }

    NetherNetBridgeServerSession getUpstream() {
        return upstream;
    }

    ChainValidationResult.IdentityData getIdentityData() {
        return identityData;
    }

    BridgeClientSession getDownstream() {
        return downstream;
    }

    KeyPair getProxyKeyPair() {
        return proxyKeyPair;
    }

    Logger getLogger() {
        return logger;
    }

    void closeDownstream(CharSequence reason) {
        if (downstream.isConnected()) {
            if (reason == null || reason.length() == 0) {
                downstream.disconnect();
            } else {
                downstream.disconnect(reason);
            }
        }
        downstream.getPeer().close(reason);
    }
}
