package com.rtm516.mcxboxbroadcast.core.models.session;

import com.rtm516.mcxboxbroadcast.core.ExpandedSessionInfo;

import java.util.Collections;
import java.util.Map;

public class CreateSessionRequest extends JoinSessionRequest {
    public final SessionProperties properties;

    public CreateSessionRequest(ExpandedSessionInfo sessionInfo, Map<String, String> nonces) {
        super(sessionInfo);
        // The MPSD system properties are the real access gate: Xbox applies them before Minecraft
        // ever reads the Joinability label below. Leaving them pinned to "followed" is why
        // friends-of-friends could not see the world no matter what Joinability was set to.
        this.properties = new SessionProperties(
            new SessionSystemProperties(sessionInfo.getJoinRestriction(), sessionInfo.getReadRestriction(), false),
            new SessionCustomProperties(
            3,
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
