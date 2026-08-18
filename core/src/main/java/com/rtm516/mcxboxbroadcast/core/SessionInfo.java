package com.rtm516.mcxboxbroadcast.core;

import com.rtm516.mcxboxbroadcast.core.configs.CoreConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SessionInfo {
    private static final Pattern COLOR_PATTERN = Pattern.compile("\u00A7[\\w]");

    private String hostName;
    private String worldName;
    private int players;
    private int maxPlayers;
    private String ip;
    private int port;
    private String joinability = "joinable_by_friends";
    private String worldType = "Survival";
    private boolean editorWorld;
    private boolean hardcore;
    private boolean proxyBridgeEnabled;
    private String relayTargetAddress;
    private int relayTargetPort;
    private boolean externalNetherNetHosted;
    private String externalNetherNetId;

    public SessionInfo() {
    }

    public SessionInfo(CoreConfig.SessionConfig.SessionInfo config) {
        this.hostName = config.hostName();
        this.worldName = config.worldName();
        this.players = config.players();
        this.maxPlayers = config.maxPlayers();
        this.ip = config.ip();
        this.port = config.port();
    }

    public SessionInfo(String hostName, String worldName, int players, int maxPlayers, String ip, int port) {
        this.hostName = hostName;
        this.worldName = worldName;
        this.players = players;
        this.maxPlayers = maxPlayers;
        this.ip = ip;
        this.port = port;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = removeColorCodes(hostName);
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = removeColorCodes(worldName);
    }

    public String getVersion() {
        return Constants.BEDROCK_CODEC.getMinecraftVersion();
    }

    public int getProtocol() {
        return Constants.BEDROCK_CODEC.getProtocolVersion();
    }

    public int getPlayers() {
        // Allows the join button on 1.21.70 to show up
        if (players <= 0) {
            return 1;
        }
        return players;
    }

    public void setPlayers(int players) {
        this.players = players;
    }

    public int getMaxPlayers() {
        // Prevents the server from showing as full
        if (maxPlayers <= getPlayers()) {
            return getPlayers() + 1;
        }
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getJoinability() {
        return joinability;
    }

    public void setJoinability(String joinability) {
        this.joinability = joinability;
    }

    public String getWorldType() {
        return worldType;
    }

    public void setWorldType(String worldType) {
        this.worldType = worldType;
    }

    public boolean isEditorWorld() {
        return editorWorld;
    }

    public void setEditorWorld(boolean editorWorld) {
        this.editorWorld = editorWorld;
    }

    public boolean isHardcore() {
        return hardcore;
    }

    public void setHardcore(boolean hardcore) {
        this.hardcore = hardcore;
    }

    public boolean isProxyBridgeEnabled() {
        return proxyBridgeEnabled;
    }

    public void setProxyBridgeEnabled(boolean proxyBridgeEnabled) {
        this.proxyBridgeEnabled = proxyBridgeEnabled;
    }

    public String getRelayTargetAddress() {
        return relayTargetAddress;
    }

    public void setRelayTargetAddress(String relayTargetAddress) {
        this.relayTargetAddress = relayTargetAddress;
    }

    public int getRelayTargetPort() {
        return relayTargetPort;
    }

    public void setRelayTargetPort(int relayTargetPort) {
        this.relayTargetPort = relayTargetPort;
    }

    public boolean isExternalNetherNetHosted() {
        return externalNetherNetHosted;
    }

    public void setExternalNetherNetHosted(boolean externalNetherNetHosted) {
        this.externalNetherNetHosted = externalNetherNetHosted;
    }

    public String getExternalNetherNetId() {
        return externalNetherNetId;
    }

    public void setExternalNetherNetId(String externalNetherNetId) {
        this.externalNetherNetId = externalNetherNetId;
    }

    public SessionInfo copy() {
        SessionInfo copy = new SessionInfo(hostName, worldName, players, maxPlayers, ip, port);
        copy.setJoinability(joinability);
        copy.setWorldType(worldType);
        copy.setEditorWorld(editorWorld);
        copy.setHardcore(hardcore);
        copy.setProxyBridgeEnabled(proxyBridgeEnabled);
        copy.setRelayTargetAddress(relayTargetAddress);
        copy.setRelayTargetPort(relayTargetPort);
        copy.setExternalNetherNetHosted(externalNetherNetHosted);
        copy.setExternalNetherNetId(externalNetherNetId);
        return copy;
    }

    private static String removeColorCodes(String string) {
        if (string == null) {
            return "";
        }
        Matcher matcher = COLOR_PATTERN.matcher(string);
        return matcher.replaceAll("").trim();
    }
}
