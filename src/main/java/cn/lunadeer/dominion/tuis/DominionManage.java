package cn.lunadeer.dominion.tuis;

import cn.lunadeer.dominion.dtos.DominionDTO;
import cn.lunadeer.dominion.utils.Notification;
import cn.lunadeer.dominion.utils.STUI.Button;
import cn.lunadeer.dominion.utils.STUI.Line;
import cn.lunadeer.dominion.utils.STUI.View;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static cn.lunadeer.dominion.commands.Apis.playerOnly;
import static cn.lunadeer.dominion.tuis.Apis.getDominionNameArg_1;
import static cn.lunadeer.dominion.tuis.Apis.noAuthToManage;

public class DominionManage {
    public static void show(CommandSender sender, String[] args) {
        Player player = playerOnly(sender);
        if (player == null) return;
        DominionDTO dominion = getDominionNameArg_1(player, args);
        if (dominion == null) {
            Notification.error(sender, "你不在任何领地内，请指定领地名称 /dominion manage <领地名称>");
            return;
        }
        if (noAuthToManage(player, dominion)) return;
        Line size_info = Line.create()
                .append(Button.create("尺寸信息", "/dominion info " + dominion.getName()))
                .append("查看领地尺寸信息");
        Line flag_info = Line.create()
                .append(Button.create("权限设置", "/dominion flag_info " + dominion.getName()))
                .append("管理领地默认权限");
        Line privilege_list = Line.create()
                .append(Button.create("玩家权限", "/dominion privilege_list " + dominion.getName()))
                .append("管理玩家特权");
        View view = View.create();
        view.title("领地 " + dominion.getName() + " 管理界面")
                .navigator(Line.create()
                        .append(Button.create("主菜单", "/dominion menu"))
                        .append(Button.create("我的领地", "/dominion list"))
                        .append(dominion.getName()))
                .addLine(size_info)
                .addLine(flag_info)
                .addLine(privilege_list)
                .showOn(player);
    }
}
