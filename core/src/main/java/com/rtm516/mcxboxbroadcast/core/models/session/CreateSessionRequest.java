package com.rtm516.mcxboxbroadcast.core.models.session;

import com.rtm516.mcxboxbroadcast.core.ExpandedSessionInfo;

import java.util.Collections;
import java.util.Map;

public class CreateSessionRequest extends JoinSessionRequest {
    public final SessionProperties properties;

    public CreateSessionRequest(ExpandedSessionInfo sessionInfo, Map<String, String> nonces) {
        super(sessionInfo);
        this.properties = new SessionProperties(new SessionSystemProperties(), new SessionCustomProperties(
            4, // Public rather than friends of friends; Xbox's "followed" gate still decides who sees it
            false,
            "joinable_by_friends",
            true, // LanGame: what the builds before the NetherNet rewrite sent, and what this fork has run on since
            sessionInfo.getMaxPlayers(),
            sessionInfo.getPlayers(),
            true,
            Collections.singletonList(new Connection(sessionInfo.getNetherNetId(), sessionInfo.getPmsgId())),
            0,
            2,
            "level",
            sessionInfo.getHostName(),
            sessionInfo.getXuid(),
            "",
            sessionInfo.getWorldName(),
            "Survival",
            sessionInfo.getProtocol(),
            sessionInfo.getVersion(),
            false,
            false,
            nonces
        ));
    }
}
