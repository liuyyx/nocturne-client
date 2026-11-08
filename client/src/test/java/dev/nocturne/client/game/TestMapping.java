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

    public TestMapping(Class<?> minecraftClass, Class<?> componentClass, Class<?> textComponentClass) {
        this.minecraftClass = minecraftClass;
        this.componentClass = componentClass;
        this.textComponentClass = textComponentClass;
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
