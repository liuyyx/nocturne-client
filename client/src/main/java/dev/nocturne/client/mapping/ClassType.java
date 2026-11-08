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
    /** 暂停界面；InvWalk 的 Inventory Only 判定。 */
    PAUSE_SCREEN("net.minecraft.client.gui.screens.PauseScreen"),
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
    /** 玩家能力（飞行、创造模式）。 */
    ABILITIES("net.minecraft.world.entity.player.Abilities"),
    /** 装备槽枚举；盔甲/鞘翅判断使用。 */
    EQUIPMENT_SLOT("net.minecraft.world.entity.EquipmentSlot"),
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
    /** 实体命中结果；自动点击器的触发模式判断。 */
    ENTITY_HIT_RESULT("net.minecraft.world.phys.EntityHitResult"),
    /** 方块朝向；place 动作的实参类型。 */
    DIRECTION("net.minecraft.core.Direction"),
    /** 客户端逻辑包监听器；ActionApi 的高级发包入口。 */
    CLIENT_PACKET_LISTENER("net.minecraft.client.multiplayer.ClientPacketListener"),
    /** 客户端网络连接；发包与收包入口。 */
    CONNECTION("net.minecraft.network.Connection"),
    /** 网络包接口；接收队列释放时调 handle(listener) 走原版处理入口。 */
    PACKET("net.minecraft.network.protocol.Packet"),
    /** 交互结果；consumesAction 统一判断放置/使用是否消费。 */
    INTERACTION_RESULT("net.minecraft.world.InteractionResult"),
    /** 队伍颜色；26.x 为带 rgb 字段的值类型。 */
    TEAM_COLOR("net.minecraft.world.scores.TeamColor"),
    /** 键盘输入入口（{@code KeyboardHandler.keyPress}）。 */
    KEYBOARD_HANDLER("net.minecraft.client.KeyboardHandler"),
    /**
     * 屏幕级输入派发接口（1.16+ 的 {@code ContainerEventHandler}）：鼠标点击在这里分发给
     * 子控件。自绘界面打开时必须短路，否则同一次点击会穿透到背后的原生 Screen。
     */
    CONTAINER_EVENT_HANDLER("net.minecraft.client.gui.components.events.ContainerEventHandler"),
    /** 26.x 的键盘事件记录；key() 返回 SDL scancode。 */
    KEY_EVENT("net.minecraft.client.input.KeyEvent"),
    /** 26.x 的鼠标按钮事件记录。 */
    MOUSE_BUTTON_INFO("net.minecraft.client.input.MouseButtonInfo"),
    /** 界面基类。 */
    SCREEN("net.minecraft.client.gui.screens.Screen"),
    CHAT_SCREEN("net.minecraft.client.gui.screens.ChatScreen"),
    /** 容器界面与槽位；HoverTotem 使用 hoveredSlot.index。 */
    CONTAINER_SCREEN("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen"),
    SLOT("net.minecraft.world.inventory.Slot"),
    /** HUD 绘制入口（1.20–1.21 的 {@code Gui.render}）。 */
    HUD("net.minecraft.client.gui.Gui"),
    /** 物品栏界面：调用游戏原生玩家模型绘制方法。 */
    INVENTORY_SCREEN("net.minecraft.client.gui.screens.inventory.InventoryScreen"),
    /** 1.20+ 的绘制上下文；26.x 已被 {@code GuiGraphicsExtractor} 取代。 */
    GUI_GRAPHICS("net.minecraft.client.gui.GuiGraphics"),
    /**
     * 1.13–1.19 的 GUI 原语宿主（{@code fill} / {@code drawString} / 裁剪）。
     *
     * <p>1.20 起该类被删除，原语移进 {@link #GUI_GRAPHICS}；表里这些版本会把成员标成
     * {@code absent}，运行期回退规范名（这些版本本来也不走 {@code bindLegacy}）。
     */
    GUI_COMPONENT("net.minecraft.client.gui.GuiComponent"),
    /**
     * 1.13+ 的变换栈；1.16–1.19 的 {@code fill}/{@code drawString} 以它为第一个形参。
     *
     * <p>它的 {@code pushPose}/{@code popPose} 在混淆表里都是无参 void（1.16.5 是 a/b），
     * 形状无法区分，只能查表——文本缩放依赖这两个句柄。
     */
    POSE_STACK("com.mojang.blaze3d.vertex.PoseStack"),
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
    /** 1.8.9/1.12.2 的 MouseHelper：mouseXYChange 是相机旋转的唯一增量入口。 */
    MOUSE_HELPER("net.minecraft.util.MouseHelper"),
    /** 三维向量：相机眼位、实体坐标都从它上面读 x/y/z（世界覆盖层投影用）。 */
    VEC3("net.minecraft.world.phys.Vec3"),
    /**
     * 轴对齐包围盒：ESP 框按它的 8 个角投影取屏幕包围矩形（对齐 eps-plus 的 ESP2D）。
     *
     * <p>只投影头顶/脚下两点时，低头/侧视/远距离会把竖直差压到 1–4px，框实际不可见。
     * 1.8.9 叫 AxisAlignedBB（表里 absent），那边退回两点方案。
     */
    AABB("net.minecraft.world.phys.AABB"),
    /** 方块实体基类；StorageESP 遍历容器用。 */
    BLOCK_ENTITY("net.minecraft.world.level.block.entity.BlockEntity"),
    /** 方块坐标（blockPos() 构造、BlockEntity#getBlockPos 的返回类型）。 */
    BLOCK_POS("net.minecraft.core.BlockPos"),
    /**
     * 方块坐标的父类：坐标分量 x/y/z 是它的字段。
     *
     * <p>不要用 {@code BlockPos#getX()} 读分量：1.16.5 里同名方法既有实例版又有静态打包版
     * {@code getX(long)}，映射表只记一个重载，选中静态版就会在运行期静默读不到坐标
     * （真机症状：StorageESP `scanned=9 matched=6 visible=0`，一个框都画不出来）。
     */
    VEC3_I("net.minecraft.core.Vec3i"),
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
    /** 空气方块：旧版没有 {@code BlockState#isAir()}，用方块类型兜底。 */
    AIR_BLOCK("net.minecraft.world.level.block.AirBlock"),
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
    /** 客户端输入基类；26.x 持有可替换的按键记录与移动向量。 */
    CLIENT_INPUT("net.minecraft.client.player.ClientInput"),
    /** 旧版及 1.14–1.21 的输入基类；承载 forwardImpulse/leftImpulse 等字段。 */
    INPUT("net.minecraft.client.player.Input"),
    /** 每 tick 从物理键合成移动输入的入口。 */
    KEYBOARD_INPUT("net.minecraft.client.player.KeyboardInput"),
    /** 26.x 的不可变七键记录；事件层整体替换，不写 KeyMapping 持久状态。 */
    PLAYER_INPUT("net.minecraft.world.entity.player.Input"),
    /** 二维移动向量。 */
    VEC2("net.minecraft.world.phys.Vec2"),
    /** 准星命中基类；旧版 MovingObjectPosition 也携带命中类型。 */
    HIT_RESULT("net.minecraft.world.phys.HitResult"),
    /** 火焰弹、雪球、鸡蛋、喷溅药水；Indicators/Projectiles 使用。 */
    ABSTRACT_FIREBALL("net.minecraft.world.entity.projectile.AbstractFireball"),
    SNOWBALL("net.minecraft.world.entity.projectile.Snowball"),
    EGG("net.minecraft.world.entity.projectile.Egg"),
    THROWN_POTION("net.minecraft.world.entity.projectile.ThrownPotion"),
    /** 方块更新包；PropHunt 记录近期变化位置。 */
    CLIENTBOUND_BLOCK_UPDATE("net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket"),
    CLIENTBOUND_BLOCK_EVENT("net.minecraft.network.protocol.game.ClientboundBlockEventPacket"),
    /** 珍珠实体；AutoPearl/PearlCatch 轨迹目标。 */
    THROWN_ENDER_PEARL("net.minecraft.world.entity.projectile.ThrownEnderpearl"),
    THROWABLE_PROJECTILE("net.minecraft.world.entity.projectile.ThrowableProjectile"),
    /** 鱼钩实体；AutoFish 读取玩家 fishing 字段。 */
    FISHING_HOOK("net.minecraft.world.entity.projectile.FishingHook"),
    /** 箭实体；AntiFireball/Arrows 的目标。 */
    ABSTRACT_ARROW("net.minecraft.world.entity.projectile.AbstractArrow"),
    /** 玩家队伍及队伍关系判定。 */
    PLAYER_TEAM("net.minecraft.world.scores.PlayerTeam"),
    /** 动物实体分类。 */
    ANIMAL("net.minecraft.world.entity.animal.Animal"),
    /** 环境生物分类（蝙蝠等）。 */
    AMBIENT("net.minecraft.world.entity.ambient.AmbientCreature"),
    /** 盔甲架；目标选择通常要显式排除。 */
    ARMOR_STAND("net.minecraft.world.entity.decoration.ArmorStand"),
    /** 末影水晶；CrystalAura 与命中特效的目标。 */
    END_CRYSTAL("net.minecraft.world.entity.boss.enderdragon.EndCrystal"),
    /** TNT 实体；TNTTimer 读取引信。 */
    PRIMED_TNT("net.minecraft.world.entity.item.PrimedTnt"),
    /** 全部移动包基类；Freecam 仅拦截这些包。 */
    SERVERBOUND_MOVE_PLAYER("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket"),
    /** 玩家挖掘/释放物品动作包；NoItemRelease 读取 action。 */
    SERVERBOUND_PLAYER_ACTION("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket"),
    /** 实体速度包；Velocity/JumpReset/KnockbackDelay 读取并重建。 */
    CLIENTBOUND_ENTITY_VELOCITY("net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket"),
    CLIENTBOUND_EXPLOSION("net.minecraft.network.protocol.game.ClientboundExplodePacket"),
    CLIENTBOUND_ENTITY_EVENT("net.minecraft.network.protocol.game.ClientboundEntityEventPacket"),
    CLIENTBOUND_ADD_ENTITY("net.minecraft.network.protocol.game.ClientboundAddEntityPacket"),
    /** Tab 列表更新与移除包；LogoutSpots/PlayerAlarms 判型。 */
    CLIENTBOUND_PLAYER_INFO_UPDATE("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket"),
    CLIENTBOUND_PLAYER_INFO_REMOVE("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket"),
    /** Epsilon 包级模块使用的聊天、位置、槽位和容器包。 */
    CLIENTBOUND_SYSTEM_CHAT("net.minecraft.network.protocol.game.ClientboundSystemChatPacket"),
    CLIENTBOUND_PLAYER_POSITION("net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket"),
    SERVERBOUND_CONTAINER_CLICK("net.minecraft.network.protocol.game.ServerboundContainerClickPacket"),
    SERVERBOUND_PLAYER_INPUT("net.minecraft.network.protocol.game.ServerboundPlayerInputPacket"),
    CLIENTBOUND_SET_TITLE_TEXT("net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket"),
    CLIENTBOUND_SET_SUBTITLE_TEXT("net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket"),
    CLIENTBOUND_SET_ACTION_BAR_TEXT("net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket"),
    /** 文本组件与可变内容；TitleReplace 在包上替换内容。 */
    COMPONENT("net.minecraft.network.chat.Component"),
    /**
     * 可构造的纯文本组件。
     *
     * <p>1.8.9–1.12 的 {@code IChatComponent} 是接口，本地播报需要一个实例；那时只有
     * {@code ChatComponentText(String)}。1.13+ 直接 {@code Component.literal(String)}，
     * 该类型只在旧版映射表里出现（其余版本标 absent，调用方走 literal）。
     */
    TEXT_COMPONENT("net.minecraft.network.chat.TextComponent"),
    /**
     * HUD 聊天框：1.19+ 是 {@code ChatComponent#addMessage}，1.8.9–1.12 是
     * {@code GuiNewChat#printChatMessage}（映射表按别名归一到同一个规范成员）。
     */
    CHAT_HUD("net.minecraft.client.gui.components.ChatComponent"),
    /**
     * 26.x 的 HUD 宿主（{@code Minecraft.gui} 的运行时类型）。
     *
     * <p>26.x 把聊天框从 {@code Gui} 搬到了 {@code Hud}：{@code Hud.chat}/{@code Hud.getChat()}
     * 才是本地播报入口，{@code Gui#getChat} 在这些版本是 verified absent。
     */
    HUD_CHAT("net.minecraft.client.gui.Hud"),
    MUTABLE_COMPONENT("net.minecraft.network.chat.MutableComponent"),
    STYLE("net.minecraft.network.chat.Style"),
    /** 原版声音常量；报警和命中音效共享。 */
    SOUND_EVENTS("net.minecraft.sounds.SoundEvents"),
    /** 计分板、目标与分数；血量/队伍显示读取。 */
    SCOREBOARD("net.minecraft.world.scores.Scoreboard"),
    OBJECTIVE("net.minecraft.world.scores.Objective"),
    SCORE("net.minecraft.world.scores.Score"),
    /** 方块碰撞体；isEmpty 用于放置空间和弹道近似。 */
    VOXEL_SHAPE("net.minecraft.world.phys.shapes.VoxelShape"),
    /** 世界射线上下文；高级搭路与伤害计算使用。 */
    CLIP_CONTEXT("net.minecraft.world.level.ClipContext"),
    /** 世界存档数据；TimeChanger 写入 dayTime。 */
    LEVEL_DATA("net.minecraft.world.level.storage.LevelData"),
    /** 世界基类；碰撞与射线入口声明在这里。 */
    LEVEL("net.minecraft.world.level.Level"),
    /** 1.14.4 的客户端世界名；该版本没有 ClientLevel。 */
    MULTIPLAYER_LEVEL("net.minecraft.client.multiplayer.MultiPlayerLevel"),
    /** 方块渲染调度器；XRay 在真实渲染入口按 BlockState 剔除。 */
    BLOCK_RENDER_DISPATCHER("net.minecraft.client.renderer.block.BlockRenderDispatcher"),
    /** 实体渲染调度器；实体渲染事件的映射宿主。 */
    ENTITY_RENDER_DISPATCHER("net.minecraft.client.renderer.entity.EntityRenderDispatcher"),
    /** 世界渲染入口；世界空间事件的映射宿主。 */
    LEVEL_RENDERER("net.minecraft.client.renderer.LevelRenderer"),
    /** 相机人称枚举；Freelook 切换与恢复。 */
    PERSPECTIVE("net.minecraft.client.CameraType"),
    /** 帧/ tick 插值计时器。 */
    DELTA_TRACKER("net.minecraft.client.DeltaTracker"),
    /** 游戏渲染器与相机宿主。 */
    GAME_RENDERER("net.minecraft.client.renderer.GameRenderer"),
    CAMERA("net.minecraft.client.Camera"),
    ITEM_IN_HAND_RENDERER("net.minecraft.client.renderer.ItemInHandRenderer"),
    QUATERNIONF("org.joml.Quaternionf"),
    AXIS_ANGLE4F("org.joml.AxisAngle4f"),
    /** 容器菜单；背包动作需要 containerId 与物品列表。 */
    ABSTRACT_CONTAINER_MENU("net.minecraft.world.inventory.AbstractContainerMenu"),
    /** 现代容器点击类型；旧版使用整数 mode。 */
    CONTAINER_INPUT("net.minecraft.world.inventory.ContainerInput"),
    /** 1.12–1.21 的容器点击类型；26.x 改名 ContainerInput。 */
    CLICK_TYPE("net.minecraft.world.inventory.ClickType"),
    /** 玩家网络信息与游戏模式。 */
    PLAYER_INFO("net.minecraft.client.multiplayer.PlayerInfo"),
    /** 玩家资料；UUID 是 AntiBot/好友/退服记录的稳定键。 */
    GAME_PROFILE("com.mojang.authlib.GameProfile"),
    /** 游戏模式枚举。 */
    GAME_TYPE("net.minecraft.world.level.GameType"),
    /** 按键映射；物理输入查询使用，不用于持久写入移动状态。 */
    KEY_MAPPING("net.minecraft.client.KeyMapping"),
    /** 键码与物理键查询。 */
    INPUT_CONSTANTS("com.mojang.blaze3d.platform.InputConstants"),
    /** 物品常量注册表。 */
    ITEMS("net.minecraft.world.item.Items"),
    /** 方块常量注册表。 */
    BLOCKS("net.minecraft.world.level.block.Blocks"),
    /** 木桶方块实体；1.14 前的版本显式 absent。 */
    BARREL_BLOCK_ENTITY("net.minecraft.world.level.block.entity.BarrelBlockEntity"),
    /** 位置包：只更新朝向。 */
    SERVERBOUND_MOVE_ROT("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$Rot"),
    /** 位置包：同时更新坐标与朝向。 */
    SERVERBOUND_MOVE_POS_ROT("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$PosRot"),
    /** 位置包：只更新落地状态。 */
    SERVERBOUND_MOVE_STATUS("net.minecraft.network.protocol.game.ServerboundMovePlayerPacket$StatusOnly"),
    /** 服务端热栏槽位切换包。 */
    SERVERBOUND_SET_CARRIED_ITEM("net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket"),
    /** 挥手包；类不存在的版本由动作层明确降级为普通挥手。 */
    SERVERBOUND_SWING("net.minecraft.network.protocol.game.ServerboundSwingPacket"),
    /** 26.3 的挥手包新名。 */
    SERVERBOUND_PUNCH("net.minecraft.network.protocol.game.ServerboundPunchPacket"),
    /** 关闭容器包。 */
    SERVERBOUND_CONTAINER_CLOSE("net.minecraft.network.protocol.game.ServerboundContainerClosePacket"),
    /** 玩家命令包及动作枚举；疾跑、潜行、鞘翅启动共用。 */
    SERVERBOUND_PLAYER_COMMAND("net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket"),
    PLAYER_COMMAND_ACTION("net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket$Action"),
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
