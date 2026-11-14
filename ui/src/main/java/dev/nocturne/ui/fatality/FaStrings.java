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

    /** 是否输出中文（默认中文；界面上可切换，并持久化到 clickgui.properties）。 */
    private static volatile boolean chinese = true;

    /** 英文 → 中文。键必须与数据侧一模一样（区分大小写）。 */
    private static final Map<String, String> ZH = new HashMap<String, String>();

    /** 中文 → 英文：搜索时让中文查询也能命中英文数据名。 */
    private static final Map<String, String> EN = new java.util.concurrent.ConcurrentHashMap<String, String>();

    /** 通用术语，用于组合设置名；键统一为小写。 */
    private static final Map<String, String> TERMS = new HashMap<String, String>();

    /** 组合短语只在首次显示时分解，避免每帧重复做驼峰切词。 */
    private static final Map<String, String> CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    static {
        // 分类
        put("MOVEMENT", "移动");
        put("RENDER", "渲染");
        put("PLAYER", "玩家");
        put("MISC", "杂项");
        put("COMBAT", "战斗");
        put("WORLD", "世界");
        put("INVENTORY", "背包");
        // 模块
        put("Sprint", "疾跑");
        put("FullBright", "夜视");
        put("Watermark", "水印");
        put("ESP", "透视框");
        put("ESP 2D", "二维透视");
        put("NameTags", "名字标签");
        put("Name Tags", "名字标签");
        put("Tracers", "追踪线");
        put("Trajectories", "弹道");
        put("Chams", "穿墙高亮");
        put("StorageESP", "容器透视");
        put("SpawnerFinder", "刷怪笼查找");
        put("ItemESP", "物品透视");
        put("Search", "方块搜索");
        put("AutoRespawn", "自动重生");
        put("Xray", "矿物透视");
        put("Panic", "紧急关闭");
        put("AutoClicker", "自动点击");
        put("KeepSprint", "保持疾跑");
        put("AimAssist", "瞄准辅助");
        put("KillAura", "击杀光环");
        put("Scaffold", "自动搭路");
        put("FastPlace", "快速放置");
        put("AutoTool", "自动选工具");
        put("NoFall", "无摔落伤害");
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
        put("Barrel", "木桶");
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
        put("Medium", "中");
        put("Highest", "最高");
        // 新移植模块的参数
        put("CPS", "每秒点击");
        put("Randomization", "随机化");
        put("Normal", "普通");
        put("Extra", "额外");
        put("Extra+", "额外+");
        put("Jitter", "抖动");
        put("Hold to click", "按住点击");
        put("Trigger mode", "触发模式");
        put("Cancel Invis", "隐身时取消");
        put("Require mouse down", "需按住鼠标");
        put("Aim vertically", "垂直瞄准");
        put("Players", "玩家");
        put("Mobs", "生物");
        put("Horizontal speed", "水平速度");
        put("Vertical speed", "垂直速度");
        put("Max angle", "最大角度");
        put("Target mode", "目标模式");
        put("Yaw", "偏航角");
        put("Swing range", "挥击距离");
        put("Attack range", "攻击距离");
        put("Attacks per Second", "每秒攻击次数");
        put("Max targets", "最大目标数");
        put("Rotate", "自动转向");
        put("Delay", "延迟");
        put("Held Item", "手持物品");
        put("ALL", "全部");
        put("BLOCKS", "方块");
        put("PROJECTILES", "投掷物");
        put("Instant swap", "立即切换");
        put("Swap to delay", "切换延迟");
        put("Swap back", "切回原槽");
        put("Swap back delay", "切回延迟");
        put("Only while sneaking", "仅潜行时");
        put("Show distance", "显示距离");
        put("Spawner names-whitelist", "刷怪笼名称白名单");
        put("Player", "玩家");
        put("Hit Slow Down During Velocity", "受击减速（击退时）");
        put("Hit Slow Down Normal", "受击减速（普通）");
        put("Buffer Decrease", "缓冲衰减");
        put("Max Buffer", "最大缓冲");
        put("Velocity Hit Sprint", "击退命中疾跑");
        put("Normal Hit Sprint", "普通命中疾跑");
        put("Buffer Abuse", "缓冲滥用");
        put("Only In Air", "仅空中");
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
        put("Whitelist Only", "仅白名单");
        put("Trajectory Color", "轨迹颜色");
        put("Target Color", "目标颜色");
        put("Use tracers", "使用追踪线");
        put("Visible Color", "可见颜色");
        put("Render Trapped Chests", "渲染陷阱箱");
        put("No modules", "无模块");
        put("Enter a world first", "请先进入世界");
        put("Search modules and settings", "搜索模块与设置");
        registerModules();
        registerTerms();
        registerSpecialLabels();
    }

    /** 无法靠通用词分解的模型、算法与历史兼容名称。 */
    private static void registerSpecialLabels() {
        put("19KC8KP", "19KC8KP 模型");
        put("21KC11KP", "21KC11KP 模型");
        put("1st Person", "第一人称");
        put("3rd Person", "第三人称");
        put("2B2T", "2B2T 服务器");
        put("Aca", "ACA 算法");
        put("Alice", "爱丽丝");
        put("Astolfo", "Astolfo 风格");
        put("Boqijiang", "波奇酱");
        put("CCClip", "CC 相机裁剪");
        put("CaptureMark", "捕获标记");
        put("Deobf", "去混淆");
        put("Elowen", "Elowen 模式");
        put("Euabe", "Euabe 风格");
        put("GodBridge", "神桥");
        put("Grim", "Grim 反作弊");
        put("Heypixel", "Heypixel 模式");
        put("Hypixel", "Hypixel 模式");
        put("KeepY", "保持高度");
        put("Mengcha", "梦茶");
        put("NCP", "NCP 反作弊");
        put("Nya", "喵系");
        put("OpenYsm", "开放 YSM");
        put("Otkisuli", "Otkisuli 风格");
        put("Polar", "Polar 算法");
        put("Sexy", "华丽");
        put("SnowFlake", "雪花");
        put("Sy", "Sy 风格");
        put("ToyotaAe86", "丰田 AE86");
        put("UwU", "可爱风格");
        put("V1_7", "1.7 版本");
        put("VClip", "垂直移动");
        put("XIN_2B2T", "XIN 2B2T");
        put("Yatuoya", "亚托莉");
        put("Flux", "Flux 风格");
        put("NV", "夜视");
        put("TXT", "纯文本");
    }

    /** 全部内置模块的精确译名；技术缩写保留，但名称本身必须中文化。 */
    private static void registerModules() {
        put("Client Setting", "客户端设置");
        put("Aim Bot", "自动瞄准");
        put("Anchor Blast", "重生锚爆破");
        put("Anti Bot", "反机器人");
        put("Backtrack", "回溯");
        put("Auto Clicker", "自动点击");
        put("Auto Dtap", "自动双击");
        put("Auto Hit Crystal", "自动攻击水晶");
        put("Auto Mend", "自动修复");
        put("Auto Throw", "自动投掷");
        put("Auto Totem", "自动图腾");
        put("Auto Weapon", "自动武器");
        put("Criticals", "自动暴击");
        put("Crystal Aura", "水晶光环");
        put("Crystal Blocker", "水晶阻挡");
        put("Feet Trap", "脚下困住");
        put("Double Anchor", "双重生锚");
        put("Elytra Combat", "鞘翅战斗");
        put("Fake Lag", "假延迟");
        put("Hover Totem", "悬停图腾");
        put("Kill Aura", "击杀光环");
        put("Key Pearl", "快捷珍珠");
        put("Mace Aura", "重锤光环");
        put("Multi Aura", "多重光环");
        put("Packet Mine", "数据包挖掘");
        put("Safe Anchor", "安全重生锚");
        put("Safe Crystal", "安全水晶");
        put("Silent Aim", "静默瞄准");
        put("Spear Kill", "长矛击杀");
        put("Trigger Bot", "准星攻击");
        put("Zealot Crystal Plus", "狂热水晶增强");
        put("Keep Sprint", "保持疾跑");
        put("RightClicker", "自动右键");
        put("Velocity", "击退控制");
        put("Jump Reset", "跳跃重置");
        put("KnockbackDelay", "击退延迟");
        put("Reach", "攻击距离");
        put("WTap", "疾跑重置");
        put("AimAssist", "瞄准辅助");
        put("HitBoxes", "命中箱扩展");
        put("HitSelect", "受击选择");
        put("BlockHit", "格挡攻击");
        put("SilentAura", "静默光环");
        put("auraClicker", "光环自动点击");
        put("HitFlick", "命中甩头");
        put("HitSwap", "命中切换");
        put("AutoMace", "自动重锤");
        put("AutoAnchor", "自动重生锚");
        put("ShieldBreaker", "破盾");
        put("AntiFireball", "反火球");
        put("Auto Armor", "自动盔甲");
        put("Auto Crossbow Release", "自动弩释放");
        put("Auto Firework", "自动烟花");
        put("Auto KouZi", "自动扣字");
        put("Auto Ban", "自动封禁");
        put("Auto MLG", "自动落地自救");
        put("Auto Tool", "自动工具");
        put("Bed Nuker", "自动拆床");
        put("Break Cooldown", "挖掘冷却");
        put("Chest Aura", "箱子交互光环");
        put("Disabler", "防护绕过");
        put("Elytra Swap", "鞘翅切换");
        put("Fake Player", "假人");
        put("Ghost Hand", "隔空交互");
        put("Input Disabler", "输入禁用");
        put("Inv Manager", "背包管理");
        put("Jump Cooldown", "跳跃冷却");
        put("Key Friend", "快捷好友");
        put("Multi Task", "多任务交互");
        put("NoFall", "无摔落伤害");
        put("No Rotate", "反强制转向");
        put("Packet Eat", "数据包进食");
        put("Player Alarms", "玩家警报");
        put("Server Guard", "服务器防护");
        put("Sound FX", "音效");
        put("Stealer", "容器窃取");
        put("Timer", "游戏加速");
        put("Use Cooldown", "使用冷却");
        put("Auto Queue", "自动排队");
        put("Elytra Fly", "鞘翅飞行");
        put("Elytra Grim Acc", "Grim 鞘翅加速");
        put("Dolphin", "海豚游动");
        put("Auto Sprint", "自动疾跑");
        put("Auto Build", "自动建造");
        put("Blink", "闪现");
        put("Eagle", "边缘潜行");
        put("Auto Map", "自动地图");
        put("Flight", "飞行");
        put("GUI Move", "界面内移动");
        put("Hole Snap", "洞口吸附");
        put("Jump Reset", "跳跃重置");
        put("Movement Fix", "移动修正");
        put("No Slowdown", "无使用减速");
        put("Phase", "穿墙");
        put("Reverse Step", "快速下落");
        put("Safe Walk", "安全行走");
        put("Scaffold", "自动搭路");
        put("Great Wall", "长城搭路");
        put("Speed", "速度");
        put("Step", "自动上台阶");
        put("Strafe", "空中加速");
        put("Stuck", "原地卡住");
        put("Target Strafe", "绕目标移动");
        put("Telly", "跳跃搭路");
        put("Kill Effect", "击杀特效");
        put("Anti Alias", "抗锯齿");
        put("Aspect Ratio", "画面比例");
        put("Better Chat", "聊天增强");
        put("Better Scoreboard", "计分板增强");
        put("Block ESP", "方块透视");
        put("Block Highlight", "方块高亮");
        put("Camera Clip", "相机穿墙");
        put("Chams", "穿墙高亮");
        put("Crystal Chams", "水晶高亮");
        put("CustomSky", "自定义天空");
        put("Derp", "搞怪视角");
        put("ESP 2D", "二维透视");
        put("Filter", "实体过滤");
        put("Free Camera", "自由相机");
        put("Fullbright", "最大亮度");
        put("Game Animation", "游戏动画");
        put("Hand View", "手部视图");
        put("Hat", "帽子");
        put("Health", "生命值");
        put("Hit Particles", "命中粒子");
        put("Hole ESP", "安全洞透视");
        put("Item Physics", "物品物理");
        put("Jump Circle", "跳跃圆环");
        put("Logout Spots", "退服位置");
        put("Mas Effects", "MAS 特效");
        put("Motion Blur", "动态模糊");
        put("NameTags", "名字标签");
        put("Name Tags", "名字标签");
        put("No Ghost Blocks", "无幽灵方块");
        put("No Render", "隐藏渲染");
        put("Particles", "粒子");
        put("Pop Chams", "图腾爆裂高亮");
        put("Shaders", "着色器");
        put("Sneak Tweak", "潜行调整");
        put("Title Replace", "标题替换");
        put("Totem Animation", "图腾动画");
        put("TNT Timer", "TNT 倒计时");
        put("Trajectories", "弹道轨迹");
        put("World Tweaks", "世界调整");
        put("Xray", "矿物透视");
        put("Ysm Model", "YSM 模型");
        put("AutoLadder", "自动爬梯");
        put("Clutch", "紧急放置");
        put("AutoFish", "自动钓鱼");
        put("AntiAFK", "防挂机踢出");
        put("MurdererFinder", "杀手识别");
        put("Weather", "天气控制");
        put("Time", "时间控制");
        put("BlockIn", "方块封身");
        put("Throwpot", "自动投掷药水");
        put("Refill", "自动补充");
        put("InvCleaner", "背包清理");
        put("ThrowDebuff", "自动投掷减益");
        put("AutoHotbar", "快捷栏整理");
        put("ArmorSwitch", "盔甲切换");
        put("InventoryFill", "背包填充");
        put("FastPlace", "快速放置");
        put("AutoRespawn", "自动重生");
        put("AutoHeal", "自动治疗");
        put("AutoPearl", "自动珍珠");
        put("PearlCatch", "珍珠接住");
        put("WindCharge", "风弹");
        put("NoItemRelease", "阻止物品释放");
        put("NoClickDelay", "无点击延迟");
        put("MouseDelayFix", "鼠标延迟修复");
        put("TargetFilter", "目标过滤");
        put("Watermark", "水印");
        put("Clock", "时钟");
        put("Coords", "坐标");
        put("Compass", "指南针");
        put("FPS", "帧率");
        put("StorageESP", "容器透视");
        put("SpawnerFinder", "刷怪笼查找");
        put("ESP", "实体透视");
        put("Tracers", "追踪线");
        put("ItemESP", "物品透视");
        put("Search", "方块搜索");
        put("Arrows", "箭矢显示");
        put("Projectiles", "投掷物显示");
        put("Indicators", "威胁指示");
        put("Explosions", "爆炸显示");
        put("BedPlates", "床信息板");
        put("PropHunt", "方块伪装识别");
        put("AntiDebuff", "减益清除");
        put("Freelook", "自由视角");
        put("Blockhit Animation", "格挡动画");
        put("NoFog", "无雾");
        put("NoHurtCamera", "无受伤晃动");
        put("BlockOverlay", "方块覆盖层");
        put("BlockRenderColorOverride", "方块颜色覆盖");
        put("InventoryBlur", "背包背景模糊");
        put("Potion Status", "药水状态");
        put("Armor Status", "盔甲状态");
        put("Keystrokes", "按键显示");
        put("Reach Display", "攻击距离显示");
        put("Text GUI", "文字界面");
        put("Panic", "紧急关闭");
    }

    /** 组合设置名使用的通用术语。 */
    private static void registerTerms() {
        term("auto", "自动"); term("manual", "手动"); term("mode", "模式"); term("type", "类型");
        term("enabled", "启用"); term("enable", "启用"); term("disable", "禁用"); term("disabled", "禁用");
        term("bind", "按键"); term("key", "按键"); term("button", "按钮"); term("mouse", "鼠标");
        term("left", "左"); term("right", "右"); term("middle", "中"); term("click", "点击");
        term("delay", "延迟"); term("time", "时间"); term("ticks", "刻"); term("tick", "刻");
        term("min", "最小"); term("max", "最大"); term("minimum", "最小值"); term("maximum", "最大值");
        term("range", "范围"); term("radius", "半径"); term("distance", "距离"); term("limit", "上限");
        term("speed", "速度"); term("motion", "移动"); term("velocity", "速度"); term("multiplier", "倍率");
        term("rotation", "旋转"); term("rotate", "旋转"); term("yaw", "偏航角"); term("pitch", "俯仰角");
        term("angle", "角度"); term("fov", "视野"); term("priority", "优先级"); term("target", "目标");
        term("player", "玩家"); term("players", "玩家"); term("self", "自身"); term("other", "其他");
        term("others", "其他"); term("friend", "好友"); term("friends", "好友"); term("bot", "机器人");
        term("mob", "生物"); term("mobs", "生物"); term("monster", "怪物"); term("monsters", "怪物");
        term("animal", "动物"); term("animals", "动物"); term("ambient", "环境生物"); term("ambients", "环境生物");
        term("creature", "生物"); term("creatures", "生物"); term("entity", "实体"); term("entities", "实体");
        term("invisible", "隐身"); term("invisibles", "隐身实体"); term("visibility", "可见性");
        term("color", "颜色"); term("colour", "颜色"); term("alpha", "透明度"); term("opacity", "不透明度");
        term("outline", "描边"); term("fill", "填充"); term("filled", "填充"); term("box", "方框");
        term("line", "线条"); term("width", "宽度"); term("height", "高度"); term("size", "大小");
        term("scale", "缩放"); term("scaling", "缩放"); term("thickness", "粗细"); term("shadow", "阴影");
        term("render", "渲染"); term("draw", "绘制"); term("show", "显示"); term("hide", "隐藏");
        term("fade", "淡出"); term("fading", "淡出"); term("gradient", "渐变"); term("rainbow", "彩虹");
        term("start", "开始"); term("end", "结束"); term("side", "侧面"); term("top", "顶部");
        term("bottom", "底部"); term("inner", "内侧"); term("outer", "外侧"); term("background", "背景");
        term("health", "生命值"); term("damage", "伤害"); term("hurt", "受伤"); term("death", "死亡");
        term("attack", "攻击"); term("hit", "命中"); term("hits", "命中"); term("critical", "暴击");
        term("swing", "挥手"); term("swung", "已挥手"); term("cooldown", "冷却"); term("cps", "每秒点击");
        term("break", "破坏"); term("place", "放置"); term("placing", "放置"); term("mine", "挖掘");
        term("block", "方块"); term("blocks", "方块"); term("item", "物品"); term("items", "物品");
        term("inventory", "背包"); term("hotbar", "快捷栏"); term("slot", "槽位"); term("swap", "切换");
        term("switch", "切换"); term("select", "选择"); term("return", "返回"); term("back", "返回");
        term("armor", "盔甲"); term("helmet", "头盔"); term("chestplate", "胸甲"); term("leggings", "护腿");
        term("boots", "靴子"); term("sword", "剑"); term("swords", "剑"); term("axe", "斧");
        term("pickaxe", "镐"); term("shovel", "锹"); term("hoe", "锄"); term("bow", "弓");
        term("crossbow", "弩"); term("shield", "盾牌"); term("totem", "图腾"); term("crystal", "水晶");
        term("crystals", "水晶"); term("anchor", "重生锚"); term("pearl", "珍珠"); term("elytra", "鞘翅");
        term("firework", "烟花"); term("rocket", "火箭"); term("potion", "药水"); term("potions", "药水");
        term("food", "食物"); term("eat", "进食"); term("eating", "进食"); term("heal", "治疗");
        term("water", "水"); term("lava", "岩浆"); term("fire", "火焰"); term("explosion", "爆炸");
        term("explosions", "爆炸"); term("ground", "地面"); term("air", "空中"); term("jump", "跳跃");
        term("sneak", "潜行"); term("sneaking", "潜行"); term("sprint", "疾跑"); term("sprinting", "疾跑");
        term("fly", "飞行"); term("flight", "飞行"); term("fall", "下落"); term("step", "台阶");
        term("phase", "穿墙"); term("scaffold", "搭路"); term("safewalk", "安全行走"); term("edge", "边缘");
        term("move", "移动"); term("movement", "移动"); term("moving", "移动中"); term("input", "输入");
        term("forward", "前进"); term("backward", "后退"); term("backwards", "向后"); term("vertical", "垂直");
        term("horizontal", "水平"); term("position", "位置"); term("prediction", "预测"); term("predict", "预测");
        term("check", "检查"); term("strict", "严格"); term("smart", "智能"); term("safe", "安全");
        term("force", "强制"); term("ignore", "忽略"); term("only", "仅"); term("always", "总是");
        term("none", "无"); term("off", "关闭"); term("on", "开启"); term("both", "两者");
        term("all", "全部"); term("single", "单个"); term("multi", "多个"); term("double", "双重");
        term("random", "随机"); term("randomize", "随机化"); term("chance", "概率"); term("balance", "平衡");
        term("normal", "普通"); term("simple", "简单"); term("advanced", "高级"); term("custom", "自定义");
        term("default", "默认"); term("legacy", "旧版"); term("old", "旧版"); term("new", "新版");
        term("current", "当前"); term("next", "下次"); term("last", "上次"); term("global", "全局");
        term("local", "本地"); term("client", "客户端"); term("server", "服务器"); term("packet", "数据包");
        term("packets", "数据包"); term("chat", "聊天"); term("message", "消息"); term("messages", "消息");
        term("notification", "通知"); term("notifications", "通知"); term("sound", "声音"); term("volume", "音量");
        term("effect", "效果"); term("effects", "效果"); term("particle", "粒子"); term("particles", "粒子");
        term("animation", "动画"); term("physics", "物理"); term("model", "模型"); term("texture", "纹理");
        term("hand", "手部"); term("mainhand", "主手"); term("offhand", "副手"); term("head", "头部");
        term("body", "身体"); term("arm", "手臂"); term("eyes", "眼部"); term("camera", "相机");
        term("perspective", "视角"); term("world", "世界"); term("weather", "天气"); term("fog", "雾");
        term("blur", "模糊"); term("bloom", "泛光"); term("glow", "发光"); term("shader", "着色器");
        term("shaders", "着色器"); term("quality", "质量"); term("samples", "采样数"); term("strength", "强度");
        term("factor", "系数"); term("amount", "数量"); term("count", "数量"); term("duration", "持续时间");
        term("interval", "间隔"); term("frequency", "频率"); term("smooth", "平滑"); term("smoothing", "平滑");
        term("smoothness", "平滑度"); term("sensitivity", "灵敏度"); term("accuracy", "准确度");
        term("record", "录制"); term("playback", "回放"); term("reset", "重置"); term("clear", "清除");
        term("save", "保存"); term("load", "加载"); term("reload", "重载"); term("debug", "调试");
        term("log", "日志"); term("print", "打印"); term("warning", "警告"); term("alert", "警报");
        term("search", "搜索"); term("filter", "过滤"); term("blacklist", "黑名单"); term("whitelist", "白名单");
        term("allowed", "允许"); term("require", "需要"); term("pause", "暂停"); term("stop", "停止");
        term("cancel", "取消"); term("flush", "释放"); term("release", "释放"); term("hold", "按住");
        term("follow", "跟随"); term("assist", "辅助"); term("simulation", "模拟"); term("simulate", "模拟");
        term("sync", "同步"); term("static", "静态"); term("dynamic", "动态"); term("constant", "固定");
        term("linear", "线性"); term("curve", "曲线"); term("quadratic", "二次曲线"); term("sigmoid", "S 曲线");
        term("circle", "圆环"); term("sphere", "球体"); term("spiral", "螺旋"); term("spin", "旋转");
        term("wave", "波动"); term("shake", "抖动"); term("shaking", "抖动"); term("amplitude", "振幅");
        term("density", "密度"); term("length", "长度"); term("points", "点数"); term("segments", "分段数");
        term("fps", "帧率"); term("gui", "界面"); term("hud", "平视显示"); term("esp", "透视");
        term("ai", "智能"); term("grim", "Grim"); term("hypixel", "Hypixel"); term("heypixel", "Heypixel");
        term("ncp", "NCP"); term("polar", "Polar"); term("telly", "跳跃搭路"); term("elowen", "Elowen");
        term("raven", "Raven"); term("mace", "重锤"); term("spear", "长矛"); term("bed", "床");
        term("chest", "箱子"); term("chests", "箱子"); term("furnace", "熔炉"); term("furnaces", "熔炉");
        term("spawner", "刷怪笼"); term("ore", "矿物"); term("diamond", "钻石"); term("emerald", "绿宝石");
        term("gold", "金"); term("iron", "铁"); term("coal", "煤"); term("redstone", "红石");
        term("lapis", "青金石"); term("quartz", "石英"); term("netherite", "下界合金"); term("leather", "皮革");
        term("direction", "方向"); term("raytrace", "射线检测"); term("collision", "碰撞"); term("collisions", "碰撞");
        term("wall", "墙"); term("walls", "墙"); term("hole", "洞"); term("holes", "洞");
        term("pathfinding", "寻路"); term("path", "路径"); term("node", "节点"); term("nodes", "节点");
        term("true", "是"); term("false", "否"); term("yes", "是"); term("no", "无");
        term("above", "上方"); term("action", "动作"); term("activate", "激活"); term("activation", "激活");
        term("adaptive", "自适应"); term("age", "存在时间"); term("aggressive", "激进"); term("aggro", "仇恨");
        term("aim", "瞄准"); term("algorithm", "算法"); term("allow", "允许"); term("alphabetical", "按字母排序");
        term("analog", "模拟"); term("anti", "反"); term("suicide", "自伤"); term("weakness", "虚弱");
        term("armored", "已穿甲"); term("attributes", "属性"); term("axes", "斧"); term("bane", "克星");
        term("arthropods", "节肢生物"); term("banners", "旗帜"); term("barrier", "屏障"); term("basic", "基础");
        term("beacon", "信标"); term("beams", "光束"); term("better", "增强"); term("bob", "晃动");
        term("view", "视图"); term("blacklisted", "黑名单"); term("blast", "爆破"); term("ring", "圆环");
        term("rings", "圆环"); term("blend", "混合"); term("blindness", "致盲"); term("blood", "血液");
        term("boat", "船"); term("expand", "扩展"); term("expansion", "扩展"); term("boss", "首领");
        term("bar", "条"); term("breach", "破甲"); term("breaking", "破坏中"); term("brewing", "酿造");
        term("stands", "台"); term("brightness", "亮度"); term("callout", "标注"); term("capture", "捕获");
        term("mark", "标记"); term("markers", "标记"); term("cave", "洞穴"); term("culling", "剔除");
        term("centered", "居中"); term("chain", "连锁"); term("charge", "蓄力"); term("first", "首先");
        term("cine", "电影"); term("classic", "经典"); term("closest", "最近"); term("cobweb", "蜘蛛网");
        term("cobwebs", "蜘蛛网"); term("compact", "紧凑"); term("control", "控制"); term("cover", "覆盖");
        term("critted", "已暴击"); term("crosshair", "准星"); term("decelerate", "减速"); term("drag", "拖拽");
        term("darkness", "黑暗"); term("digital", "数字"); term("dollars", "美元"); term("down", "下");
        term("drop", "丢弃"); term("duplicate", "重复"); term("ease", "缓动"); term("easing", "缓动");
        term("in", "入"); term("out", "出"); term("circ", "圆形"); term("cubic", "三次方");
        term("expo", "指数"); term("quad", "二次方"); term("quart", "四次方"); term("quint", "五次方");
        term("sine", "正弦"); term("elastic", "弹性"); term("efficient", "高效"); term("emergency", "紧急");
        term("ench", "附魔"); term("enchant", "附魔"); term("table", "台"); term("book", "书");
        term("glint", "附魔光效"); term("extend", "延伸"); term("extended", "延伸"); term("extra", "额外");
        term("fake", "模拟"); term("punch", "拳击"); term("fast", "快速"); term("bypass", "绕过");
        term("feet", "脚下"); term("fired", "已发射"); term("projectile", "投掷物"); term("firefly", "萤火");
        term("fishing", "钓鱼"); term("rod", "鱼竿"); term("rods", "鱼竿"); term("fix", "修复");
        term("kick", "踢出"); term("lag", "延迟"); term("flash", "闪光"); term("flux", "Flux");
        term("full", "完整"); term("gamemode", "游戏模式"); term("change", "变化"); term("general", "通用");
        term("ghost", "幽灵"); term("glide", "滑翔"); term("icon", "图标"); term("god", "神");
        term("bridge", "搭桥"); term("variant", "变体"); term("golden", "金"); term("apple", "苹果");
        term("grow", "增长"); term("hands", "手部"); term("hardest", "最高硬度"); term("hearts", "心形");
        term("high", "高"); term("illegals", "非法状态"); term("impaling", "穿刺"); term("insert", "插入");
        term("join", "加入"); term("keep", "保留"); term("eggs", "鸡蛋"); term("snowballs", "雪球");
        term("close", "靠近"); term("lazy", "惰性"); term("leaked", "已泄露"); term("leave", "离开");
        term("ledge", "边缘"); term("legit", "拟真"); term("lethal", "致命"); term("override", "覆盖");
        term("threshold", "阈值"); term("addition", "增量"); term("light", "光照"); term("map", "地图");
        term("lightning", "闪电"); term("liquid", "液体"); term("overlay", "覆盖层"); term("literal", "字面");
        term("npc", "NPC"); term("looting", "抢夺"); term("low", "低"); term("lowest", "最低");
        term("cost", "成本"); term("maces", "重锤"); term("contents", "内容"); term("midpoint", "中点");
        term("mix", "混合"); term("moan", "呻吟"); term("names", "名称"); term("nausea", "反胃");
        term("ninja", "忍者"); term("not", "不"); term("tab", "标签页"); term("list", "列表");
        term("obfuscation", "混淆"); term("offset", "偏移"); term("one", "一个"); term("of", "每");
        term("each", "每种"); term("open", "开放"); term("order", "顺序"); term("own", "自身");
        term("timeout", "超时"); term("parkour", "跑酷"); term("perfect", "完美"); term("plugin", "插件");
        term("portal", "传送门"); term("pots", "药水"); term("pre", "早于"); term("predictor", "预测器");
        term("protection", "保护"); term("pulse", "脉冲"); term("pushdown", "下压"); term("rain", "降雨");
        term("ratio", "比例"); term("precision", "精度"); term("tower", "塔式搭路"); term("recast", "重新抛竿");
        term("caught", "已上钩"); term("reduce", "降低"); term("refill", "补充"); term("remove", "移除");
        term("roll", "翻滚"); term("sentence", "句子"); term("sequential", "顺序"); term("silent", "静默");
        term("stabilized", "稳定"); term("saturation", "饱和度"); term("scoreboard", "计分板"); term("set", "设置");
        term("setback", "回弹"); term("wait", "等待"); term("sharpness", "锋利"); term("shrink", "收缩");
        term("sign", "告示牌"); term("text", "文本"); term("slowness", "缓慢"); term("smite", "亡灵杀手");
        term("smoke", "烟雾"); term("octaves", "叠加层数"); term("smokers", "烟熏炉"); term("snap", "吸附");
        term("degrees", "角度"); term("snow", "雪"); term("flake", "雪花"); term("solid", "实心");
        term("sort", "排序"); term("soup", "汤"); term("spins", "旋转圈数"); term("spyglass", "望远镜");
        term("squid", "鱿鱼"); term("stars", "星星"); term("steepness", "陡峭度"); term("invert", "反转");
        term("strong", "强"); term("suffix", "后缀"); term("sunrise", "日出"); term("team", "队伍");
        term("status", "状态"); term("third", "第三"); term("person", "人称"); term("threat", "威胁");
        term("thunder", "雷声"); term("tights", "紧身"); term("title", "标题"); term("toggle", "切换");
        term("tracking", "追踪"); term("buffer", "缓冲"); term("trident", "三叉戟"); term("trigger", "触发");
        term("unbreaking", "耐久"); term("up", "上"); term("upward", "向上"); term("use", "使用");
        term("buckets", "桶"); term("validate", "校验"); term("cjk", "中日韩"); term("chars", "字符");
        term("cyrillic", "西里尔"); term("vanilla", "原版"); term("value", "数值"); term("vehicles", "载具");
        term("vignette", "暗角"); term("villager", "村民"); term("villagers", "村民"); term("visible", "可见");
        term("weak", "弱"); term("wind", "风"); term("burst", "爆发"); term("wobble", "摆动");
        term("modifier", "修饰量"); term("hour", "小时"); term("eagle", "边缘潜行"); term("kill", "击杀");
        term("aura", "光环"); term("butterfly", "蝴蝶"); term("strafe", "空中加速"); term("fireworks", "烟花");
    }

    private static void term(String english, String chinese) {
        TERMS.put(english, chinese);
    }

    private FaStrings() {
    }

    /** 登记一条对照（同时建立反向索引）。 */
    private static void put(String english, String chinese) {
        ZH.put(english, chinese);
        EN.put(chinese, english);
    }

    /** @return 当前是否为中文 */
    static boolean isChinese() {
        return chinese;
    }

    /**
     * 切换界面语言。
     *
     * @param value {@code true} 中文、{@code false} 英文
     */
    static void setChinese(boolean value) {
        chinese = value;
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
        if (!chinese) {
            return text;
        }
        String translated = ZH.get(text);
        if (translated != null) {
            return translated;
        }
        translated = CACHE.get(text);
        if (translated == null) {
            translated = translatePhrase(text);
            String existing = CACHE.putIfAbsent(text, translated);
            if (existing != null) {
                translated = existing;
            }
        }
        if (!text.equals(translated)) {
            EN.put(translated, text);
        }
        return translated;
    }

    /** 把驼峰、空格和下划线设置名按通用术语组合成中文；未知专有名词保持原文。 */
    private static String translatePhrase(String text) {
        java.util.List<String> words = splitWords(text);
        if (words.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        boolean translated = false;
        for (String word : words) {
            String replacement = TERMS.get(word.toLowerCase(java.util.Locale.ROOT));
            if (replacement != null) {
                translated = true;
            } else {
                replacement = word;
            }
            if (out.length() > 0 && asciiWord(out.charAt(out.length() - 1))
                    && !replacement.isEmpty() && asciiWord(replacement.charAt(0))) {
                out.append(' ');
            }
            out.append(replacement);
        }
        return translated ? out.toString() : text;
    }

    /** 英文单词/驼峰/连续大写/数字切词；分隔符只用于分词，不带进中文标签。 */
    private static java.util.List<String> splitWords(String text) {
        java.util.List<String> words = new java.util.ArrayList<String>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (!Character.isLetterOrDigit(current)) {
                if (start >= 0) {
                    words.add(text.substring(start, i));
                    start = -1;
                }
                continue;
            }
            if (start < 0) {
                start = i;
                continue;
            }
            char previous = text.charAt(i - 1);
            boolean camelBoundary = Character.isUpperCase(current)
                    && (Character.isLowerCase(previous) || Character.isDigit(previous)
                    || Character.isUpperCase(previous) && i + 1 < text.length()
                    && Character.isLowerCase(text.charAt(i + 1)));
            boolean digitBoundary = Character.isDigit(current) != Character.isDigit(previous);
            if (camelBoundary || digitBoundary) {
                words.add(text.substring(start, i));
                start = i;
            }
        }
        if (start >= 0) {
            words.add(text.substring(start));
        }
        return words;
    }

    private static boolean asciiWord(char value) {
        return value < 128 && Character.isLetterOrDigit(value);
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
