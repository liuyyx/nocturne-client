package dev.nocturne.client.mapping;

/**
 * 客户端会用到的游戏类，一律以规范名（Mojang 映射）书写。
 *
 * <p>在未混淆构建（Minecraft 26.1+）上这些就是真实的运行期名；在混淆构建上则由
 * {@link Mapping} 翻译为运行中 jar 实际暴露的名称。
 */
public enum ClassType {

    /** 主游戏客户端类。 */
    MINECRAFT("net.minecraft.client.Minecraft"),
    /** 游戏设置对象（承载 gamma 等选项）。 */
    OPTIONS("net.minecraft.client.Options"),
    /** 封装可调选项（Options#fov 在新版本中返回它）；读取值必须走映射方法 get。 */
    OPTION_INSTANCE("net.minecraft.client.OptionInstance"),
    /** 本地玩家。 */
    LOCAL_PLAYER("net.minecraft.client.player.LocalPlayer"),
    /** 其他玩家。 */
    REMOTE_PLAYER("net.minecraft.client.player.RemotePlayer"),
    /** 客户端侧的世界实例。 */
    CLIENT_LEVEL("net.minecraft.client.multiplayer.ClientLevel"),
    /** 玩家实体基类。 */
    PLAYER("net.minecraft.world.entity.player.Player"),
    /** 玩家背包；快捷栏选中槽与物品读取。 */
    INVENTORY("net.minecraft.world.entity.player.Inventory"),
    /** 生物实体基类。 */
    LIVING_ENTITY("net.minecraft.world.entity.LivingEntity"),
    /**
     * 怪物基类（{@code Mob}）：除玩家外的"活物"几乎都继承它，用来把实体分成
     * 玩家 / 怪物 / 其它三类（世界覆盖层按类别选色）。
     */
    MOB("net.minecraft.world.entity.Mob"),
    /** 实体基类；模块读取 {@code isDead} 等字段时以此为宿主类。 */
    ENTITY("net.minecraft.world.entity.Entity"),
    /** 掉落物实体：ItemEsp 的目标（它不是 LivingEntity，必须单独判）。 */
    ITEM_ENTITY("net.minecraft.world.entity.item.ItemEntity"),
    /** 多人游戏模式。 */
    MULTI_PLAYER_GAME_MODE("net.minecraft.client.multiplayer.MultiPlayerGameMode"),
    /** 交互手；动作层固定使用 MAIN_HAND。 */
    INTERACTION_HAND("net.minecraft.world.InteractionHand"),
    /** 方块命中结果；place 动作在语义层组装。 */
    BLOCK_HIT_RESULT("net.minecraft.world.phys.BlockHitResult"),
    /** 方块朝向；place 动作的实参类型。 */
    DIRECTION("net.minecraft.core.Direction"),
    /** 客户端逻辑包监听器；ActionApi 的高级发包入口。 */
    CLIENT_PACKET_LISTENER("net.minecraft.client.multiplayer.ClientPacketListener"),
    /** 客户端网络连接；发包与收包入口。 */
    CONNECTION("net.minecraft.network.Connection"),
    /** 键盘输入入口（{@code KeyboardHandler.keyPress}）。 */
    KEYBOARD_HANDLER("net.minecraft.client.KeyboardHandler"),
    /** 26.x 的键盘事件记录；key() 返回 SDL scancode。 */
    KEY_EVENT("net.minecraft.client.input.KeyEvent"),
    /** 26.x 的鼠标按钮事件记录。 */
    MOUSE_BUTTON_INFO("net.minecraft.client.input.MouseButtonInfo"),
    /** 界面基类。 */
    SCREEN("net.minecraft.client.gui.screens.Screen"),
    /** 物品栏界面：调用游戏原生玩家模型绘制方法。 */
    INVENTORY_SCREEN("net.minecraft.client.gui.screens.inventory.InventoryScreen"),
    /** 1.20+ 的绘制上下文；26.x 已被 {@code GuiGraphicsExtractor} 取代。 */
    GUI_GRAPHICS("net.minecraft.client.gui.GuiGraphics"),
    /** 字体渲染器。 */
    FONT_RENDERER("net.minecraft.client.gui.FontRenderer"),
    /** 1.13+ 的字体类（1.8.9 用 {@link #FONT_RENDERER}）。 */
    FONT("net.minecraft.client.gui.Font"),
    /** 物品栈。 */
    ITEM_STACK("net.minecraft.world.item.ItemStack"),
    /** 物品基类；FastPlace 的方块/投掷物分类判定。 */
    ITEM("net.minecraft.world.item.Item"),
    /** 方块物品。 */
    BLOCK_ITEM("net.minecraft.world.item.BlockItem"),
    /** 雪球物品。 */
    SNOWBALL_ITEM("net.minecraft.world.item.SnowballItem"),
    /** 鸡蛋物品。 */
    EGG_ITEM("net.minecraft.world.item.EggItem"),
    /** 药水效果类型（夜视等）；1.8.9 名 Potion。 */
    MOB_EFFECT("net.minecraft.world.effect.MobEffect"),
    /** 效果常量持有类；旧映射可与效果类型共用同一个运行时类。 */
    MOB_EFFECTS("net.minecraft.world.effect.MobEffects"),
    /** 药水效果实例；1.8.9 名 PotionEffect。 */
    MOB_EFFECT_INSTANCE("net.minecraft.world.effect.MobEffectInstance"),
    /** 窗口句柄（来自 Blaze3D 平台层，非 Minecraft 命名空间）。 */
    WINDOW("com.mojang.blaze3d.platform.Window"),
    /** 鼠标处理器（1.14.4+）：抓取/释放鼠标、玩家朝向的鼠标输入都在这里。 */
    MOUSE_HANDLER("net.minecraft.client.MouseHandler"),
    /** 三维向量：相机眼位、实体坐标都从它上面读 x/y/z（世界覆盖层投影用）。 */
    VEC3("net.minecraft.world.phys.Vec3"),
    /** 方块实体基类；StorageESP 遍历容器用。 */
    BLOCK_ENTITY("net.minecraft.world.level.block.entity.BlockEntity"),
    /** 方块坐标（方块实体的位置读它上面的 getX/getY/getZ）。 */
    BLOCK_POS("net.minecraft.core.BlockPos"),
    /** 方块基类：Search 用它的描述 id 认方块（1.8.9 是 Block.getUnlocalizedName）。 */
    BLOCK("net.minecraft.world.level.block.Block"),
    /** 只读方块访问面：逐格读方块走它（1.8.9 是 IBlockAccess），比具体世界类稳。 */
    LEVEL_READER("net.minecraft.world.level.LevelReader"),
    /** 箱子方块实体（含陷阱箱判定与开盖计数）。 */
    CHEST_BLOCK_ENTITY("net.minecraft.world.level.block.entity.ChestBlockEntity"),
    /** 末影箱方块实体。 */
    ENDER_CHEST_BLOCK_ENTITY("net.minecraft.world.level.block.entity.EnderChestBlockEntity"),
    /** 漏斗方块实体。 */
    HOPPER_BLOCK_ENTITY("net.minecraft.world.level.block.entity.HopperBlockEntity"),
    /** 熔炉方块实体（1.14+ 是抽象基类，高炉/烟熏炉也继承它）。 */
    FURNACE_BLOCK_ENTITY("net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity"),
    /** 发射器方块实体（投掷器继承它，判定时必须先判投掷器）。 */
    DISPENSER_BLOCK_ENTITY("net.minecraft.world.level.block.entity.DispenserBlockEntity"),
    /** 投掷器方块实体。 */
    DROPPER_BLOCK_ENTITY("net.minecraft.world.level.block.entity.DropperBlockEntity"),
    /** 潜影盒方块实体；1.8.9/1.12.2 没有（表里 absent）。 */
    SHULKER_BOX_BLOCK_ENTITY("net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity"),
    /** 方块状态：陷阱箱与普通箱共用方块实体类，只能看方块来分。 */
    BLOCK_STATE("net.minecraft.world.level.block.state.BlockState"),
    /** 刷怪笼方块实体。 */
    SPAWNER_BLOCK_ENTITY("net.minecraft.world.level.block.entity.SpawnerBlockEntity"),
    /** 刷怪笼逻辑；实体名从它读取。 */
    BASE_SPAWNER("net.minecraft.world.level.BaseSpawner"),
    /** 箱子方块；陷阱箱是它的子类（1.8.9/1.12.2 是 BlockChest / BlockTrappedChest）。 */
    CHEST_BLOCK("net.minecraft.world.level.block.ChestBlock"),
    /** 客户端区块缓存（StorageEsp 的遍历入口）。 */
    CLIENT_CHUNK_CACHE("net.minecraft.client.multiplayer.ClientChunkCache"),
    /** 区块缓存内部的块数组；1.8.9/1.12.2 无此类（走 List 路径）。 */
    CLIENT_CHUNK_STORAGE("net.minecraft.client.multiplayer.ClientChunkCache$Storage"),
    /** 区块（LevelChunk）；方块实体表挂在它上面。 */
    LEVEL_CHUNK("net.minecraft.world.level.chunk.LevelChunk"),
    /**
     * 聊天组件：{@code Entity#getName} 之类的成员返回它而不是 {@code String}。
     *
     * <p>读文本走 {@code getString()}——运行期名由映射表给出（1.8.9 是 {@code IChatComponent}，
     * 表里 {@code getString} 对应 {@code d}/{@code method_10865}/{@code func_150254_d}）。
     * 用 {@code instanceof String} 判组件永远是"读不到"，这正是标签类模块一个字符都画不出来的原因。
     */
    CHAT_COMPONENT("net.minecraft.network.chat.Component");

    /** Mojang 映射下的全限定类名。 */
    private final String canonicalName;

    /** 由枚举声明的规范名直接构造，不可变。 */
    ClassType(String canonicalName) {
        // 枚举构造在类初始化时执行，此处不做校验，规范名由常量表保证正确。
        this.canonicalName = canonicalName;
    }

    /** Mojmap 下的全限定类名。 */
    public String canonicalName() {
        return canonicalName;
    }
}
