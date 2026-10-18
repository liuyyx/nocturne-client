package dev.nocturne.ui.fatality;

import java.util.HashMap;
import java.util.Map;

/**
 * 界面文案的中文对照表：**只作用于显示**，不参与任何逻辑（模块名、值名仍用英文做键）。
 *
 * <p>为什么用查表而不是改数据源：模块名与参数名同时是注册表键、搜索键与持久化键，
 * 直接翻译会改掉这些契约（搜索、配置、映射表都会跟着错）。所以翻译只发生在绘制入口
 * （{@link FaDraw#text} 与它的三个对齐变体），数据侧一律不动。
 *
 * <p>正文与标签字体是 MiSans（含中文），所以中文不需要额外字体回退；Logo 仍是 Manrope
 * 的 {@code NOCTURNE} 品牌字，不翻译。
 *
 * <p>查不到返回原文：新加的模块/参数在补词条之前显示英文，不会变成空白。
 */
final class FaStrings {

    /** 英文 → 中文。键必须与数据侧一模一样（区分大小写）。 */
    private static final Map<String, String> ZH = new HashMap<String, String>();

    /** 中文 → 英文：搜索时让中文查询也能命中英文数据名。 */
    private static final Map<String, String> EN = new HashMap<String, String>();

    static {
        // 分类
        put("MOVEMENT", "移动");
        put("RENDER", "渲染");
        put("PLAYER", "玩家");
        put("MISC", "杂项");
        // 模块
        put("Sprint", "疾跑");
        put("FullBright", "夜视");
        put("Watermark", "水印");
        put("ESP", "透视框");
        put("NameTags", "名字标签");
        put("Tracers", "追踪线");
        put("Trajectories", "弹道");
        put("Chams", "穿墙高亮");
        put("StorageESP", "容器透视");
        put("ItemESP", "物品透视");
        put("Search", "方块搜索");
        put("AutoRespawn", "自动重生");
        put("Xray", "矿物透视");
        put("Panic", "紧急关闭");
        // 参数
        put("Enabled", "启用");
        put("Gamma", "亮度");
        put("Mode", "模式");
        put("Fade", "渐变");
        put("Color", "颜色");
        put("Opacity", "不透明度");
        put("Range", "范围");
        put("Distance", "距离");
        put("Max Distance", "最大距离");
        put("Name", "名称");
        put("Health", "血量");
        put("Invisibles", "隐身实体");
        put("Ignore Invisibles", "忽略隐身");
        put("Bounding Box", "包围盒");
        put("Player Color", "玩家颜色");
        put("Chest Color", "箱子颜色");
        put("Trapped Chest Color", "陷阱箱颜色");
        put("Ender Chest Color", "末影箱颜色");
        put("Hopper Color", "漏斗颜色");
        put("Furnace Color", "熔炉颜色");
        put("Dispenser Color", "发射器颜色");
        put("Dropper Color", "投掷器颜色");
        put("Shulker Color", "潜影盒颜色");
        put("Mob Color", "生物颜色");
        put("Animal Color", "动物颜色");
        put("Invisible Color", "隐身颜色");
        put("Aiming Color", "瞄准颜色");
        put("Color Behind Walls", "穿墙颜色");
        put("Color by distance", "按距离着色");
        put("Colored", "着色");
        put("Cave Mode", "洞穴模式");
        put("Only caves", "仅洞穴");
        put("Render Chests", "渲染箱子");
        put("Render Trapped Chests", "渲染陷阱箱");
        put("Render Enderchests", "渲染末影箱");
        put("Render Hopper", "渲染漏斗");
        put("Render Furnace", "渲染熔炉");
        put("Render Dispenser", "渲染发射器");
        put("Render Dropper", "渲染投掷器");
        put("Render Shulker", "渲染潜影盒");
        put("Render Players", "渲染玩家");
        put("Render Mobs", "渲染生物");
        put("Render Animals", "渲染动物");
        put("Player Distance Check", "玩家距离检测");
        put("Mob Distance Check", "生物距离检测");
        put("Animal Distance Check", "动物距离检测");
        put("Player Max Distance", "玩家最大距离");
        put("Mob Max Distance", "生物最大距离");
        put("Animal Max Distance", "动物最大距离");
        put("Hide Bots", "隐藏机器人");
        put("Highlight if focusing", "锁定高亮");
        put("Ghost Bow Charge", "拉弓残影");
        // 界面文案
        put("Preview", "预览");
        put("Preview unavailable", "预览不可用");
        put("Drag to turn", "拖动转向");
        put("Search modules...", "搜索模块…");
        put("Search...", "搜索…");
        put("Copy", "复制");
        put("Paste", "粘贴");
        put("Reset", "重置");
        put("Default", "默认");
        put("Close", "关闭");
        put("(empty)", "（空）");
        put("No results", "无结果");
        put("No modules", "无模块");
        put("Search modules and settings", "搜索模块与设置");
    }

    private FaStrings() {
    }

    /** 登记一条对照（同时建立反向索引）。 */
    private static void put(String english, String chinese) {
        ZH.put(english, chinese);
        EN.put(chinese, english);
    }

    /**
     * 把显示串翻成中文。
     *
     * @param text 原始（英文）显示串；{@code null}/空串原样返回
     * @return 中文；没有词条时返回原文
     */
    static String t(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String translated = ZH.get(text);
        return translated != null ? translated : text;
    }

    /**
     * 搜索用的候选名：给定用户输入，返回用于匹配的英文名（输入本身即英文时原样返回）。
     *
     * <p>搜索框里输入"疾跑"也要能找到 {@code Sprint}，否则中文界面下按名字搜索是坏的。
     *
     * @param query 用户输入
     * @return 用于匹配的串（中文输入时返回对应的英文名，否则返回输入本身）
     */
    static String searchKey(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        String english = EN.get(query);
        return english != null ? english : query;
    }
}
