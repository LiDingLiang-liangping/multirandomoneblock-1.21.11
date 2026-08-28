package liangping.multirandomoneblock;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class CraftModeCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MultiRandomOneBlockMod mod) {
        dispatcher.register(Commands.literal("craftmode")
            // 1.21.11: hasPermission(int) 已删除，改用 hasPermissionLevel
            .requires(source -> source.hasPermissionLevel(4))
            .then(Commands.literal("cave")
                .executes(context -> {
                    if (!mod.isInitialized()) {
                        context.getSource().sendFailure(Component.literal("§c尚未设定人数！"));
                        return 0;
                    }
                    if (mod.isCaveMode()) {
                        context.getSource().sendFailure(Component.literal("§c已经是矿洞模式！"));
                        return 0;
                    }
                    if (!mod.canEnterCaveMode()) {
                        int required = mod.getRequiredCountForCaveMode();
                        int current = mod.getTotalBrokenCount();
                        context.getSource().sendFailure(Component.literal("§c挖掘数不足！当前: " + current + " / 需要: " + required));
                        return 0;
                    }
                    mod.setCaveMode(true);
                    broadcast(context.getSource().getServer(), "§6[多人随机单方块] 已进入矿洞模式！方块将主要刷新石头和矿物");
                    return 1;
                })
            )
            .then(Commands.literal("normal")
                .executes(context -> {
                    if (!mod.isInitialized()) {
                        context.getSource().sendFailure(Component.literal("§c尚未设定人数！"));
                        return 0;
                    }
                    if (!mod.isCaveMode()) {
                        context.getSource().sendFailure(Component.literal("§c已经是普通模式！"));
                        return 0;
                    }
                    mod.setCaveMode(false);
                    broadcast(context.getSource().getServer(), "§a[多人随机单方块] 已恢复普通模式");
                    return 1;
                })
            )
        );
    }

    private static void broadcast(MinecraftServer server, String message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
