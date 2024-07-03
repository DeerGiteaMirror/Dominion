package cn.lunadeer.dominion.controllers;

import cn.lunadeer.dominion.Cache;
import cn.lunadeer.dominion.Dominion;
import cn.lunadeer.dominion.dtos.DominionDTO;
import cn.lunadeer.dominion.dtos.PlayerDTO;
import cn.lunadeer.dominion.managers.GlobalTeleport;
import cn.lunadeer.minecraftpluginutils.Notification;
import cn.lunadeer.minecraftpluginutils.ParticleRender;
import cn.lunadeer.minecraftpluginutils.VaultConnect;
import cn.lunadeer.minecraftpluginutils.XLogger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.lunadeer.dominion.DominionNode.isInDominion;
import static cn.lunadeer.dominion.controllers.Apis.getPlayerCurrentDominion;
import static cn.lunadeer.dominion.controllers.Apis.notOwner;

public class DominionController {

    public static List<DominionDTO> all(Player owner) {
        return DominionDTO.selectAll(owner.getUniqueId());
    }

    /**
     * 创建领地
     *
     * @param operator 拥有者
     * @param name     领地名称
     * @param loc1     位置1
     * @param loc2     位置2
     */
    public static void create(AbstractOperator operator, String name, Location loc1, Location loc2) {
        DominionDTO parent = getPlayerCurrentDominion(operator);
        if (parent == null) {
            create(operator, name, loc1, loc2, "");
        } else {
            create(operator, name, loc1, loc2, parent.getName());
        }
    }

    /**
     * 创建子领地
     *
     * @param operator             拥有者
     * @param name                 领地名称
     * @param loc1                 位置1
     * @param loc2                 位置2
     * @param parent_dominion_name 父领地名称
     */
    public static void create(AbstractOperator operator, String name,
                              Location loc1, Location loc2,
                              String parent_dominion_name) {
        create(operator, name, loc1, loc2, parent_dominion_name, false);
    }

