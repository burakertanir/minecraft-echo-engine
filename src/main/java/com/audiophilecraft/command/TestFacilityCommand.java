package com.audiophilecraft.command;

import com.audiophilecraft.network.ModMessages;
import com.audiophilecraft.registry.SpeakerRegistry;
import com.audiophilecraft.sound.InternetAudioLoader;
import com.audiophilecraft.sound.SpeakerPlaybackData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class TestFacilityCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(TestFacilityCommand::registerCommand);
    }

    private static void registerCommand(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("audiophilecraft")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.literal("build_tiers").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;

                    World world = player.getWorld();
                    BlockPos startPos = player.getBlockPos().add(5, 0, 0); // Start slightly away

                    context.getSource().sendMessage(Text.literal("§aBuilding test map... (may lag)"));

                    // Dim: width, height, length
                    int[][] dimensions = {
                        {5, 3, 5}, // Tier 1
                        {12, 6, 12}, // Tier 2
                        {20, 9, 20}, // Tier 3
                        {28, 13, 28}, // Tier 4
                        {45, 18, 45}, // Tier 5
                        {75, 30, 75}, // Tier 6
                        {115, 45, 115}, // Tier 7
                        {165, 65, 165}, // Tier 8
                        {240, 95, 240}, // Tier 9
                        {350, 140, 350} // Tier 10
                    };

                    BlockState wallBlock = Blocks.SMOOTH_QUARTZ.getDefaultState();
                    BlockState lantern = Blocks.SEA_LANTERN.getDefaultState();
                    BlockState air = Blocks.AIR.getDefaultState();

                    BlockPos currentOrigin = startPos;

                    for (int tierIndex = 0; tierIndex < dimensions.length; tierIndex++) {
                        int w = dimensions[tierIndex][0];
                        int h = dimensions[tierIndex][1];
                        int l = dimensions[tierIndex][2];

                        for (int x = 0; x <= w; x++) {
                            for (int y = 0; y <= h; y++) {
                                for (int z = 0; z <= l; z++) {
                                    boolean isWall = (x == 0 || x == w || y == 0 || y == h || z == 0 || z == l);
                                    boolean isEdge = (x == 0 && y == 0)
                                            || (x == 0 && y == h)
                                            || (x == w && y == 0)
                                            || (x == w && y == h)
                                            || (x == 0 && z == 0)
                                            || (x == 0 && z == l)
                                            || (x == w && z == 0)
                                            || (x == w && z == l)
                                            || (y == 0 && z == 0)
                                            || (y == 0 && z == l)
                                            || (y == h && z == 0)
                                            || (y == h && z == l);

                                    BlockPos pos = currentOrigin.add(x, y, z);

                                    if (isEdge) {
                                        world.setBlockState(pos, lantern, 18);
                                    } else if (isWall) {
                                        world.setBlockState(pos, wallBlock, 18);
                                    } else {
                                        world.setBlockState(pos, air, 18); // Hollow inside
                                    }
                                }
                            }
                        }

                        // Add a glowstone marker at the center of the floor
                        BlockPos centerPos = currentOrigin.add(w / 2, 1, l / 2);
                        world.setBlockState(centerPos, Blocks.GLOWSTONE.getDefaultState(), 18);

                        // Move origin forward for the next tier
                        currentOrigin = currentOrigin.add(w + 10, 0, 0); // 10 blocks gap between tiers
                    }

                    context.getSource().sendMessage(Text.literal("§aAll tier enclosures built successfully!"));
                    return 1;
                }))
                // ─── TEST: LavaPlayer reset + retry flow ───
                .then(CommandManager.literal("test_retry").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;

                    context.getSource().sendMessage(Text.literal("§e[TEST] Starting LavaPlayer reset test..."));

                    // Step 1: Report current state
                    boolean wasInitialized;
                    synchronized (InternetAudioLoader.class) {
                        wasInitialized = true;
                        try {
                            InternetAudioLoader.getInstance();
                        } catch (Exception e) {
                            wasInitialized = false;
                        }
                    }
                    context.getSource().sendMessage(Text.literal("§7  LavaPlayer instance exists: " + wasInitialized));

                    // Step 2: Force reset
                    long resetStart = System.currentTimeMillis();
                    InternetAudioLoader.resetInstance();
                    long resetDuration = System.currentTimeMillis() - resetStart;
                    context.getSource()
                            .sendMessage(Text.literal("§a  ✅ resetInstance() completed (" + resetDuration + "ms)"));

                    // Step 3: Recreate and verify
                    long recreateStart = System.currentTimeMillis();
                    InternetAudioLoader newLoader = InternetAudioLoader.getInstance();
                    long recreateDuration = System.currentTimeMillis() - recreateStart;
                    context.getSource()
                            .sendMessage(Text.literal("§a  ✅ New instance created (" + recreateDuration + "ms)"));

                    // Step 4: Quick resolve test (non-blocking, just checks that YouTube source works)
                    context.getSource()
                            .sendMessage(Text.literal("§7  Testing whether the YouTube source manager works..."));
                    long testStart = System.currentTimeMillis();
                    var testTracks = newLoader.loadItemBlocking("ytsearch:test audio 10 seconds", 10_000);
                    long testDuration = System.currentTimeMillis() - testStart;
                    if (!testTracks.isEmpty()) {
                        context.getSource()
                                .sendMessage(Text.literal("§a  ✅ YouTube resolve succeeded! (" + testDuration
                                        + "ms) Track: " + testTracks.get(0).getInfo().title));
                    } else {
                        context.getSource()
                                .sendMessage(Text.literal("§c  ❌ YouTube resolve failed! (" + testDuration + "ms) "
                                        + "Check your network connection or YouTube access."));
                    }

                    context.getSource().sendMessage(Text.literal("§e[TEST] LavaPlayer reset test finished."));
                    return 1;
                }))
                // ─── TEST: Multiplayer sync status ───
                .then(CommandManager.literal("sync_status").executes(context -> {
                    MinecraftServer server = context.getSource().getServer();
                    int onlinePlayers =
                            server.getPlayerManager().getPlayerList().size();

                    context.getSource()
                            .sendMessage(Text.literal("§6═══ Multiplayer Sync Status ═══")
                                    .formatted(Formatting.GOLD));
                    context.getSource().sendMessage(Text.literal("§7Online players: §f" + onlinePlayers));

                    // Count players per dimension
                    java.util.Map<String, Integer> dimCounts = new java.util.HashMap<>();
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        String dimName =
                                p.getWorld().getRegistryKey().getValue().toString();
                        dimCounts.merge(dimName, 1, Integer::sum);
                    }
                    for (var entry : dimCounts.entrySet()) {
                        context.getSource()
                                .sendMessage(
                                        Text.literal("§7  " + entry.getKey() + ": §f" + entry.getValue() + " players"));
                    }

                    // Sync timeout info
                    context.getSource().sendMessage(Text.literal("§7Sync timeout: §f" + (30_000 / 1000) + "s"));
                    context.getSource().sendMessage(Text.literal("§7Retry settings: §fmax=" + 7 + ", delay=4s"));
                    context.getSource()
                            .sendMessage(Text.literal(
                                    "§7Worst-case retry duration: §f~" + (7 * 4 + 8) + "s (above timeout=30s ⚠)"));

                    // Simulate: what would happen if X players fail
                    if (onlinePlayers > 1) {
                        context.getSource().sendMessage(Text.literal(""));
                        context.getSource().sendMessage(Text.literal("§6─── Scenario Analysis ───"));
                        context.getSource()
                                .sendMessage(Text.literal("§7All succeed: §aEveryone starts ~at the same time"));
                        context.getSource()
                                .sendMessage(
                                        Text.literal("§71 player retries: §eEveryone waits ~4-8s, then sync starts"));
                        context.getSource()
                                .sendMessage(
                                        Text.literal("§7Everyone retries: §eEveryone waits ~4-8s, then sync starts"));
                        context.getSource()
                                .sendMessage(Text.literal(
                                        "§71 player 7x retry: §eWaits ~28-44s (may exceed timeout=30s ⚠)"));
                    }

                    return 1;
                }))
                // ─── TEST: Broadcast URL to all players (sync test) ───
                .then(CommandManager.literal("test_sync_url")
                        .then(CommandManager.argument("url", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ServerPlayerEntity player =
                                            context.getSource().getPlayer();
                                    if (player == null) return 0;

                                    String url = StringArgumentType.getString(context, "url");
                                    if (!ModMessages.isValidAudioUrl(url)) {
                                        context.getSource().sendMessage(Text.literal("§cInvalid URL: " + url));
                                        return 0;
                                    }

                                    MinecraftServer server = context.getSource().getServer();
                                    UUID ownerUUID = player.getUuid();
                                    Identifier playbackDimension =
                                            player.getWorld().getRegistryKey().getValue();
                                    List<SpeakerPlaybackData> speakers =
                                            SpeakerRegistry.findPlaybackDataByOwner(player.getWorld(), ownerUUID);

                                    if (speakers.isEmpty()) {
                                        context.getSource()
                                                .sendMessage(
                                                        Text.literal("§cNo speakers found! Place speakers first."));
                                        return 0;
                                    }

                                    // Count and list players who will receive the packet
                                    Set<UUID> waitingPlayers = ConcurrentHashMap.newKeySet();
                                    long sendStart = System.currentTimeMillis();

                                    for (ServerPlayerEntity onlinePlayer :
                                            server.getPlayerManager().getPlayerList()) {
                                        if (!onlinePlayer
                                                .getWorld()
                                                .getRegistryKey()
                                                .getValue()
                                                .equals(playbackDimension)) continue;
                                        ModMessages.sendPlayUrl(
                                                onlinePlayer, playbackDimension, ownerUUID, url, speakers, 1.0f, 1.0f);
                                        waitingPlayers.add(onlinePlayer.getUuid());
                                    }

                                    long sendDuration = System.currentTimeMillis() - sendStart;

                                    context.getSource().sendMessage(Text.literal("§6═══ Sync URL Test Started ═══"));
                                    context.getSource().sendMessage(Text.literal("§7URL: §f" + url));
                                    context.getSource()
                                            .sendMessage(Text.literal("§7Speaker count: §f" + speakers.size()));
                                    context.getSource()
                                            .sendMessage(Text.literal(
                                                    "§7Players receiving the packet: §f" + waitingPlayers.size()));
                                    context.getSource()
                                            .sendMessage(
                                                    Text.literal("§7Packet send duration: §f" + sendDuration + "ms"));
                                    context.getSource()
                                            .sendMessage(Text.literal("§eServer is waiting for all C2S_PLAYBACK_READY "
                                                    + "packets... (timeout=30s)"));
                                    context.getSource()
                                            .sendMessage(Text.literal("§7Clients retrying will not send READY yet."));
                                    context.getSource()
                                            .sendMessage(
                                                    Text.literal(
                                                            "§7Once all clients are ready, S2C_START_PLAYBACK will be broadcast."));

                                    return 1;
                                }))));
    }
}
