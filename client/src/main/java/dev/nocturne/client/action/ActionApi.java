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

    /** 用主手对方块的指定面执行放置/使用。 */
    boolean place(Object blockPos, Object direction);

    /** 用主手执行空气使用。 */
    boolean useItem();

    /** 写入本地玩家的客户端朝向。 */
    boolean setRotation(float yaw, float pitch);

    /** 通过当前客户端连接发送一个包。 */
    boolean sendPacket(Object packet);

    /** 读取一份不可变玩家状态；世界未就绪或必需字段缺失时返回 {@link PlayerState#empty()}。 */
    PlayerState readState();

    /** 某动作在当前运行版本是否已绑定；false 时调用按契约降级。 */
    boolean isAvailable(String actionId);
}