    /**
     * 创建子领地
     *
     * @param operator             拥有者
     * @param name                 领地名称
     * @param loc1                 位置1
     * @param loc2                 位置2
     * @param parent_dominion_name 父领地名称
     * @param skipEco              是否跳过经济检查
     */
    public static void create(AbstractOperator operator, String name,
                              Location loc1, Location loc2,
                              String parent_dominion_name, boolean skipEco) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "创建领地失败");
        AbstractOperator.Result SUCCESS = new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功创建领地 %s", name);
        if (name.isEmpty()) {
            operator.setResponse(FAIL.addMessage("领地名称不能为空"));
            return;
        }
        if (name.contains(" ") || name.contains(".")) {
            operator.setResponse(FAIL.addMessage("领地名称不能包含空格或点"));
            return;
        }
        if (DominionDTO.select(name) != null) {
            operator.setResponse(FAIL.addMessage("已经存在名称为 %s 的领地", name));
            return;
        }
        if (!loc1.getWorld().equals(loc2.getWorld())) {
            operator.setResponse(FAIL.addMessage("选点世界不一致"));
            return;
        }
        // 检查世界是否可以创建
        if (worldNotValid(operator, loc1.getWorld().getName())) {
            operator.setResponse(FAIL.addMessage("禁止在世界 %s 创建领地", loc1.getWorld().getName()));
            return;
        }
        // 检查领地数量是否达到上限
        if (amountNotValid(operator)) {
            operator.setResponse(FAIL.addMessage("你的领地数量已达上限(%d个)", Dominion.config.getLimitAmount()));
            return;
        }
        // 检查领地大小是否合法
        if (sizeNotValid(operator,
                loc1.getBlockX(), loc1.getBlockY(), loc1.getBlockZ(),
                loc2.getBlockX(), loc2.getBlockY(), loc2.getBlockZ())) {
            return;
        }
        DominionDTO dominion = new DominionDTO(operator.getUniqueId(), name, loc1.getWorld().getName(),
                (int) Math.min(loc1.getX(), loc2.getX()), (int) Math.min(loc1.getY(), loc2.getY()),
                (int) Math.min(loc1.getZ(), loc2.getZ()), (int) Math.max(loc1.getX(), loc2.getX()),
                (int) Math.max(loc1.getY(), loc2.getY()), (int) Math.max(loc1.getZ(), loc2.getZ()));
        DominionDTO parent_dominion;
        if (parent_dominion_name.isEmpty()) {
            parent_dominion = DominionDTO.select(-1);
        } else {
            parent_dominion = DominionDTO.select(parent_dominion_name);
        }
        if (parent_dominion == null) {
            operator.setResponse(FAIL.addMessage("父领地 %s 不存在", parent_dominion_name));
            if (parent_dominion_name.isEmpty()) {
                XLogger.err("根领地丢失！");
            }
            return;
        }
        // 是否是父领地的拥有者
        if (parent_dominion.getId() != -1) {
            if (notOwner(operator, parent_dominion)) {
                operator.setResponse(FAIL.addMessage("你不是父领地 %s 的拥有者，无法创建子领地", parent_dominion_name));
                return;
            }
        }
        // 如果parent_dominion不为-1 检查是否在同一世界
        if (parent_dominion.getId() != -1 && !parent_dominion.getWorld().equals(dominion.getWorld())) {
            operator.setResponse(FAIL.addMessage("父领地与子领地不在同一世界。"));
            return;
        }
        // 检查深度是否达到上限
        if (depthNotValid(operator, parent_dominion)) {
            return;
        }
        // 检查是否超出父领地范围
        if (!isContained(dominion, parent_dominion)) {
            operator.setResponse(FAIL.addMessage("超出父领地 %s 范围", parent_dominion.getName()));
            return;
        }
        // 获取此领地的所有同级领地
        List<DominionDTO> sub_dominions = DominionDTO.selectByParentId(dominion.getWorld(), parent_dominion.getId());
        // 检查是否与其他子领地冲突
        for (DominionDTO sub_dominion : sub_dominions) {
            if (isIntersect(sub_dominion, dominion)) {
                operator.setResponse(FAIL.addMessage("与领地 %s 冲突", sub_dominion.getName()));
                return;
            }
        }
        // 检查经济
        handleEconomy(operator, Dominion.config.getEconomyOnlyXZ() ? dominion.getSquare() : dominion.getVolume(), true, FAIL, SUCCESS);
        dominion = DominionDTO.insert(dominion);
        if (dominion == null) {
            operator.setResponse(FAIL.addMessage("创建领地失败，数据库错误，请联系管理员"));
            return;
        }
        // 显示粒子效果
        handleParticle(operator, dominion.getWorld(), dominion.getX1(), dominion.getY1(), dominion.getZ1(), dominion.getX2(), dominion.getY2(), dominion.getZ2(), FAIL);
        dominion.setParentDomId(parent_dominion.getId());
        operator.setResponse(SUCCESS);
    }

    /**
     * 向一个方向扩展领地
     * 会尝试对操作者当前所在的领地进行操作，当操作者不在一个领地内或者在子领地内时
     * 需要手动指定要操作的领地名称
     *
     * @param operator 操作者
     * @param size     扩展的大小
     */
    public static void expand(AbstractOperator operator, Integer size) {
        DominionDTO dominion = getPlayerCurrentDominion(operator);
        if (dominion == null) {
            operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "无法获取你所处的领地，请指定名称"));
            return;
        }
        expand(operator, size, dominion.getName());
    }

    /**
     * 向一个方向扩展领地
     *
     * @param operator      操作者
     * @param size          扩展的大小
     * @param dominion_name 领地名称
     */
    public static void expand(AbstractOperator operator, Integer size, String dominion_name) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "扩展领地失败");
        DominionDTO dominion = expandContractPreCheck(operator, getExistDomAndIsOwner(operator, dominion_name), FAIL);
        if (dominion == null) {
            return;
        }
        int[] newCords = expandContractSizeChange(operator, dominion, true, size, FAIL);
        if (newCords == null) {
            return;
        }
        // 校验是否超出父领地范围
        DominionDTO parent_dominion = DominionDTO.select(dominion.getParentDomId());
        if (parent_dominion == null) {
            operator.setResponse(FAIL.addMessage("父领地丢失"));
            return;
        }
        if (!isContained(newCords, parent_dominion)) {
            operator.setResponse(FAIL.addMessage("超出父领地 %s 范围", parent_dominion.getName()));
            return;
        }
        // 获取同世界下的所有同级领地
        List<DominionDTO> exist_dominions = DominionDTO.selectByParentId(dominion.getWorld(), dominion.getParentDomId());
        for (DominionDTO exist_dominion : exist_dominions) {
            if (isIntersect(exist_dominion, newCords)) {
                // 如果是自己，跳过
                if (exist_dominion.getId().equals(dominion.getId())) continue;
                operator.setResponse(FAIL.addMessage("与领地 %s 冲突", exist_dominion.getName()));
                return;
            }
        }
        AbstractOperator.Result SUCCESS = new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功扩展领地 %s %d格", dominion_name, size);
        // 检查经济
        handleEconomy(operator, Dominion.config.getEconomyOnlyXZ() ? sqr(newCords) - dominion.getSquare() : vol(newCords) - dominion.getVolume()
                , false, FAIL, SUCCESS);
        // 显示粒子效果
        handleParticle(operator, dominion.getWorld(), newCords, FAIL);
        dominion.setXYZ(newCords);
        operator.setResponse(SUCCESS);
    }

    /**
     * 缩小领地
     * 会尝试对操作者当前所在的领地进行操作，当操作者不在一个领地内或者在子领地内时
     * 需要手动指定要操作的领地名称
     *
     * @param operator 操作者
     * @param size     缩小的大小
     */
    public static void contract(AbstractOperator operator, Integer size) {
        DominionDTO dominion = getPlayerCurrentDominion(operator);
        if (dominion == null) {
            operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "无法获取你所处的领地，请指定名称"));
            return;
        }
        contract(operator, size, dominion.getName());
    }

    /**
     * 缩小领地
     *
     * @param operator      操作者
     * @param size          缩小的大小
     * @param dominion_name 领地名称
     */
    public static void contract(AbstractOperator operator, Integer size, String dominion_name) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "缩小领地失败");
        DominionDTO dominion = expandContractPreCheck(operator, getExistDomAndIsOwner(operator, dominion_name), FAIL);
        if (dominion == null) {
            return;
        }
        int[] newCords = expandContractSizeChange(operator, dominion, false, size, FAIL);
        if (newCords == null) {
            return;
        }
        // 获取所有的子领地
        List<DominionDTO> sub_dominions = DominionDTO.selectByParentId(dominion.getWorld(), dominion.getId());
        for (DominionDTO sub_dominion : sub_dominions) {
            if (!isContained(sub_dominion, newCords)) {
                operator.setResponse(FAIL.addMessage("缩小后的领地无法包含子领地 %s", sub_dominion.getName()));
                return;
            }
        }
        AbstractOperator.Result SUCCESS = new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功缩小领地 %s %d格", dominion_name, size);
        // 退还经济
        handleEconomy(operator, Dominion.config.getEconomyOnlyXZ() ? dominion.getSquare() - sqr(newCords) : dominion.getVolume() - vol(newCords)
                , false, FAIL, SUCCESS);
        // 显示粒子效果
        handleParticle(operator, dominion.getWorld(), newCords, FAIL);
        dominion.setXYZ(newCords);
        operator.setResponse(SUCCESS);
    }

    private static int vol(int x1, int y1, int z1, int x2, int y2, int z2) {
        return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    private static int vol(int[] cords) {
        return vol(cords[0], cords[1], cords[2], cords[3], cords[4], cords[5]);
    }

    private static int sqr(int x1, int z1, int x2, int z2) {
        return (x2 - x1 + 1) * (z2 - z1 + 1);
    }

    private static int sqr(int[] cords) {
        return sqr(cords[0], cords[2], cords[3], cords[5]);
    }

    /**
     * 删除领地 会同时删除其所有子领地
     *
     * @param operator      操作者
     * @param dominion_name 领地名称
     * @param force         是否强制删除
     */
    public static void delete(AbstractOperator operator, String dominion_name, boolean force) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "删除领地失败");
        AbstractOperator.Result SUCCESS = new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "领地 %s 及其所有子领地已删除", dominion_name);
        DominionDTO dominion = getExistDomAndIsOwner(operator, dominion_name);
        if (dominion == null) {
            return;
        }
        if (dominion.getServerId() != GlobalTeleport.instance.getThisServerId()) {
            operator.setResponse(FAIL.addMessage("禁止跨服务器操作"));
            return;
        }
        List<DominionDTO> sub_dominions = getSubDominionsRecursive(dominion);
        if (!force) {
            AbstractOperator.Result WARNING = new AbstractOperator.Result(AbstractOperator.Result.WARNING, "删除领地 %s 会同时删除其所有子领地，是否继续？", dominion_name);
            showSubNamesWarning(sub_dominions, WARNING);
            if (operator instanceof BukkitPlayerOperator) {
                Notification.warn(operator.getPlayer(), "输入 /dominion delete %s force 确认删除", dominion_name);
            }
            operator.setResponse(WARNING);
            return;
        }
        DominionDTO.delete(dominion);
        // 退还经济
        int count = 0;
        if (Dominion.config.getEconomyOnlyXZ()) {
            for (DominionDTO sub_dominion : sub_dominions) {
                count += sub_dominion.getSquare();
            }
        } else {
            for (DominionDTO sub_dominion : sub_dominions) {
                count += sub_dominion.getVolume();
            }
        }
        handleEconomy(operator, count, false, FAIL, SUCCESS);
        operator.setResponse(SUCCESS);
    }

    /**
     * 设置领地的进入消息
     *
     * @param operator 操作者
     * @param message  消息
     */
    public static void setJoinMessage(AbstractOperator operator, String message) {
        DominionDTO dominion = getPlayerCurrentDominion(operator);
        if (dominion == null) {
            return;
        }
        setJoinMessage(operator, dominion.getName(), message);
    }

    /**
     * 设置进入领地的消息
     *
     * @param operator      操作者
     * @param dominion_name 领地名称
     * @param message       消息
     */
    public static void setJoinMessage(AbstractOperator operator, String message, String dominion_name) {
        DominionDTO dominion = getExistDomAndIsOwner(operator, dominion_name);
        if (dominion == null) {
            return;
        }
        dominion.setJoinMessage(message);
        operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功设置领地 %s 的进入消息", dominion_name));
    }

    /**
     * 设置领地的离开消息
     *
     * @param operator 操作者
     * @param message  消息
     */
    public static void setLeaveMessage(AbstractOperator operator, String message) {
        DominionDTO dominion = getPlayerCurrentDominion(operator);
        if (dominion == null) {
            return;
        }
        setLeaveMessage(operator, dominion.getName(), message);
    }

    /**
     * 设置离开领地的消息
     *
     * @param operator      操作者
     * @param dominion_name 领地名称
     * @param message       消息
     */
    public static void setLeaveMessage(AbstractOperator operator, String message, String dominion_name) {
        DominionDTO dominion = getExistDomAndIsOwner(operator, dominion_name);
        if (dominion == null) {
            return;
        }
        dominion.setLeaveMessage(message);
        operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功设置领地 %s 的离开消息", dominion_name));
    }

    /**
     * 设置领地的传送点
     *
     * @param operator 操作者
     */
    public static void setTpLocation(AbstractOperator operator, int x, int y, int z) {
        DominionDTO dominion = getPlayerCurrentDominion(operator);
        if (dominion == null) {
            return;
        }
        setTpLocation(operator, x, y, z, dominion.getName());
    }

    /**
     * 设置领地的传送点
     *
     * @param operator      操作者
     * @param dominion_name 领地名称
     */
    public static void setTpLocation(AbstractOperator operator, int x, int y, int z, String dominion_name) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "设置领地传送点失败");
        DominionDTO dominion = getExistDomAndIsOwner(operator, dominion_name);
        if (dominion == null) {
            operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "领地 %s 不存在", dominion_name));
            return;
        }
        if (dominion.getServerId() != GlobalTeleport.instance.getThisServerId()) {
            operator.setResponse(FAIL.addMessage("禁止跨服务器操作"));
            return;
        }
        World world = Dominion.instance.getServer().getWorld(dominion.getWorld());
        if (world == null) {
            operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "世界 %s 不存在", dominion.getWorld()));
            return;
        }
        Location loc = new Location(world, x, y, z);
        // 检查是否在领地内
        if (isInDominion(dominion, loc)) {
            loc.setY(loc.getY() + 1.5);
            dominion.setTpLocation(loc);
            operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.SUCCESS,
                    "成功设置领地 %s 的传送点 %d %d %d", dominion_name
                    , loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        } else {
            operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "传送点不在领地 %s 内", dominion_name));
        }
    }

    /**
     * 重命名领地
     *
     * @param operator 操作者
     * @param old_name 旧名称
     * @param new_name 新名称
     */
    public static void rename(AbstractOperator operator, String old_name, String new_name) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "重命名领地失败");
        if (new_name.isEmpty()) {
            operator.setResponse(FAIL.addMessage("新名称不能为空"));
            return;
        }
        if (new_name.contains(" ") || new_name.contains(".")) {
            operator.setResponse(FAIL.addMessage("领地名称不能包含空格或点"));
            return;
        }
        if (Objects.equals(old_name, new_name)) {
            operator.setResponse(FAIL.addMessage("新名称与旧名称相同"));
            return;
        }
        DominionDTO dominion = getExistDomAndIsOwner(operator, old_name);
        if (dominion == null) {
            return;
        }
        if (DominionDTO.select(new_name) != null) {
            operator.setResponse(FAIL.addMessage("已经存在名称为 %s 的领地", new_name));
            return;
        }
        dominion.setName(new_name);
        operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功将领地 %s 重命名为 %s", old_name, new_name));
    }

    /**
     * 转让领地
     *
     * @param operator    操作者
     * @param dom_name    领地名称
     * @param player_name 玩家名称
     * @param force       是否强制转让
     */
    public static void give(AbstractOperator operator, String dom_name, String player_name, boolean force) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "转让领地失败");
        PlayerDTO operatorDTO = PlayerDTO.select(operator.getUniqueId());
        if (operatorDTO == null) {
            operator.setResponse(FAIL.addMessage("操作者信息丢失，请联系管理员"));
            return;
        }
        if (Objects.equals(player_name, operatorDTO.getLastKnownName())) {
            operator.setResponse(FAIL.addMessage("不能将领地转让给自己"));
            return;
        }
        DominionDTO dominion = getExistDomAndIsOwner(operator, dom_name);
        if (dominion == null) {
            return;
        }
        PlayerDTO player = PlayerController.getPlayerDTO(player_name);
        if (player == null) {
            operator.setResponse(FAIL.addMessage("玩家 %s 不存在", player_name));
            return;
        }
        if (dominion.getParentDomId() != -1) {
            operator.setResponse(FAIL.addMessage("子领地无法转让，你可以通过将 %s 设置为管理员来让其管理领地 %s ", player_name, dom_name));
            return;
        }
        List<DominionDTO> sub_dominions = getSubDominionsRecursive(dominion);
        if (!force) {
            AbstractOperator.Result WARNING = new AbstractOperator.Result(AbstractOperator.Result.WARNING, "转让领地 %s 给 %s 会同时转让其所有子领地，是否继续？", dom_name, player_name);
            showSubNamesWarning(sub_dominions, WARNING);
            if (operator instanceof BukkitPlayerOperator) {
                Notification.warn(operator.getPlayer(), "输入 /dominion give %s %s force 确认转让", dom_name, player_name);
            }
            operator.setResponse(WARNING);
            return;
        }
        dominion.setOwner(player.getUuid());
        for (DominionDTO sub_dominion : sub_dominions) {
            sub_dominion.setOwner(player.getUuid());
        }
        operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功将领地 %s 及其所有子领地转让给 %s", dom_name, player_name));
    }

    /**
     * 设置领地的卫星地图地块颜色
     *
     * @param operator 操作者
     * @param color    16进制颜色 例如 #ff0000
     * @param dom_name 领地名称
     */
    public static void setMapColor(AbstractOperator operator, String color, String dom_name) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "设置领地地图颜色失败");
        DominionDTO dominion = getExistDomAndIsOwner(operator, dom_name);
        if (dominion == null) {
            return;
        }
        if (notOwner(operator, dominion)) {
            operator.setResponse(FAIL.addMessage("你不是领地 %s 的拥有者", dom_name));
            return;
        }
        color = color.toUpperCase();    // 转换为大写
        if (!color.matches("^#[0-9a-fA-F]{6}$")) {
            operator.setResponse(FAIL.addMessage("颜色格式不正确"));
            return;
        }
        dominion.setColor(color);
        operator.setResponse(new AbstractOperator.Result(AbstractOperator.Result.SUCCESS, "成功设置领地 %s 的卫星地图颜色为 %s", dom_name, color));
    }

    /**
     * 设置领地的卫星地图地块颜色
     *
     * @param operator 操作者
     * @param color    16进制颜色 例如 #ff0000
     */
    public static void setMapColor(AbstractOperator operator, String color) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "设置领地地图颜色失败");
        DominionDTO dominion = getPlayerCurrentDominion(operator);
        if (dominion == null) {
            operator.setResponse(FAIL.addMessage("无法获取你所处的领地，请指定名称"));
            return;
        }
        setMapColor(operator, color, dominion.getName());
    }

    /**
     * 判断两个领地是否相交
     */
    private static boolean isIntersect(DominionDTO a, DominionDTO b) {
        return a.getX1() < b.getX2() && a.getX2() > b.getX1() &&
                a.getY1() < b.getY2() && a.getY2() > b.getY1() &&
                a.getZ1() < b.getZ2() && a.getZ2() > b.getZ1();
    }

    private static boolean isIntersect(DominionDTO a, Integer x1, Integer y1, Integer z1, Integer x2, Integer y2, Integer z2) {
        return a.getX1() < x2 && a.getX2() > x1 &&
                a.getY1() < y2 && a.getY2() > y1 &&
                a.getZ1() < z2 && a.getZ2() > z1;
    }

    private static boolean isIntersect(DominionDTO a, int[] cord) {
        return isIntersect(a, cord[0], cord[1], cord[2], cord[3], cord[4], cord[5]);
    }

    /**
     * 判断 sub 是否完全被 parent 包裹
     */
    private static boolean isContained(DominionDTO sub, DominionDTO parent) {
        if (parent.getId() == -1) {
            return true;
        }
        return isContained(sub.getX1(), sub.getY1(), sub.getZ1(), sub.getX2(), sub.getY2(), sub.getZ2(), parent.getX1(), parent.getY1(), parent.getZ1(), parent.getX2(), parent.getY2(), parent.getZ2());
    }

    private static boolean isContained(int[] cords, DominionDTO parent) {
        return isContained(cords[0], cords[1], cords[2], cords[3], cords[4], cords[5], parent);
    }

    private static boolean isContained(DominionDTO sub, int[] cords) {
        return isContained(sub, cords[0], cords[1], cords[2], cords[3], cords[4], cords[5]);
    }

    private static boolean isContained(Integer x1, Integer y1, Integer z1, Integer x2, Integer y2, Integer z2, DominionDTO parent) {
        if (parent.getId() == -1) {
            return true;
        }
        return isContained(x1, y1, z1, x2, y2, z2, parent.getX1(), parent.getY1(), parent.getZ1(), parent.getX2(), parent.getY2(), parent.getZ2());
    }

    private static boolean isContained(DominionDTO sub, Integer x1, Integer y1, Integer z1, Integer x2, Integer y2, Integer z2) {
        return isContained(sub.getX1(), sub.getY1(), sub.getZ1(), sub.getX2(), sub.getY2(), sub.getZ2(), x1, y1, z1, x2, y2, z2);
    }

    private static boolean isContained(int sub_x1, int sub_y1, int sub_z1, int sub_x2, int sub_y2, int sub_z2, int parent_x1, int parent_y1, int parent_z1, int parent_x2, int parent_y2, int parent_z2) {
        return sub_x1 >= parent_x1 && sub_x2 <= parent_x2 &&
                sub_y1 >= parent_y1 && sub_y2 <= parent_y2 &&
                sub_z1 >= parent_z1 && sub_z2 <= parent_z2;
    }

    private static List<DominionDTO> getSubDominionsRecursive(DominionDTO dominion) {
        List<DominionDTO> sub_dominions = DominionDTO.selectByParentId(dominion.getWorld(), dominion.getId());
        List<DominionDTO> sub_sub_dominions = new ArrayList<>();
        for (DominionDTO sub_dominion : sub_dominions) {
            sub_sub_dominions.addAll(getSubDominionsRecursive(sub_dominion));
        }
        sub_dominions.addAll(sub_sub_dominions);
        return sub_dominions;
    }

    private static boolean sizeNotValid(AbstractOperator operator, int[] cords) {
        return sizeNotValid(operator, cords[0], cords[1], cords[2], cords[3], cords[4], cords[5]);
    }

    private static boolean sizeNotValid(AbstractOperator operator, int x1, int y1, int z1, int x2, int y2, int z2) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "尺寸不合法");
        if (operator.isOp() && Dominion.config.getLimitOpBypass()) {
            return false;
        }
        // 如果 1 > 2 则交换
        if (x1 > x2) {
            int temp = x1;
            x1 = x2;
            x2 = temp;
        }
        if (y1 > y2) {
            int temp = y1;
            y1 = y2;
            y2 = temp;
        }
        if (z1 > z2) {
            int temp = z1;
            z1 = z2;
            z2 = temp;
        }
        int x_length = x2 - x1;
        int y_length = y2 - y1;
        int z_length = z2 - z1;
        if (x_length < 4 || y_length < 4 || z_length < 4) {
            operator.setResponse(FAIL.addMessage("领地的任意一边长度不得小于4"));
            return true;
        }
        if (x_length > Dominion.config.getLimitSizeX() && Dominion.config.getLimitSizeX() > 0) {
            operator.setResponse(FAIL.addMessage("领地X方向长度不能超过 %d", Dominion.config.getLimitSizeX()));
            return true;
        }
        if (y_length > Dominion.config.getLimitSizeY() && Dominion.config.getLimitSizeY() > 0) {
            operator.setResponse(FAIL.addMessage("领地Y方向高度不能超过 %d", Dominion.config.getLimitSizeY()));
            return true;
        }
        if (z_length > Dominion.config.getLimitSizeZ() && Dominion.config.getLimitSizeZ() > 0) {
            operator.setResponse(FAIL.addMessage("领地Z方向长度不能超过 %d", Dominion.config.getLimitSizeZ()));
            return true;
        }
        if (y2 > Dominion.config.getLimitMaxY()) {
            operator.setResponse(FAIL.addMessage("领地Y坐标不能超过 %d", Dominion.config.getLimitMaxY()));
            return true;
        }
        if (y1 < Dominion.config.getLimitMinY()) {
            operator.setResponse(FAIL.addMessage("领地Y坐标不能低于 %d", Dominion.config.getLimitMinY()));
            return true;
        }
        return false;
    }

    private static boolean depthNotValid(AbstractOperator operator, DominionDTO parent_dom) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "子领地深度不合法");
        if (operator.isOp() && Dominion.config.getLimitOpBypass()) {
            return false;
        }
        if (Dominion.config.getLimitDepth() == -1) {
            return false;
        }
        if (parent_dom.getId() != -1 && Dominion.config.getLimitDepth() == 0) {
            operator.setResponse(FAIL.addMessage("不允许创建子领地"));
            return true;
        }
        if (parent_dom.getId() == -1) {
            return false;
        }
        int level = 0;
        while (parent_dom.getParentDomId() != -1) {
            parent_dom = Cache.instance.getDominion(parent_dom.getParentDomId());
            level++;
        }
        if (level >= Dominion.config.getLimitDepth()) {
            operator.setResponse(FAIL.addMessage("子领地嵌套深度不能超过 %s", Dominion.config.getLimitDepth()));
            return true;
        }
        return false;
    }

    private static boolean amountNotValid(AbstractOperator operator) {
        if (operator.isOp() && Dominion.config.getLimitOpBypass()) {
            return false;
        }
        return Cache.instance.getPlayerDominionCount(operator.getUniqueId()) >= Dominion.config.getLimitAmount() && Dominion.config.getLimitAmount() != -1;
    }

    private static boolean worldNotValid(AbstractOperator operator, String world) {
        if (operator.isOp() && Dominion.config.getLimitOpBypass()) {
            return false;
        }
        return Dominion.config.getWorldBlackList().contains(world);
    }

    private static DominionDTO getExistDomAndIsOwner(AbstractOperator operator, String dominion_name) {
        AbstractOperator.Result FAIL = new AbstractOperator.Result(AbstractOperator.Result.FAILURE, "");
        DominionDTO dominion = DominionDTO.select(dominion_name);
        if (dominion == null) {
            operator.setResponse(FAIL.addMessage("领地 %s 不存在", dominion_name));
            return null;
        }
        if (notOwner(operator, dominion)) {
            operator.setResponse(FAIL.addMessage("你不是领地 %s 的拥有者", dominion_name));
            return null;
        }
        return dominion;
    }

    /**
     * 处理经济系统
     *
     * @param operator 操作者
     * @param count    数量
     * @param paid     操作类型 true 为扣费 false 为退费
     * @param FAIL     失败消息
     * @param SUCCESS  成功消息
     */
    private static void handleEconomy(AbstractOperator operator, Integer count, boolean paid, AbstractOperator.Result FAIL, AbstractOperator.Result SUCCESS) {
        if (Dominion.config.getEconomyEnable()) {
            if (!VaultConnect.instance.economyAvailable()) {
                operator.setResponse(FAIL.addMessage("没有可用的经济插件系统，请联系服主。"));
                return;
            }
            float priceOrRefund = count * Dominion.config.getEconomyPrice();
            if (paid) {
                if (VaultConnect.instance.getBalance(operator.getPlayer()) < priceOrRefund) {
                    operator.setResponse(FAIL.addMessage("你的余额不足，需要 %.2f %s", priceOrRefund, VaultConnect.instance.currencyNamePlural()));
                    return;
                }
                SUCCESS.addMessage("已扣除 %.2f %s", priceOrRefund, VaultConnect.instance.currencyNamePlural());
                VaultConnect.instance.withdrawPlayer(operator.getPlayer(), priceOrRefund);
            } else {
                float refund = priceOrRefund * Dominion.config.getEconomyRefund();
                VaultConnect.instance.depositPlayer(operator.getPlayer(), refund);
                SUCCESS.addMessage("已退还 %.2f %s", refund, VaultConnect.instance.currencyNamePlural());
            }
        }
    }

    /**
     * 显示粒子效果
     *
     * @param operator  操作者
     * @param worldName 世界名称
     * @param x1        x1
     * @param y1        y1
     * @param z1        z1
     * @param x2        x2
     * @param y2        y2
     * @param z2        z2
     * @param FAIL      失败消息
     */
    private static void handleParticle(AbstractOperator operator, String worldName, Integer x1, Integer y1, Integer z1, Integer x2, Integer y2, Integer z2, AbstractOperator.Result FAIL) {
        if (operator instanceof BukkitPlayerOperator) {
            World world = Dominion.instance.getServer().getWorld(worldName);
            if (world == null) {
                operator.setResponse(FAIL.addMessage("世界 %s 不存在", worldName));
                return;
            }
            ParticleRender.showBoxFace(Dominion.instance, operator.getPlayer(),
                    new Location(world, x1, y1, z1),
                    new Location(world, x2, y2, z2));
        }
    }

    private static void handleParticle(AbstractOperator operator, String worldName, int[] cords, AbstractOperator.Result FAIL) {
        handleParticle(operator, worldName, cords[0], cords[1], cords[2], cords[3], cords[4], cords[5], FAIL);
    }

    private static @Nullable DominionDTO expandContractPreCheck(AbstractOperator operator, @Nullable DominionDTO dominion, AbstractOperator.Result FAIL) {
        if (dominion == null) {
            return null;
        }
        if (operator.getLocation() == null) {
            operator.setResponse(FAIL.addMessage("无法获取你的位置"));
            return null;
        }
        if (!operator.getLocation().getWorld().getName().equals(dominion.getWorld())) {
            operator.setResponse(FAIL.addMessage("禁止跨世界操作"));
            return null;
        }
        if (dominion.getServerId() != GlobalTeleport.instance.getThisServerId()) {
            operator.setResponse(FAIL.addMessage("禁止跨服务器操作"));
            return null;
        }
        if (!isInDominion(dominion, operator.getLocation())) {
            operator.setResponse(FAIL.addMessage("你不在领地 %s 内，无法执行此操作", dominion.getName()));
            return null;
        }
        return dominion;
    }

    private static int[] expandContractSizeChange(AbstractOperator operator, @NotNull DominionDTO dominion, boolean expand, int size, AbstractOperator.Result FAIL) {
        BlockFace face = operator.getDirection();
        int[] result = new int[6];
        result[0] = dominion.getX1();
        result[1] = dominion.getY1();
        result[2] = dominion.getZ1();
        result[3] = dominion.getX2();
        result[4] = dominion.getY2();
        result[5] = dominion.getZ2();
        if (!expand) {
            size = size * -1;
        }
        switch (face) {
            case NORTH:
                result[2] -= size;
                break;
            case SOUTH:
                result[5] += size;
                break;
            case WEST:
                result[0] -= size;
                break;
            case EAST:
                result[3] += size;
                break;
            case UP:
                result[4] += size;
                break;
            case DOWN:
                result[1] -= size;
                break;
            default:
                operator.setResponse(FAIL.addMessage("无效的方向"));
                return null;
        }
        if (!expand) {
            // 校验第二组坐标是否小于第一组坐标
            if (result[0] > result[3] || result[1] > result[4] || result[2] > result[5]) {
                operator.setResponse(FAIL.addMessage("缩小后的领地大小无效"));
                return null;
            }
        }
        if (sizeNotValid(operator, result)) {
            return null;
        }
        return result;
    }


    /**
     * 以警告形式打印所有子领地名称
     *
     * @param sub_dominions 子领地列表
     * @param WARNING       警告消息
     */
    public static void showSubNamesWarning(List<DominionDTO> sub_dominions, AbstractOperator.Result WARNING) {
        String sub_names = "";
        for (DominionDTO sub_dominion : sub_dominions) {
            sub_names = sub_dominion.getName() + ", ";
        }
        if (sub_dominions.size() > 0) {
            sub_names = sub_names.substring(0, sub_names.length() - 2);
            WARNING.addMessage("(子领地：%s)", sub_names);
        }
    }

}
