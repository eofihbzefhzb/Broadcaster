package com.rtm516.mcxboxbroadcast.core.models.session;

import com.rtm516.mcxboxbroadcast.core.ExpandedSessionInfo;

import java.util.Collections;
import java.util.Map;

public class CreateSessionRequest extends JoinSessionRequest {
    public final SessionProperties properties;

    public CreateSessionRequest(ExpandedSessionInfo sessionInfo, Map<String, String> nonces) {
        super(sessionInfo);
        // Two independent axes, easily confused:
        //  - the MPSD system properties below are Xbox's own gate, applied before Minecraft reads
        //    anything. "followed" limits the session to users followed by one of its members.
        //  - BroadcastSetting is Minecraft's visibility value (3 = friends of friends) and
        //    Joinability is the relationship it demands to join.
        // Widening Joinability is not a way past the Xbox gate, and there is no "friends of
        // friends" Joinability value to widen it to - see the config comment on joinability().
        this.properties = new SessionProperties(
            new SessionSystemProperties(sessionInfo.getJoinRestriction(), sessionInfo.getReadRestriction(), false),
            new SessionCustomProperties(
            sessionInfo.getBroadcastSetting(),
            false,
            sessionInfo.getJoinability(),
            false,
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
            sessionInfo.getWorldType(),
            sessionInfo.getProtocol(),
            sessionInfo.getVersion(),
            sessionInfo.isEditorWorld(),
            sessionInfo.isHardcore(),
            nonces
        ));
    }
}
