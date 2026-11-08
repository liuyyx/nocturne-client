package dev.nocturne.client.game;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;

/**
 * 测试用映射表：只把 {@link ClassType#MINECRAFT} 指向替身类，其余按规范名透传，且不提供描述符。
 *
 * <p>这样 {@link GameBridge#resolve()} 能在测试 classpath 上解析出替身「游戏类」，
 * 而 {@code callMapped} 因无描述符会走实参推导路径。
 */
public final class TestMapping implements Mapping {

    private final Class<?> minecraftClass;
    /** {@code Component} 的替身类；默认 {@link FakeComponent}。 */
    private final Class<?> componentClass;
    /** {@code TextComponent} 的替身类；null 表示该版本没有可构造实现（走其它回退）。 */
    private final Class<?> textComponentClass;

    public TestMapping(Class<?> minecraftClass) {
        this(minecraftClass, FakeComponent.class, FakeTextComponent.class);
    }

    /** 26.x 形态的 HUD 宿主（聊天挂在它自己身上）。 */
    private Class<?> hudChatClass;
    /** 26.x 的聊天框实现。 */
    private Class<?> chatHudClass;
    /** 明确标记为 absent 的成员，元素形如 {@code "HUD#getChatGUI"}。 */
    private java.util.Set<String> absentMembers = java.util.Collections.emptySet();

    public TestMapping(Class<?> minecraftClass, Class<?> componentClass, Class<?> textComponentClass) {
        this.minecraftClass = minecraftClass;
        this.componentClass = componentClass;
        this.textComponentClass = textComponentClass;
    }

    /** 26.x 形态：额外指定 Hud 与 ChatComponent 的替身类。 */
    public TestMapping hudAndChat(Class<?> hud, Class<?> chat) {
        this.hudChatClass = hud;
        this.chatHudClass = chat;
        return this;
    }

    /** 标记若干成员为 absent（用于验证"不重复尝试不存在的入口"）。 */
    public TestMapping absent(java.util.Set<String> members) {
        this.absentMembers = members == null ? java.util.Collections.<String>emptySet() : members;
        return this;
    }

    @Override
    public boolean isMemberAbsent(ClassType owner, String canonicalName) {
        return absentMembers.contains(owner.name() + "#" + canonicalName);
    }

    @Override
    public String describe() {
        return "test-mapping";
    }

    @Override
    public String className(ClassType type) {
        if (type == ClassType.MINECRAFT) {
            return minecraftClass.getName();
        }
        if (type == ClassType.COMPONENT) {
            return componentClass.getName();
        }
        if (type == ClassType.TEXT_COMPONENT) {
            return textComponentClass == null ? type.canonicalName() : textComponentClass.getName();
        }
        if (type == ClassType.HUD_CHAT) {
            return hudChatClass == null ? type.canonicalName() : hudChatClass.getName();
        }
        if (type == ClassType.CHAT_HUD) {
            return chatHudClass == null ? type.canonicalName() : chatHudClass.getName();
        }
        return type.canonicalName();
    }

    @Override
    public String methodName(ClassType owner, String canonicalName, String descriptor) {
        return canonicalName;
    }

    @Override
    public String fieldName(ClassType owner, String canonicalName) {
        return canonicalName;
    }

    @Override
    public boolean isIdentity() {
        return false;
    }
}
