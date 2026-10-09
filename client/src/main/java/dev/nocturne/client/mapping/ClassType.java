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
    /** 本地玩家。 */
    LOCAL_PLAYER("net.minecraft.client.player.LocalPlayer"),
    /** 其他玩家。 */
    REMOTE_PLAYER("net.minecraft.client.player.RemotePlayer"),
    /** 客户端侧的世界实例。 */
    CLIENT_LEVEL("net.minecraft.client.multiplayer.ClientLevel"),
    /** 玩家实体基类。 */
    PLAYER("net.minecraft.world.entity.player.Player"),
    /** 生物实体基类。 */
    LIVING_ENTITY("net.minecraft.world.entity.LivingEntity"),
    /** 实体基类；模块读取 {@code isDead} 等字段时以此为宿主类。 */
    ENTITY("net.minecraft.world.entity.Entity"),
    /** 掉落物实体：ItemEsp 的目标（它不是 LivingEntity，必须单独判）。 */
    ITEM_ENTITY("net.minecraft.world.entity.item.ItemEntity"),
    /** 多人游戏模式。 */
    MULTI_PLAYER_GAME_MODE("net.minecraft.client.multiplayer.MultiPlayerGameMode"),
    /** 界面基类。 */
    SCREEN("net.minecraft.client.gui.screens.Screen"),
    /** 字体渲染器。 */
    FONT_RENDERER("net.minecraft.client.gui.FontRenderer"),
    /** 物品栈。 */
    ITEM_STACK("net.minecraft.world.item.ItemStack"),
    /** 药水效果类型（夜视等）；1.8.9 名 Potion。 */
    MOB_EFFECT("net.minecraft.world.effect.MobEffect"),
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
    /** 箱子方块；陷阱箱是它的子类（1.8.9/1.12.2 是 BlockChest / BlockTrappedChest）。 */
    CHEST_BLOCK("net.minecraft.world.level.block.ChestBlock"),
    /** 客户端区块缓存（StorageEsp 的遍历入口）。 */
    CLIENT_CHUNK_CACHE("net.minecraft.client.multiplayer.ClientChunkCache"),
    /** 区块缓存内部的块数组；1.8.9/1.12.2 无此类（走 List 路径）。 */
    CLIENT_CHUNK_STORAGE("net.minecraft.client.multiplayer.ClientChunkCache$Storage"),
    /** 区块（LevelChunk）；方块实体表挂在它上面。 */
    LEVEL_CHUNK("net.minecraft.world.level.chunk.LevelChunk");

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
