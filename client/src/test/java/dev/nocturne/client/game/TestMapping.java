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

    public TestMapping(Class<?> minecraftClass) {
        this.minecraftClass = minecraftClass;
    }

    @Override
    public String describe() {
        return "test-mapping";
    }

    @Override
    public String className(ClassType type) {
        return type == ClassType.MINECRAFT ? minecraftClass.getName() : type.canonicalName();
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
