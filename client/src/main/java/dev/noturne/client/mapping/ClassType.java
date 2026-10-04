package dev.noturne.client.mapping;

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
    /** 多人游戏模式。 */
    MULTI_PLAYER_GAME_MODE("net.minecraft.client.multiplayer.MultiPlayerGameMode"),
    /** 界面基类。 */
    SCREEN("net.minecraft.client.gui.screens.Screen"),
    /** 字体渲染器。 */
    FONT_RENDERER("net.minecraft.client.gui.FontRenderer"),
    /** 物品栈。 */
    ITEM_STACK("net.minecraft.world.item.ItemStack"),
    /** 窗口句柄（来自 Blaze3D 平台层，非 Minecraft 命名空间）。 */
    WINDOW("com.mojang.blaze3d.platform.Window");

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
