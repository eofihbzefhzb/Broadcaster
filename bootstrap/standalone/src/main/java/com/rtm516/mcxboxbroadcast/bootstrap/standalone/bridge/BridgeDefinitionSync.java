package com.rtm516.mcxboxbroadcast.bootstrap.standalone.bridge;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockSession;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;

import java.util.ArrayList;
import java.util.List;

final class BridgeDefinitionSync {
    private BridgeDefinitionSync() {
    }

    static void applyStartGame(BedrockSession source, BedrockSession target, StartGamePacket packet) {
        if (packet.getItemDefinitions() != null && !packet.getItemDefinitions().isEmpty()) {
            SimpleDefinitionRegistry<ItemDefinition> itemDefinitions = buildItemDefinitions(packet.getItemDefinitions());
            source.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
            target.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
        }

        if (packet.getBlockProperties() != null && !packet.getBlockProperties().isEmpty()) {
            SimpleDefinitionRegistry<BlockDefinition> blockDefinitions = buildBlockDefinitionsFromProperties(packet.getBlockProperties());
            source.getPeer().getCodecHelper().setBlockDefinitions(blockDefinitions);
            target.getPeer().getCodecHelper().setBlockDefinitions(blockDefinitions);
        } else if (packet.getBlockPalette() != null && !packet.getBlockPalette().isEmpty()) {
            SimpleDefinitionRegistry<BlockDefinition> blockDefinitions = buildBlockDefinitionsFromPalette(packet.getBlockPalette());
            source.getPeer().getCodecHelper().setBlockDefinitions(blockDefinitions);
            target.getPeer().getCodecHelper().setBlockDefinitions(blockDefinitions);
        }
    }

    static void applyItemComponents(BedrockSession source, BedrockSession target, ItemComponentPacket packet) {
        if (packet.getItems() == null || packet.getItems().isEmpty()) {
            return;
        }
        SimpleDefinitionRegistry<ItemDefinition> itemDefinitions = buildItemDefinitions(packet.getItems());
        source.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
        target.getPeer().getCodecHelper().setItemDefinitions(itemDefinitions);
    }

    private static SimpleDefinitionRegistry<ItemDefinition> buildItemDefinitions(List<ItemDefinition> items) {
        return SimpleDefinitionRegistry.<ItemDefinition>builder().addAll(items).build();
    }

    private static SimpleDefinitionRegistry<BlockDefinition> buildBlockDefinitionsFromPalette(List<NbtMap> palette) {
        SimpleDefinitionRegistry.Builder<BlockDefinition> builder = SimpleDefinitionRegistry.builder();
        List<BlockDefinition> blockDefinitions = new ArrayList<>(palette.size());

        for (int runtimeId = 0; runtimeId < palette.size(); runtimeId++) {
            NbtMap entry = palette.get(runtimeId);
            String identifier = entry.getString("name", "");
            NbtMap states = entry.getCompound("states", NbtMap.EMPTY);
            SimpleBlockDefinition definition = new SimpleBlockDefinition(identifier, runtimeId, states);
            definition.setPersistentIdentifier(identifier);
            blockDefinitions.add(definition);
        }

        builder.addAll(blockDefinitions);
        return builder.build();
    }

    private static SimpleDefinitionRegistry<BlockDefinition> buildBlockDefinitionsFromProperties(List<BlockPropertyData> blockProperties) {
        SimpleDefinitionRegistry.Builder<BlockDefinition> builder = SimpleDefinitionRegistry.builder();
        List<BlockDefinition> blockDefinitions = new ArrayList<>(blockProperties.size());

        for (int runtimeId = 0; runtimeId < blockProperties.size(); runtimeId++) {
            BlockPropertyData entry = blockProperties.get(runtimeId);
            SimpleBlockDefinition definition = new SimpleBlockDefinition(entry.getName(), runtimeId, entry.getProperties());
            definition.setPersistentIdentifier(entry.getName());
            blockDefinitions.add(definition);
        }

        builder.addAll(blockDefinitions);
        return builder.build();
    }
}
