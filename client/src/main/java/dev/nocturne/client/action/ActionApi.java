package dev.nocturne.client.action;

/**
 * 模块可见的语义动作面。
 *
 * <p>所有参数都是游戏对象，但模块不得自行反射这些对象；版本差异、候选绑定和降级均由
 * {@link ActionBinder} 处理。返回 {@code false} 表示动作未执行（世界未就绪、输入为空或能力缺失），
 * 不把“已尝试”冒充为“服务器已接受”。
 */
public interface ActionApi {

    /** 用本地玩家攻击指定实体。 */
    boolean attack(Object target);

    /** 触发游戏左键入口（自动点击器用；不要求已有目标）。 */
    boolean click();

    /** 用主手对方块的指定面执行放置/使用。 */
    boolean place(Object blockPos, Object direction);

    /**
     * 在方块 {@code (x,y,z)} 的指定面点击一次。
     *
     * <p>模块不构造游戏对象；坐标、朝向与命中向量都由动作层装配。
     *
     * @param face 六个面之一：{@code up/down/north/south/east/west}
     */
    boolean placeAt(int x, int y, int z, String face);

    /**
     * 用指定手和方块面内归一化命中点放置；hit 坐标通常在 0..1，超出时拒绝。
     * 该入口承载 Scaffold/CrystalAura 的精确命中向量，不再把全部放置固定到面心。
     */
    boolean placeAt(int x, int y, int z, String face,
                    double hitX, double hitY, double hitZ, boolean offHand);

    /** 用主手执行空气使用。 */
    boolean useItem();

    /** 用指定手执行空气使用；1.8.9 没有副手，offHand=true 明确返回 false。 */
    boolean useItem(boolean offHand);

    /** 挥动指定手；silent=true 时只发挥手包，包面缺失时返回 false 而不伪装成功。 */
    boolean swing(boolean offHand, boolean silent);

    /** 切换快捷栏；silent=true 只同步服务端槽位，false 同时修改客户端选中槽。 */
    boolean selectSlot(int slot, boolean silent);

    /** 对当前容器执行一次点击；mode 为 pickup/quick_move/swap/clone/throw/quick_craft/pickup_all。 */
    boolean clickSlot(int slot, int button, String mode);

    /** 关闭当前容器。 */
    boolean closeContainer();

    /** 通过当前连接发送聊天文本；命令请使用 sendCommand，避免高版本语义混用。 */
    boolean sendChat(String text);

    /** 发送不含前导斜杠的命令；调用方传 /command 时会先去掉首个斜杠。 */
    boolean sendCommand(String command);

    /** 从本地玩家眼睛执行一次射线；返回游戏 HitResult，未命中或能力缺失时为 null。 */
    Object pick(double distance, float partialTicks);

    /** 写入本地玩家速度；旧版由动作层拆写到 motionX/Y/Z。 */
    boolean setMotion(double x, double y, double z);

    /** 设置本 tick 的跳跃意图，不写 KeyMapping 持久键态。 */
    boolean setJumping(boolean jumping);

    /** 构造并发送仅朝向的位置包。 */
    boolean sendRotation(float yaw, float pitch, boolean onGround);

    /** 构造并发送坐标+朝向的位置包。 */
    boolean sendPositionRotation(double x, double y, double z,
                                 float yaw, float pitch, boolean onGround);

    /** 构造并发送仅落地状态的位置包。 */
    boolean sendGround(boolean onGround);

    /** 设置本地玩家疾跑状态。 */
    boolean setSprinting(boolean sprinting);

    /** 触发游戏右键入口，保留原版的延迟与交互链。 */
    boolean clickUse();

    /** 发送 START_SPRINTING/STOP_SPRINTING/START_FALL_FLYING 等玩家命令。 */
    boolean sendPlayerCommand(String action);

    /** 写入本地玩家的客户端朝向。 */
    boolean setRotation(float yaw, float pitch);

    /** 通过当前客户端连接发送一个包。 */
    boolean sendPacket(Object packet);

    /** 读取一份不可变玩家状态；世界未就绪或必需字段缺失时返回 {@link PlayerState#empty()}。 */
    PlayerState readState();

    /** 某动作在当前运行版本是否已绑定；false 时调用按契约降级。 */
    boolean isAvailable(String actionId);
}
