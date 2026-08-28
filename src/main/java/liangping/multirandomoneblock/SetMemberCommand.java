package liangping.multirandomoneblock;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class SetMemberCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MultiRandomOneBlockMod mod) {
        // 抛弃：移除 .requires()，让所有人都能用
        dispatcher.register(Commands.literal("setmember")
            .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                .executes(context -> {
                    int count = IntegerArgumentType.getInteger(context, "count");
                    if (mod.isInitialized()) {
                        context.getSource().sendFailure(Component.literal("§c人数已经设定，无法更改！"));
                        return 0;
                    }
                    var server = context.getSource().getServer();
                    mod.setPlayerCount(count, server);
                    context.getSource().sendSuccess(() -> Component.literal("§a成功设定参加人数为 " + count + " 人！"), true);
                    return 1;
                })
            )
        );

        dispatcher.register(Commands.literal("mrob")
            .then(Commands.literal("info")
                .executes(context -> {
                    if (mod.isInitialized()) {
                        int required = mod.getRequiredCountForCaveMode();
                        int current = mod.getTotalBrokenCount();
                        String mode = mod.isCaveMode() ? "§6矿洞模式" : "§a普通模式";
                        context.getSource().sendSuccess(() -> Component.literal("§a当前设定人数: " + mod.getPlayerCount()), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e当前模式: " + mode), false);
                        context.getSource().sendSuccess(() -> Component.literal("§7总挖掘数: " + current + " / " + required + " (矿洞模式所需)"), false);
                    } else {
                        context.getSource().sendSuccess(() -> Component.literal("§e尚未设定人数，请使用 /setmember <人数>"), false);
                    }
                    return 1;
                })
            )
        );
    }
}
