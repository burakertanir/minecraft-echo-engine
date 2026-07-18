package com.audiophilecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
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

                    context.getSource().sendMessage(Text.literal("§aTest Haritası İnşa Ediliyor... (Biraz kasabilir)"));

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

                    context.getSource().sendMessage(Text.literal("§aTüm Tier Plazmaları Başarıyla Oluşturuldu!"));
                    return 1;
                })));
    }
}
