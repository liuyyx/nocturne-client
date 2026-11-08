package dev.nocturne.client.game;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.mapping.MethodCandidate;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通往运行中游戏的反射桥接层，完全由 {@link Mapping} 驱动。
 *
 * <p>这里的一切名字都来自映射表，因此同一份代码既能跑在混淆过的 1.8.9 客户端上，也能跑在未混淆的
 * 26.x 版本上。类尚未找到时 {@link #resolve()} 会重新执行查找，以适应类加载器被替换
 * （如重载、attach 后重新引导）的情况；成功解析出的句柄才会进入缓存。
 *
 * <p>已解析出的类/访问器以及字段/方法句柄都会缓存：字段与方法查找在继承链上逐级试探会产生大量
 * 异常分配，若每个 tick 重来一次会持续制造垃圾（M-71/M-72）。缓存只记录成功的查找结果，
 * 失败一律不缓存，因此类晚一点加载仍然能被后续调用重新解析。
 */
public final class GameBridge {

    /**
     * 未解析成功时的重试间隔（纳秒）。{@code instrumentation} 路径的类查找要遍历全部已加载类，
     * 若类尚未加载就每帧全表扫描一次，代价随类数线性增长（M-71）；用固定间隔把重试频率压下来。
     */
    private static final long RESOLVE_RETRY_INTERVAL_NANOS = 250_000_000L;

    /** Agent 提供的仪表接口，可为 {@code null}（此时退回到线程上下文类加载器查找）。 */
    private final Instrumentation instrumentation;
    private final Mapping mapping;

    /** 已解析出的主游戏类；未解析成功时为 {@code null}，须在游戏主线程之外访问。 */
    private volatile Class<?> minecraftClass;
    /** 主游戏类的单例访问器（静态、无参）；未解析成功时为 {@code null}。 */
    private volatile Method getInstanceMethod;
    /** 上次尝试解析的时间戳（纳秒）；成功后才可清零，失败时必须保留退避（M-71），否则每帧一次全表扫描。 */
    private volatile long lastResolveAttemptNanos;

    /** 已成功定位的字段句柄，键为（宿主类，字段名）。 */
    private final ConcurrentHashMap<FieldKey, Field> fieldCache = new ConcurrentHashMap<FieldKey, Field>();
    /** 已成功定位的方法句柄，键为（宿主类，方法名，形参签名）。 */
    private final ConcurrentHashMap<MethodKey, Method> methodCache = new ConcurrentHashMap<MethodKey, Method>();
    /** 每个类的方法表缓存；NameTags 等每帧反射调用不再重复扫描继承链。 */
    private final ConcurrentHashMap<Class<?>, List<Method>> methodListCache =
            new ConcurrentHashMap<Class<?>, List<Method>>();
    /** 已成功解析的映射类；只缓存成功结果，类晚加载时仍可重试。 */
    private final ConcurrentHashMap<ClassType, Class<?>> typeCache =
            new ConcurrentHashMap<ClassType, Class<?>>();
    /** 构造器按真实类与实参形状缓存；不可变输入记录与 Vec2 共用。 */
    private final ConcurrentHashMap<ConstructorKey, java.lang.reflect.Constructor<?>> constructorCache =
            new ConcurrentHashMap<ConstructorKey, java.lang.reflect.Constructor<?>>();
    /** 日志限流计数器，避免热路径上的失败信息刷屏。 */
    private final AtomicLong logCount = new AtomicLong();
    /** 方块坐标构造器；首次使用时解析，失败不缓存。 */
    private volatile java.lang.reflect.Constructor<?> blockPosConstructor;

    /**
     * 构造反射桥。
     *
     * @param instrumentation agent 注入的 {@link Instrumentation}，可为 {@code null}
     * @param mapping         提供混淆名/描述符的映射表，不可为 {@code null}
     */
    public GameBridge(Instrumentation instrumentation, Mapping mapping) {
        this.instrumentation = instrumentation;
        this.mapping = mapping;
    }

    /** @return 本桥持有的映射表（与构造参数相同） */
    public Mapping mapping() {
        return mapping;
    }

    /**
     * 主游戏类的候选运行时名称，按优先级从高到低排列（取自 {@link Mapping#classNameCandidates}）。
     *
     * <p>候选覆盖四种命名空间（原版混淆名 → intermediary → SRG → NeoForge），规范名兜底；
     * 逐个尝试 {@code Class.forName}，第一个成功者胜出。
     */
    public String[] minecraftClassCandidates() {
        java.util.List<String> candidates = mapping.classNameCandidates(ClassType.MINECRAFT);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>(candidates);
        // 规范名兜底：未混淆构建以及 Forge/SRG 暴露可读名的场合仍能命中。
        names.add(ClassType.MINECRAFT.canonicalName());
        return names.toArray(new String[names.size()]);
    }

    /** @return 游戏类与单例访问器是否都已定位成功；两者齐备才算解析完成 */
    public boolean isResolved() {
        return minecraftClass != null && getInstanceMethod != null;
    }

    /**
     * 定位游戏类及其单例访问器，可安全地重复调用。
     *
     * <p>幂等：一旦 {@link #isResolved()} 为 true 就直接返回，不重复反射查找。查找失败会按
     * {@link #RESOLVE_RETRY_INTERVAL_NANOS} 限流，避免未解析状态下每个 tick 全表扫描已加载类。
     * 只有当类已加载、且确实存在一个静态、非 void、返回该游戏类的 {@code getInstance} 访问器时才
     * 提交结果；否则保持未解析，下次继续尝试（M-70）。
     *
     * @return 定位成功返回 true；类尚未加载或访问器不存在时返回 false，不抛异常
     */
    public boolean resolve() {
        if (isResolved()) {
            return true;
        }
        long now = System.nanoTime();
        long last = lastResolveAttemptNanos;
        if (now - last < RESOLVE_RETRY_INTERVAL_NANOS) {
            return false;
        }
        lastResolveAttemptNanos = now;

        Class<?> found = findLoadedClass(minecraftClassCandidates());
        if (found == null) {
            return false;
        }
        // 单例访问器的候选名逐个试（getInstance 在 vanilla/forge 等命名空间下各有其名）。
        Method accessor = null;
        for (MethodCandidate candidate : mapping.methodCandidates(ClassType.MINECRAFT, "getInstance")) {
            accessor = findStaticAccessor(found, candidate.name(), found);
            if (accessor != null) {
                break;
            }
        }
        if (accessor == null) {
            // 形状兜底：Forge 等环境在运行期把成员重映射成 SRG 名（1.8.9 的 getMinecraft 变成
            // func_71410_x），映射表里记的原版混淆名（A）在那里根本不存在，于是单例永远拿不到——
            // 后果是字体绑不上、界面一个字都不显示。
            // 「静态、无参、返回自身类型」正是 getMinecraft 这类单例访问器的形状，且与命名方案无关。
            accessor = findSelfFactory(found);
            if (accessor != null) {
                logThrottled("Minecraft singleton resolved by shape (remapped environment): "
                        + found.getName() + "." + accessor.getName(), null);
            }
        }
        if (accessor == null) {
            return false;
        }
        this.minecraftClass = found;
        this.getInstanceMethod = accessor;
        return true;
    }

    /**
     * 按形状找出"单例访问器"：{@code static}、无参、返回该类型本身。
     *
     * <p>多个候选时按名字长度取最短（{@code getMinecraft} / {@code func_71410_x} 这类访问器
     * 通常比同形状的工具方法更短），并保持确定性。
     *
     * @param type 已加载的游戏主类
     * @return 访问器；不存在时返回 {@code null}
     */
    private static Method findSelfFactory(Class<?> type) {
        Method best = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 0
                    || method.getReturnType() != type) {
                continue;
            }
            if (best == null || method.getName().length() < best.getName().length()) {
                best = method;
            }
        }
        return best == null ? null : Reflect.accessible(best);
    }

    /** @return 已解析的游戏主类引用；未解析时为 {@code null}，不应缓存该结果 */
    public Class<?> minecraftClass() {
        return minecraftClass;
    }

    /** @return 游戏单例；类尚未可达或调用失败时为 {@code null} */
    public Object minecraft() {
        if (!resolve()) {
            return null;
        }
        Method accessor = getInstanceMethod;
        try {
            return accessor.invoke(null);
        } catch (Throwable t) {
            // 访问器失效（类被替换/重载）时不留下恒为 true 的“已解析”状态，否则桥会永久失效；
            // 清除后下一次调用会重新解析并重试（M-70）。
            invalidate();
            logThrottled("getInstance() invoke failed; cleared resolution for retry", t);
            return null;
        }
    }

    /** @return 本地玩家；尚未进入世界时为 {@code null} */
    public Object player() {
        Object minecraft = minecraft();
        return minecraft == null ? null : readField(minecraft, ClassType.MINECRAFT, "player");
    }

    /**
     * 游戏侧的类加载器。
     *
     * <p>解析映射类名必须用它：模块自己的加载器是系统/应用加载器，而在 Fabric（KnotClassLoader）与
     * Forge 的隔离加载器下它看不到任何游戏类，{@code Class.forName} 会恒失败——模块据此判定
     * "该版本没有这个类"并自我禁用，开关看着在、其实什么都不做。
     *
     * @return 已解析游戏类的加载器；尚未解析时退回玩家的；两者都拿不到时返回本类的加载器
     */
    public ClassLoader gameLoader() {
        Class<?> minecraft = minecraftClass();
        if (minecraft != null && minecraft.getClassLoader() != null) {
            return minecraft.getClassLoader();
        }
        Object player = player();
        if (player != null && player.getClass().getClassLoader() != null) {
            return player.getClass().getClassLoader();
        }
        return GameBridge.class.getClassLoader();
    }

    /** @return 映射类；尚未加载或该版本没有此类时返回 {@code null}，失败不缓存 */
    public Class<?> type(ClassType wanted) {
        if (wanted == null) {
            return null;
        }
        Class<?> cached = typeCache.get(wanted);
        if (cached != null) {
            return cached;
        }
        ClassLoader loader = gameLoader();
        for (String name : mapping.classNameCandidates(wanted)) {
            try {
                Class<?> resolved = Class.forName(name.replace('/', '.'), false, loader);
                typeCache.put(wanted, resolved);
                return resolved;
            } catch (Throwable ignored) {
                // 按候选顺序继续；全部失败由调用方按能力缺失处理。
            }
        }
        return null;
    }

    /** @return {@code value} 是否为映射类 {@code type} 的实例；类缺失时返回 false */
    public boolean isInstance(Object value, ClassType type) {
        Class<?> resolved = type(type);
        return resolved != null && resolved.isInstance(value);
    }

    /** @return 当前是否处于世界中（本地玩家已可用） */
    public boolean inWorld() {
        return player() != null;
    }

    /**
     * 构造方块坐标。
     *
     * <p>方块坐标没有可用的静态工厂映射，但 {@code (int,int,int)} 构造器跨版本稳定；
     * 仅在首次调用时解析，失败后不缓存，以便类晚加载时重试。
     *
     * @return 坐标对象；类/构造器缺失时返回 {@code null}
     */
    public Object blockPos(int x, int y, int z) {
        java.lang.reflect.Constructor<?> constructor = blockPosConstructor;
        if (constructor == null) {
            Class<?> type = type(ClassType.BLOCK_POS);
            if (type == null) {
                return null;
            }
            try {
                constructor = type.getDeclaredConstructor(int.class, int.class, int.class);
                constructor.setAccessible(true);
                blockPosConstructor = constructor;
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            return constructor.newInstance(x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按实参形状构造映射对象。构造器名不受混淆影响，但参数类型会随版本变化，因此只接受唯一兼容形状。
     * 成功句柄缓存；失败不缓存，以便类晚加载后重试。
     */
    public Object construct(ClassType wanted, Object... args) {
        Class<?> type = type(wanted);
        if (type == null) {
            return null;
        }
        Object[] actual = args == null ? new Object[0] : args;
        ConstructorKey key = new ConstructorKey(type, describeArgs(actual));
        java.lang.reflect.Constructor<?> constructor = constructorCache.get(key);
        if (constructor == null) {
            java.lang.reflect.Constructor<?> compatible = null;
            java.lang.reflect.Constructor<?> exact = null;
            for (java.lang.reflect.Constructor<?> candidate : type.getDeclaredConstructors()) {
                Class<?>[] parameters = candidate.getParameterTypes();
                if (parameters.length != actual.length) {
                    continue;
                }
                boolean matches = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (!isCompatible(parameters[i], actual[i])) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) {
                    continue;
                }
                if (exactMatch(parameters, actual)) {
                    exact = candidate;
                    break;
                }
                if (compatible != null) {
                    // 有歧义时不猜构造器，避免把相邻版本的新参数静默填错。
                    return null;
                }
                compatible = candidate;
            }
            constructor = exact != null ? exact : compatible;
            if (constructor == null) {
                return null;
            }
            try {
                constructor.setAccessible(true);
            } catch (Throwable t) {
                return null;
            }
            constructorCache.putIfAbsent(key, constructor);
        }
        try {
            return constructor.newInstance(actual);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取指定方块坐标的方块状态；世界未就绪时返回 {@code null}。 */
    public Object blockStateAt(int x, int y, int z) {
        Object world = world();
        Object position = blockPos(x, y, z);
        if (world == null || position == null) {
            return null;
        }
        Object state = callMapped(world, ClassType.CLIENT_LEVEL, "getBlockState", position);
        if (state == null) {
            state = callMapped(world, ClassType.LEVEL_READER, "getBlockState", position);
        }
        if (state == null) {
            state = callMapped(world, ClassType.LEVEL, "getBlockState", position);
        }
        return state;
    }

    /** 客户端本地把坐标设为空气；用于 PacketMine 的预测性移除。 */
    public boolean setAirBlock(int x, int y, int z) {
        Object air = staticField(ClassType.BLOCKS, "AIR");
        Object state = callMapped(air, ClassType.BLOCK, "getDefaultState");
        return callMappedStatus(world(), ClassType.LEVEL, "setBlock", blockPos(x, y, z), state, 3);
    }

    /** 设置客户端天气强度。 */
    public boolean setWeather(float rain, float thunder) {
        Object world = world();
        boolean rainSet = writeField(world, ClassType.CLIENT_LEVEL, "rainLevel", rain);
        boolean thunderSet = writeField(world, ClassType.CLIENT_LEVEL, "thunderLevel", thunder);
        if (!rainSet) {
            rainSet = callMappedStatus(world, ClassType.CLIENT_LEVEL, "setRainLevel", rain);
        }
        if (!thunderSet) {
            thunderSet = callMappedStatus(world, ClassType.CLIENT_LEVEL, "setThunderLevel", thunder);
        }
        return rainSet && thunderSet;
    }

    /** 设置客户端世界时间。 */
    public boolean setDayTime(long time) {
        Object world = world();
        if (writeField(world, ClassType.CLIENT_LEVEL, "dayTime", time)) {
            return true;
        }
        Object data = readField(world, ClassType.CLIENT_LEVEL, "levelData");
        return callMappedStatus(data, ClassType.LEVEL_DATA, "setDayTime", time);
    }

    /** 清除本地点击/跳跃延迟字段。 */
    public void clearInputDelays() {
        writeField(minecraft(), ClassType.MINECRAFT, "missTime", 0);
        writeField(player(), ClassType.LIVING_ENTITY, "jumpTicks", 0);
    }

    /** 当前容器界面悬停槽的菜单 index；未悬停或不是容器界面时为 -1。 */
    public int hoveredSlotIndex() {
        Object hovered = readField(currentScreen(), ClassType.CONTAINER_SCREEN, "hoveredSlot");
        Number index = number(readField(hovered, ClassType.SLOT, "index"));
        return index == null ? -1 : index.intValue();
    }

    /** 当前显示计分板的标题和玩家分数行。 */
    public java.util.List<String> scoreboardLines() {
        Object board = callMapped(world(), ClassType.CLIENT_LEVEL, "getScoreboard");
        Object objective = callMapped(board, ClassType.SCOREBOARD, "getDisplayObjective", 1);
        if (objective == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> lines = new java.util.ArrayList<String>();
        String title = componentText(callMapped(objective, ClassType.OBJECTIVE, "getDisplayName"));
        lines.add(title == null ? "Scoreboard" : title);
        Object scores = callMapped(board, ClassType.SCOREBOARD, "getPlayerScores", objective);
        if (scores instanceof Iterable<?>) {
            for (Object score : (Iterable<?>) scores) {
                Object owner = callMapped(score, ClassType.SCORE, "getOwner");
                Number points = number(callMapped(score, ClassType.SCORE, "getScore"));
                if (owner != null && points != null) {
                    lines.add(owner + " " + points.intValue());
                }
            }
        }
        return lines;
    }

    /** 读取映射类静态字段。 */
    public Object staticField(ClassType owner, String canonicalField) {
        Class<?> type = type(owner);
        if (type == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = type.getDeclaredField(mapping.fieldName(owner, canonicalField));
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写 Options 中 Boolean/Number 或 OptionInstance 值。 */
    public boolean writeOption(String canonicalField, Object value) {
        Object options = readField(minecraft(), ClassType.MINECRAFT, "options");
        Object field = readField(options, ClassType.OPTIONS, canonicalField);
        if (field == null) {
            return false;
        }
        if (field instanceof Boolean || field instanceof Number) {
            return writeField(options, ClassType.OPTIONS, canonicalField, value);
        }
        return callMappedStatus(field, ClassType.OPTION_INSTANCE, "set", value);
    }

    /** 当前人称：0=第一人称，1=第三人称背面；未知为 -1。 */
    public int perspective() {
        Object options = readField(minecraft(), ClassType.MINECRAFT, "options");
        Number legacy = number(readField(options, ClassType.OPTIONS, "thirdPersonView"));
        if (legacy != null) {
            return legacy.intValue() == 0 ? 0 : 1;
        }
        Object option = readField(options, ClassType.OPTIONS, "perspective");
        Object value = callMapped(option, ClassType.OPTION_INSTANCE, "get");
        return value != null && String.valueOf(value).contains("THIRD") ? 1 : 0;
    }

    /** 设置第一/第三人称并保留调用结果。 */
    public boolean setPerspective(int perspective) {
        Object options = readField(minecraft(), ClassType.MINECRAFT, "options");
        Number legacy = number(readField(options, ClassType.OPTIONS, "thirdPersonView"));
        if (legacy != null) {
            return writeField(options, ClassType.OPTIONS, "thirdPersonView", perspective);
        }
        Object option = readField(options, ClassType.OPTIONS, "perspective");
        Object value = staticField(ClassType.PERSPECTIVE,
                perspective == 0 ? "FIRST_PERSON" : "THIRD_PERSON_BACK");
        return callMappedStatus(option, ClassType.OPTION_INSTANCE, "setValue", value);
    }

    /** 旧版 Timer 的倍率字段；现代字段不存在时由 ASM delta 钩子接管。 */
    public boolean setTimerSpeed(float speed) {
        Object tracker = readField(minecraft(), ClassType.MINECRAFT, "deltaTracker");
        return writeField(tracker, ClassType.DELTA_TRACKER, "timerSpeed", speed);
    }

    /** 直接把移动意图写入 Input/ClientInput；用于 GUI 打开时的 InvWalk。 */
    public boolean applyMovementInput(Object input, float forward, float strafe,
                                      boolean jump, boolean sneak, boolean sprint) {
        if (input == null) {
            return false;
        }
        Object keys = readField(input, ClassType.CLIENT_INPUT, "keyPresses");
        if (keys != null) {
            Object nextKeys = construct(ClassType.PLAYER_INPUT, forward > 0.0f, forward < 0.0f,
                    strafe > 0.0f, strafe < 0.0f, jump, sneak, sprint);
            Object move = construct(ClassType.VEC2, strafe, forward);
            return nextKeys != null && move != null
                    && writeField(input, ClassType.CLIENT_INPUT, "keyPresses", nextKeys)
                    && writeField(input, ClassType.CLIENT_INPUT, "moveVector", move);
        }
        boolean written = writeField(input, ClassType.INPUT, "forwardImpulse", forward)
                && writeField(input, ClassType.INPUT, "leftImpulse", strafe)
                && writeField(input, ClassType.INPUT, "jumping", jump)
                && writeField(input, ClassType.INPUT, "shiftKeyDown", sneak);
        callMapped(player(), ClassType.ENTITY, "setSprinting", sprint);
        return written;
    }

    /** 当前界面标题纯文本；无界面时为空字符串。 */
    public String screenTitle() {
        return componentText(callMapped(currentScreen(), ClassType.SCREEN, "getTitle"));
    }

    /** 当前原生界面；未打开时为 null。 */
    public Object currentScreen() {
        Object minecraft = minecraft();
        Object current = readField(minecraft, ClassType.MINECRAFT, "currentScreen");
        return current != null ? current : readField(minecraft, ClassType.MINECRAFT, "screen");
    }

    /** 本地玩家 Input 对象。 */
    public Object movementInput() {
        return readField(player(), ClassType.LOCAL_PLAYER, "input");
    }

    /** 请求全部区块重建；XRay 状态改变后调用。 */
    public boolean reloadChunks() {
        Object renderer = readField(minecraft(), ClassType.MINECRAFT, "levelRenderer");
        return callMappedStatus(renderer, ClassType.LEVEL_RENDERER, "allChanged");
    }

    /** @return 当前客户端世界；未进入世界时为 {@code null} */
    public Object world() {
        Object minecraft = minecraft();
        return minecraft == null ? null : readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /**
     * 方块状态是否为空气。
     *
     * <p>1.8.9 的 {@code IBlockState} 没有 {@code isAir()}，此时按方块类型判定；两条路都
     * 不可用时保守返回 {@code false}，绝不把未知状态当成可放置位置。
     */
    public boolean isAir(Object blockState) {
        if (blockState == null) {
            return false;
        }
        Object air = callMapped(blockState, ClassType.BLOCK_STATE, "isAir");
        if (air instanceof Boolean) {
            return (Boolean) air;
        }
        Object block = callMapped(blockState, ClassType.BLOCK_STATE, "getBlock");
        return block != null && isInstance(block, ClassType.AIR_BLOCK);
    }

    /** @return 当前客户端包监听器；旧版同样从 LocalPlayer.connection/sendQueue 读取。 */
    public Object connection() {
        Object player = player();
        return player == null ? null : readField(player, ClassType.LOCAL_PLAYER, "connection");
    }

    /** 在当前 HUD 显示本地消息，不发送到服务器。 */
    public boolean localMessage(String text) {
        Object gui = readField(minecraft(), ClassType.MINECRAFT, "gui");
        Object component = callStaticMapped(ClassType.COMPONENT, "literal", text);
        return callMappedStatus(gui, ClassType.HUD, "displayClientMessage", component, false);
    }

    /** 播放原版声音常量；缺失的版本/声音返回 false。 */
    public boolean playSound(String soundField, float volume, float pitch) {
        Object sound = staticField(ClassType.SOUND_EVENTS, soundField);
        return callMappedStatus(player(), ClassType.PLAYER, "playSound", sound, volume, pitch);
    }

    /** 判断准星结果是否为实体；旧版没有 EntityHitResult 子类时读 typeOfHit/type。 */
    public boolean isEntityHit(Object hitResult) {
        if (hitResult == null) {
            return false;
        }
        if (isInstance(hitResult, ClassType.ENTITY_HIT_RESULT)) {
            return true;
        }
        Object type = callMapped(hitResult, ClassType.HIT_RESULT, "getType");
        if (type == null) {
            type = readField(hitResult, ClassType.HIT_RESULT, "type");
        }
        return type != null && "ENTITY".equals(String.valueOf(type));
    }

    /** 命中点世界坐标；失败时返回 null。 */
    public Object hitLocation(Object hitResult) {
        Object location = callMapped(hitResult, ClassType.HIT_RESULT, "getLocation");
        return location != null ? location : readField(hitResult, ClassType.HIT_RESULT, "location");
    }

    /** 实体命中的目标；旧版从 MovingObjectPosition.entityHit 字段读取。 */
    public Object hitEntity(Object hitResult) {
        Object entity = callMapped(hitResult, ClassType.ENTITY_HIT_RESULT, "getEntity");
        return entity != null ? entity : readField(hitResult, ClassType.HIT_RESULT, "entity");
    }

    /** 方块命中的朝向；旧版从 sideHit 字段读取。 */
    public Object hitDirection(Object hitResult) {
        Object direction = callMapped(hitResult, ClassType.BLOCK_HIT_RESULT, "getDirection");
        return direction != null ? direction : readField(hitResult, ClassType.BLOCK_HIT_RESULT, "direction");
    }

    /** 判断准星结果是否为方块；MISS 与其它未知类型均返回 false。 */
    public boolean isBlockHit(Object hitResult) {
        if (hitResult == null) {
            return false;
        }
        Object type = callMapped(hitResult, ClassType.HIT_RESULT, "getType");
        if (type == null) {
            type = readField(hitResult, ClassType.HIT_RESULT, "type");
        }
        if (type == null) {
            return isInstance(hitResult, ClassType.BLOCK_HIT_RESULT);
        }
        return "BLOCK".equals(String.valueOf(type));
    }

    /** 实体是否能通过游戏射线看见目标；缺失时保守返回 false。 */
    public boolean hasLineOfSight(Object source, Object target) {
        Object result = callMapped(source, ClassType.LIVING_ENTITY, "hasLineOfSight", target);
        return result instanceof Boolean && (Boolean) result;
    }

    /** 是否同队/同阵营；无队伍时不得把两个空队伍误判为队友。 */
    public boolean isAllied(Object first, Object second) {
        if (first == null || second == null) {
            return false;
        }
        Object firstTeam = teamOf(first);
        Object secondTeam = teamOf(second);
        if (firstTeam == null || secondTeam == null) {
            return false;
        }
        Object result = callMapped(first, ClassType.ENTITY, "isAlliedTo", second);
        if (!(result instanceof Boolean)) {
            result = callMapped(first, ClassType.PLAYER, "isAlliedTo", second);
        }
        return result instanceof Boolean && (Boolean) result;
    }

    /** 1.8.9 的队伍方法声明在 EntityPlayer；其它版本直接走 Entity。 */
    private Object teamOf(Object entity) {
        Object team = callMapped(entity, ClassType.ENTITY, "getTeam");
        return team != null ? team : callMapped(entity, ClassType.PLAYER, "getTeam");
    }

    /** 方块更新包携带的位置。 */
    public int[] blockUpdatePosition(Object packet) {
        Object pos = callMapped(packet, ClassType.CLIENTBOUND_BLOCK_UPDATE, "getPos");
        Number x = number(readField(pos, ClassType.VEC3_I, "x"));
        Number y = number(readField(pos, ClassType.VEC3_I, "y"));
        Number z = number(readField(pos, ClassType.VEC3_I, "z"));
        return x == null || y == null || z == null ? null
                : new int[]{x.intValue(), y.intValue(), z.intValue()};
    }

    /** 携带槽位包的槽号；不可读取时为 -1。 */
    public int carriedSlot(Object packet) {
        Number slot = number(callMapped(packet, ClassType.SERVERBOUND_SET_CARRIED_ITEM, "getSlot"));
        return slot == null ? -1 : slot.intValue();
    }

    /** 系统聊天包纯文本。 */
    public String systemChatText(Object packet) {
        return componentText(callMapped(packet, ClassType.CLIENTBOUND_SYSTEM_CHAT, "content"));
    }

    /** 把位置包中的服务端朝向改回本地朝向，保留坐标与落地状态。 */
    public boolean preserveLocalRotation(Object packet, float yaw, float pitch) {
        boolean yawWritten = writeField(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "yRot", yaw);
        boolean pitchWritten = writeField(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "xRot", pitch);
        return yawWritten && pitchWritten;
    }

    /** 构造当前输入或空输入包；旧版无此包时返回 null。 */
    public Object inputPacket(boolean empty) {
        Object input = empty ? staticField(ClassType.PLAYER_INPUT, "EMPTY")
                : readField(player(), ClassType.LOCAL_PLAYER, "lastSentInput");
        return input == null ? null : construct(ClassType.SERVERBOUND_PLAYER_INPUT, input);
    }

    /** 本地玩家当前药水效果集合。 */
    public Object activeEffects() {
        return callMapped(player(), ClassType.LIVING_ENTITY, "getActiveEffects");
    }

    /** 只移除 MobEffects 中指定的一种效果；兼容旧版 int id 与现代 Holder 参数。 */
    public boolean removeEffect(String canonicalField) {
        Object player = player();
        Class<?> owner = type(ClassType.MOB_EFFECTS);
        if (player == null || owner == null) {
            return false;
        }
        try {
            String fieldName = mapping.fieldName(ClassType.MOB_EFFECTS, canonicalField);
            java.lang.reflect.Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object effect = field.get(null);
            Number legacyId = number(readField(effect, ClassType.MOB_EFFECT, "id"));
            for (java.lang.reflect.Method method : player.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1) {
                    continue;
                }
                Object argument = parameters[0].isInstance(effect) ? effect
                        : parameters[0] == int.class && legacyId != null ? legacyId.intValue() : null;
                if (argument == null) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(player, argument);
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** 鱼钩当前钓到的实体；没有时为 null。 */
    public Object fishingCaughtEntity() {
        return readField(fishingHook(), ClassType.FISHING_HOOK, "caughtEntity");
    }

    /** 本地玩家当前鱼钩；未抛出或字段不可用时为 null。 */
    public Object fishingHook() {
        return readField(player(), ClassType.PLAYER, "fishing");
    }

    /** 当前世界用于渲染的实体列表/数组；不可读取时为 null。 */
    public Object entities() {
        Object world = world();
        Object result = callMapped(world, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        if (result == null) {
            result = callMapped(world, ClassType.MULTIPLAYER_LEVEL, "entitiesForRendering");
        }
        if (result == null) {
            result = callMapped(world, ClassType.LEVEL, "entitiesForRendering");
        }
        return result;
    }

    /** 任意 BlockPos/Vec3i 对象的整数坐标。 */
    public int[] blockCoordinates(Object pos) {
        Number x = number(readField(pos, ClassType.VEC3_I, "x"));
        Number y = number(readField(pos, ClassType.VEC3_I, "y"));
        Number z = number(readField(pos, ClassType.VEC3_I, "z"));
        return x == null || y == null || z == null ? null
                : new int[]{x.intValue(), y.intValue(), z.intValue()};
    }

    /** 准星方块命中的整数坐标；非方块命中或坐标缺失时为 null。 */
    public int[] hitBlockPosition(Object hit) {
        Object pos = callMapped(hit, ClassType.BLOCK_HIT_RESULT, "getBlockPos");
        Number x = number(readField(pos, ClassType.VEC3_I, "x"));
        Number y = number(readField(pos, ClassType.VEC3_I, "y"));
        Number z = number(readField(pos, ClassType.VEC3_I, "z"));
        return x == null || y == null || z == null ? null
                : new int[]{x.intValue(), y.intValue(), z.intValue()};
    }

    /** 准星当前命中的方块状态。 */
    public Object hitBlockState(Object hit) {
        int[] pos = hitBlockPosition(hit);
        return pos == null ? null : blockStateAt(pos[0], pos[1], pos[2]);
    }

    /** 方块状态的稳定描述 id。 */
    public String blockDescription(Object blockState) {
        Object block = callMapped(blockState, ClassType.BLOCK_STATE, "getBlock");
        Object id = callMapped(block, ClassType.BLOCK, "getDescriptionId");
        return id == null ? "" : String.valueOf(id);
    }

    /** 创建并加入一个本地 RemotePlayer；失败时返回 null 且不改变世界。 */
    public Object spawnFakePlayer(String name, boolean copyInventory) {
        java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(
                String.valueOf(name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Object profile = construct(ClassType.GAME_PROFILE, uuid, name);
        Object entity = construct(ClassType.REMOTE_PLAYER, world(), profile);
        if (entity == null) {
            return null;
        }
        double[] position = entityPosition(player());
        if (position == null) {
            return null;
        }
        setPosition(entity, position[0], position[1], position[2]);
        writeField(entity, ClassType.ENTITY, "id", -66123666);
        writeField(entity, ClassType.ENTITY, "yRot", readField(player(), ClassType.ENTITY, "yRot"));
        writeField(entity, ClassType.ENTITY, "xRot", readField(player(), ClassType.ENTITY, "xRot"));
        if (copyInventory) {
            for (int slot = 0; slot < 36; slot++) {
                Object targetInventory = readField(entity, ClassType.PLAYER, "inventory");
                callMappedStatus(targetInventory, ClassType.INVENTORY, "setItem", slot, inventoryStack(slot));
            }
        }
        return callMappedStatus(world(), ClassType.CLIENT_LEVEL, "addEntity", -66123666, entity) ? entity : null;
    }

    /** 移除本地伪造实体，同时兼容现代 remove 与旧版 setDead。 */
    public void removeFakePlayer(Object entity) {
        if (entity != null && !callMappedStatus(entity, ClassType.ENTITY, "remove")) {
            callMappedStatus(entity, ClassType.ENTITY, "setDead");
        }
    }

    /** 直接设置实体位置，供 Freecam 停用恢复。 */
    public boolean setPosition(Object entity, double x, double y, double z) {
        return callMappedStatus(entity, ClassType.ENTITY, "setPos", x, y, z);
    }

    /** 写入实体 noPhysics，返回是否实际写入。 */
    public boolean setNoPhysics(Object entity, boolean value) {
        return writeField(entity, ClassType.ENTITY, "noPhysics", value);
    }

    /** 写入实体碰撞/命中盒。 */
    public boolean setEntityBox(Object entity, Object box) {
        return writeField(entity, ClassType.ENTITY, "bb", box);
    }

    /** 实体累计下落距离。 */
    public float fallDistance(Object entity) {
        Number value = number(readField(entity, ClassType.ENTITY, "fallDistance"));
        return value == null ? 0.0f : value.floatValue();
    }

    /** 实体是否正在使用物品。 */
    public boolean usingItem(Object entity) {
        Object result = callMapped(entity, ClassType.LIVING_ENTITY, "isUsingItem");
        if (!(result instanceof Boolean)) {
            result = callMapped(entity, ClassType.PLAYER, "isUsingItem");
        }
        return Boolean.TRUE.equals(result);
    }

    /** 实体纯文本名称；组件与旧版 String 均可读取。 */
    public String entityName(Object entity) {
        String text = componentText(callMapped(entity, ClassType.ENTITY, "getName"));
        if (text == null || text.isEmpty()) {
            text = componentText(callMapped(entity, ClassType.ENTITY, "getDisplayName"));
        }
        if (text == null || text.isEmpty()) {
            text = componentText(callMapped(entity, ClassType.ENTITY, "getCustomName"));
        }
        return text == null ? "" : text;
    }

    /** 实体稳定 UUID；不可读取时为 null。 */
    public java.util.UUID entityUuid(Object entity) {
        Object id = callMapped(entity, ClassType.ENTITY, "getUUID");
        return id instanceof java.util.UUID ? (java.util.UUID) id : null;
    }

    /** 当前 Tab 列表中的 UUID 集合；不可读取时为空集。 */
    public java.util.Set<java.util.UUID> onlinePlayerUuids() {
        Object entries = callMapped(connection(), ClassType.CLIENT_PACKET_LISTENER, "getOnlinePlayers");
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<java.util.UUID>();
        if (!(entries instanceof Iterable)) {
            return ids;
        }
        for (Object entry : (Iterable<?>) entries) {
            Object profile = callMapped(entry, ClassType.PLAYER_INFO, "getProfile");
            Object id = callMapped(profile, ClassType.GAME_PROFILE, "getId");
            if (id instanceof java.util.UUID) {
                ids.add((java.util.UUID) id);
            }
        }
        return ids;
    }

    /** 当前 Tab 列表 UUID→名称快照。 */
    public java.util.Map<java.util.UUID, String> onlinePlayerNames() {
        java.util.Map<java.util.UUID, String> names = new java.util.HashMap<java.util.UUID, String>();
        Object entries = callMapped(connection(), ClassType.CLIENT_PACKET_LISTENER, "getOnlinePlayers");
        if (!(entries instanceof Iterable)) {
            return names;
        }
        for (Object entry : (Iterable<?>) entries) {
            Object profile = callMapped(entry, ClassType.PLAYER_INFO, "getProfile");
            Object id = callMapped(profile, ClassType.GAME_PROFILE, "getId");
            Object name = callMapped(profile, ClassType.GAME_PROFILE, "getName");
            if (id instanceof java.util.UUID && name != null) {
                names.put((java.util.UUID) id, String.valueOf(name));
            }
        }
        return names;
    }

    /** 指定 UUID 的 Tab 游戏模式；不可读取时为空字符串。 */
    public String playerGameMode(java.util.UUID uuid) {
        Object entries = callMapped(connection(), ClassType.CLIENT_PACKET_LISTENER, "getOnlinePlayers");
        if (!(entries instanceof Iterable)) {
            return "";
        }
        for (Object entry : (Iterable<?>) entries) {
            Object profile = callMapped(entry, ClassType.PLAYER_INFO, "getProfile");
            Object id = callMapped(profile, ClassType.GAME_PROFILE, "getId");
            if (uuid != null && uuid.equals(id)) {
                Object gameMode = callMapped(entry, ClassType.PLAYER_INFO, "getGameMode");
                return gameMode == null ? "" : String.valueOf(gameMode);
            }
        }
        return "";
    }

    /** 实体网络 id；不可读取时为 -1。 */
    public int entityId(Object entity) {
        Number id = number(callMapped(entity, ClassType.ENTITY, "getId"));
        return id == null ? -1 : id.intValue();
    }

    /** 活体当前血量；非活体或不可读取时为 0。 */
    public float health(Object entity) {
        Number health = number(callMapped(entity, ClassType.LIVING_ENTITY, "getHealth"));
        return health == null ? 0.0f : health.floatValue();
    }

    /** 活体最大血量；不可读取时为 0。 */
    public float maxHealth(Object entity) {
        Number health = number(callMapped(entity, ClassType.LIVING_ENTITY, "getMaxHealth"));
        return health == null ? 0.0f : health.floatValue();
    }

    /** 活体受伤无敌 tick；不可读取时为 0。 */
    public int hurtTime(Object entity) {
        Number value = number(readField(entity, ClassType.LIVING_ENTITY, "hurtTime"));
        return value == null ? 0 : value.intValue();
    }

    /** 实体脚部世界坐标；任一轴缺失时返回 null。 */
    public double[] entityPosition(Object entity) {
        Object position = callMapped(entity, ClassType.ENTITY, "position");
        Number x = number(readField(position, ClassType.VEC3, "x"));
        Number y = number(readField(position, ClassType.VEC3, "y"));
        Number z = number(readField(position, ClassType.VEC3, "z"));
        return x == null || y == null || z == null ? null
                : new double[]{x.doubleValue(), y.doubleValue(), z.doubleValue()};
    }

    /** 实体三轴速度；不可读取时返回零向量。 */
    public double[] entityVelocity(Object entity) {
        Object delta = callMapped(entity, ClassType.ENTITY, "getDeltaMovement");
        Number x = number(readField(delta, ClassType.VEC3, "x"));
        Number y = number(readField(delta, ClassType.VEC3, "y"));
        Number z = number(readField(delta, ClassType.VEC3, "z"));
        if (x != null && y != null && z != null) {
            return new double[]{x.doubleValue(), y.doubleValue(), z.doubleValue()};
        }
        Number legacyX = number(readField(entity, ClassType.ENTITY, "motionX"));
        Number legacyY = number(readField(entity, ClassType.ENTITY, "motionY"));
        Number legacyZ = number(readField(entity, ClassType.ENTITY, "motionZ"));
        return new double[]{legacyX == null ? 0.0 : legacyX.doubleValue(),
                legacyY == null ? 0.0 : legacyY.doubleValue(),
                legacyZ == null ? 0.0 : legacyZ.doubleValue()};
    }

    /** 两实体脚部中心距离；不可读取时为正无穷。 */
    public double distance(Object first, Object second) {
        double[] a = entityPosition(first);
        double[] b = entityPosition(second);
        if (a == null || b == null) {
            return Double.POSITIVE_INFINITY;
        }
        double x = a[0] - b[0];
        double y = a[1] - b[1];
        double z = a[2] - b[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** 队伍 RGB；旧版返回数字、26.x 返回带 rgb 字段的 TeamColor，未知为 -1。 */
    public int teamColor(Object entity) {
        Object color = callMapped(entity, ClassType.ENTITY, "getTeamColor");
        if (color == null) {
            Object team = teamOf(entity);
            color = callMapped(team, ClassType.PLAYER_TEAM, "getColor");
        }
        if (color instanceof Number) {
            return ((Number) color).intValue();
        }
        Number rgb = number(callMapped(color, ClassType.TEAM_COLOR, "rgb"));
        if (rgb == null) {
            rgb = color == null ? null : number(readField(color, ClassType.TEAM_COLOR, "rgb"));
        }
        if (rgb != null) {
            return rgb.intValue();
        }
        Object legacy = callMapped(color, ClassType.TEAM_COLOR, "getColorValue");
        if (!(legacy instanceof Number)) {
            legacy = color == null ? null : readField(color, ClassType.TEAM_COLOR, "colorValue");
        }
        return legacy instanceof Number ? ((Number) legacy).intValue() : -1;
    }

    /** 是否为 RELEASE_USE_ITEM 出栈包。 */
    public boolean isReleaseUseItem(Object packet) {
        Object action = callMapped(packet, ClassType.SERVERBOUND_PLAYER_ACTION, "getAction");
        return action != null && "RELEASE_USE_ITEM".equals(String.valueOf(action));
    }

    /** 实体事件包的事件 id 与实体 id；不可读取时均为 -1。 */
    public int[] entityEventData(Object packet) {
        if (!isInstance(packet, ClassType.CLIENTBOUND_ENTITY_EVENT)) {
            return null;
        }
        Number event = number(callMapped(packet, ClassType.CLIENTBOUND_ENTITY_EVENT, "getEventId"));
        Number id = number(callMapped(packet, ClassType.CLIENTBOUND_ENTITY_EVENT, "getEntityId"));
        return event == null || id == null ? null : new int[]{event.intValue(), id.intValue()};
    }

    /** 实体是否在水中；能力缺失时保守返回 false。 */
    public boolean inWater(Object entity) {
        return Boolean.TRUE.equals(callMapped(entity, ClassType.ENTITY, "isInWater"));
    }

    /** 速度包四元组：entityId、xa、ya、za；非速度包或字段缺失时返回 null。 */
    public int[] velocityData(Object packet) {
        if (!isInstance(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY)) {
            return null;
        }
        Number id = number(callMapped(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "getId"));
        Number x = number(callMapped(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "getXa"));
        Number y = number(callMapped(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "getYa"));
        Number z = number(callMapped(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "getZa"));
        if (id == null) {
            id = number(readField(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "id"));
        }
        if (x == null) {
            x = number(readField(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "xa"));
        }
        if (y == null) {
            y = number(readField(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "ya"));
        }
        if (z == null) {
            z = number(readField(packet, ClassType.CLIENTBOUND_ENTITY_VELOCITY, "za"));
        }
        return id == null || x == null || y == null || z == null ? null
                : new int[]{id.intValue(), x.intValue(), y.intValue(), z.intValue()};
    }

    /** 当前包是否为本地玩家的速度包。 */
    public boolean isLocalVelocity(Object packet) {
        int[] data = velocityData(packet);
        Number playerId = number(callMapped(player(), ClassType.ENTITY, "getId"));
        return data != null && playerId != null && data[0] == playerId.intValue();
    }

    /** 当前背包对象。 */
    public Object inventory() {
        Object player = player();
        return player == null ? null : readField(player, ClassType.PLAYER, "inventory");
    }

    /** 读取快捷栏物品；槽位越界或背包未就绪时返回 null。 */
    public Object hotbarStack(int slot) {
        Object inventory = inventory();
        return inventory == null || slot < 0 || slot > 8 ? null
                : callMapped(inventory, ClassType.INVENTORY, "getItem", slot);
    }

    /** 读取背包/装备槽；槽位非法或背包未就绪时返回 null。 */
    public Object inventoryStack(int slot) {
        Object inventory = inventory();
        return inventory == null || slot < 0 ? null
                : callMapped(inventory, ClassType.INVENTORY, "getItem", slot);
    }

    /** 当前菜单是否不是玩家自己的背包菜单。 */
    public boolean containerOpen() {
        Object player = player();
        if (player == null) {
            return false;
        }
        Object menu = readField(player, ClassType.PLAYER, "containerMenu");
        Object inventoryMenu = readField(player, ClassType.PLAYER, "inventoryMenu");
        return menu != null && menu != inventoryMenu;
    }

    /** 当前菜单的物品列表；现代读 getItems()，旧版读 inventoryItemStacks 字段。 */
    public Object containerItems() {
        Object player = player();
        Object menu = player == null ? null : readField(player, ClassType.PLAYER, "containerMenu");
        Object items = callMapped(menu, ClassType.ABSTRACT_CONTAINER_MENU, "getItems");
        return items != null ? items : readField(menu, ClassType.ABSTRACT_CONTAINER_MENU, "items");
    }

    /** 读取当前菜单槽位物品；越界返回 null。 */
    public Object containerStack(int slot) {
        Object items = containerItems();
        if (!(items instanceof java.util.List) || slot < 0) {
            return null;
        }
        java.util.List<?> list = (java.util.List<?>) items;
        return slot < list.size() ? list.get(slot) : null;
    }

    /** 当前菜单槽位数量；不可读取时为 0。 */
    public int containerSize() {
        Object items = containerItems();
        return items instanceof java.util.List ? ((java.util.List<?>) items).size() : 0;
    }

    /** 物品已损耗耐久。 */
    public int itemDamage(Object stack) {
        Number value = number(callMapped(stack, ClassType.ITEM_STACK, "getDamageValue"));
        return value == null ? 0 : value.intValue();
    }

    /** 物品最大耐久；不可损坏物品通常返回 0。 */
    public int itemMaxDamage(Object stack) {
        Number value = number(callMapped(stack, ClassType.ITEM_STACK, "getMaxDamage"));
        return value == null ? 0 : value.intValue();
    }

    /** 物品显示名；组件与旧版 String 都统一为纯文本。 */
    public String itemDisplayName(Object stack) {
        Object name = callMapped(stack, ClassType.ITEM_STACK, "getDisplayName");
        String text = componentText(name);
        return text == null ? itemDescription(stack) : text;
    }

    /** 实体指定装备槽物品；旧版回退 getCurrentArmor(0..3)。 */
    public Object equipmentStack(Object entity, int armorSlot) {
        String[] names = {"FEET", "LEGS", "CHEST", "HEAD"};
        if (armorSlot < 0 || armorSlot >= names.length) {
            return null;
        }
        Object slot = staticField(ClassType.EQUIPMENT_SLOT, names[armorSlot]);
        Object stack = callMapped(entity, ClassType.LIVING_ENTITY, "getItemBySlot", slot);
        return stack != null ? stack : callMapped(entity, ClassType.LIVING_ENTITY, "getCurrentArmor", armorSlot);
    }

    /** 当前使用是否副手；无法读取时按主手处理。 */
    public boolean usingOffHand(Object entity) {
        Object hand = callMapped(entity, ClassType.LIVING_ENTITY, "getUsedItemHand");
        Object offHand = staticField(ClassType.INTERACTION_HAND, "OFF_HAND");
        return hand != null && hand == offHand;
    }

    /** 当前正在使用的物品栈。 */
    public Object usingItemStack(Object entity) {
        return callMapped(entity, ClassType.LIVING_ENTITY, "getUseItem");
    }

    /** 已使用 tick 数；由总时长减剩余时长得到。 */
    public int usingTicks(Object entity) {
        Object stack = usingItemStack(entity);
        Number duration = number(callMapped(stack, ClassType.ITEM_STACK, "getUseDuration"));
        Number remaining = number(callMapped(entity, ClassType.LIVING_ENTITY, "getUseItemRemainingTicks"));
        return duration == null || remaining == null ? 0
                : Math.max(0, duration.intValue() - remaining.intValue());
    }

    /** 写入原生使用键状态；自动弩释放等模块使用，不改模块绑定。 */
    public boolean setUseKey(boolean down) {
        Object options = readField(minecraft(), ClassType.MINECRAFT, "options");
        Object key = readField(options, ClassType.OPTIONS, "keyUse");
        return callMappedStatus(key, ClassType.KEY_MAPPING, "setDown", down);
    }

    /** 读取实体主/副手物品。 */
    public Object heldItem(Object entity, boolean offHand) {
        return callMapped(entity, ClassType.LIVING_ENTITY,
                offHand ? "getOffhandItem" : "getMainHandItem");
    }

    /** 掉落物携带的物品栈。 */
    public Object droppedItem(Object itemEntity) {
        return callMapped(itemEntity, ClassType.ITEM_ENTITY, "getItem");
    }

    /** 物品稳定描述 id；旧版退回 unlocalized name，无法读取时为空串。 */
    public String itemDescription(Object stack) {
        Object item = callMapped(stack, ClassType.ITEM_STACK, "getItem");
        Object description = callMapped(item, ClassType.ITEM, "getDescriptionId");
        return description == null ? "" : String.valueOf(description);
    }

    /** 按逗号/空白分隔的描述片段匹配物品；支持 OpenVape 配置中的复数类别词。 */
    public boolean itemMatches(Object stack, String rules) {
        String description = itemDescription(stack).toLowerCase(java.util.Locale.ROOT);
        if (description.isEmpty() || rules == null) {
            return false;
        }
        for (String part : rules.toLowerCase(java.util.Locale.ROOT).split("[,;\\s]+")) {
            String token = part.endsWith("s") && part.length() > 1
                    ? part.substring(0, part.length() - 1) : part;
            if (token.equals("block")
                    && isInstance(callMapped(stack, ClassType.ITEM_STACK, "getItem"), ClassType.BLOCK_ITEM)) {
                return true;
            }
            if (token.equals("food") && (description.contains("apple") || description.contains("bread")
                    || description.contains("pork") || description.contains("beef")
                    || description.contains("chicken") || description.contains("mutton")
                    || description.contains("cod") || description.contains("salmon")
                    || description.contains("carrot") || description.contains("potato")
                    || description.contains("stew") || description.contains("soup"))) {
                return true;
            }
            if (!token.isEmpty() && description.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 实体宽度；旧版没有 getter 时读取 width 字段。 */
    public double entityWidth(Object entity) {
        Number value = number(callMapped(entity, ClassType.ENTITY, "getBbWidth"));
        if (value == null) {
            value = number(readField(entity, ClassType.ENTITY, "width"));
        }
        return value == null ? 0.0 : value.doubleValue();
    }

    /** 实体高度；旧版没有 getter 时读取 height 字段。 */
    public double entityHeight(Object entity) {
        Number value = number(callMapped(entity, ClassType.ENTITY, "getBbHeight"));
        if (value == null) {
            value = number(readField(entity, ClassType.ENTITY, "height"));
        }
        return value == null ? 0.0 : value.doubleValue();
    }

    /** TNT 剩余引信刻；旧版直接读取 fuse 字段。 */
    public int tntFuse(Object primedTnt) {
        Number fuse = number(callMapped(primedTnt, ClassType.PRIMED_TNT, "getFuse"));
        if (fuse == null) {
            fuse = number(readField(primedTnt, ClassType.PRIMED_TNT, "fuse"));
        }
        return fuse == null ? 0 : fuse.intValue();
    }

    /** 物品数量；读取失败返回 0，调用方可与 isEmptyStack 一起判断。 */
    public int itemCount(Object stack) {
        Number count = number(callMapped(stack, ClassType.ITEM_STACK, "getCount"));
        if (count == null) {
            count = number(readField(stack, ClassType.ITEM_STACK, "count"));
        }
        return count == null ? 0 : count.intValue();
    }

    /** 物品最大堆叠数；缺失时返回 0。 */
    public int itemMaxStackSize(Object stack) {
        Number count = number(callMapped(stack, ClassType.ITEM_STACK, "getMaxStackSize"));
        return count == null ? 0 : count.intValue();
    }

    /** 空栈判断兼容 1.8.9 的 null ItemStack/数量 0 语义。 */
    public boolean isEmptyStack(Object stack) {
        if (stack == null) {
            return true;
        }
        Object empty = callMapped(stack, ClassType.ITEM_STACK, "isEmpty");
        if (empty instanceof Boolean) {
            return (Boolean) empty;
        }
        return itemCount(stack) <= 0 || callMapped(stack, ClassType.ITEM_STACK, "getItem") == null;
    }

    /** 方块是否可被放置替换；老版本缺少 state 级 API 时只允许空气，避免覆盖草/水等方块。 */
    public boolean canBeReplaced(Object blockState) {
        Object result = callMapped(blockState, ClassType.BLOCK_STATE, "canBeReplaced");
        return result instanceof Boolean ? (Boolean) result : isAir(blockState);
    }

    /** 盒体坐标的合法上限：单轴跨度超过它就是异常 AABB，绝不逐格采样。 */
    private static final double MAX_BOX_SPAN = 16.0;

    /** 实体盒子是否无碰撞；现代使用 Level.noCollision，旧版组合两个检查入口。 */
    public boolean noCollision(Object entity, Object box) {
        Object world = world();
        if (world == null || box == null) {
            return false;
        }
        Object result = callMapped(world, ClassType.LEVEL, "noCollision", entity, box);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        Object blockCollision = callMapped(world, ClassType.LEVEL, "checkBlockCollision", box);
        if (blockCollision instanceof Boolean) {
            // 1.8.9–1.12.2：实体检查必须是两参重载（把自己排除在外）。一参重载把盒子覆盖到的
            // 玩家本身算成碰撞，边缘检测因此恒为 false；映射表里该条已固定到两参 SRG。
            Object entityCollision = entity == null ? null
                    : callMapped(world, ClassType.LEVEL, "checkNoEntityCollision", box, entity);
            return legacyBoxFree((Boolean) blockCollision, entityCollision);
        }
        // 1.13+ 的 Level 只剩 noCollision(Entity)，旧的两个检查入口被移除；
        // 用已映射的方块状态逐格采样盒体覆盖的方块，避免边缘检测整代失效。
        return blockBoxFree(box);
    }

    /**
     * 旧版两入口合成：方块无碰撞且（读得到实体检查时）无实体碰撞。
     *
     * <p>实体检查读不到时只据方块判定——把"读不到"当成"有碰撞"会让 1.8.9–1.12.2 的
     * 边缘检测整代失效。
     */
    static boolean legacyBoxFree(boolean blockCollision, Object entityCollision) {
        if (blockCollision) {
            return false;
        }
        return !(entityCollision instanceof Boolean) || (Boolean) entityCollision;
    }

    /** 盒体覆盖的方块是否全部无碰撞；坐标不合法或任一格未加载时保守返回 false。 */
    private boolean blockBoxFree(Object box) {
        Number minX = number(readField(box, ClassType.AABB, "minX"));
        Number minY = number(readField(box, ClassType.AABB, "minY"));
        Number minZ = number(readField(box, ClassType.AABB, "minZ"));
        Number maxX = number(readField(box, ClassType.AABB, "maxX"));
        Number maxY = number(readField(box, ClassType.AABB, "maxY"));
        Number maxZ = number(readField(box, ClassType.AABB, "maxZ"));
        if (minX == null || minY == null || minZ == null
                || maxX == null || maxY == null || maxZ == null) {
            return false;
        }
        if (!boxSpanValid(minX.doubleValue(), minY.doubleValue(), minZ.doubleValue(),
                maxX.doubleValue(), maxY.doubleValue(), maxZ.doubleValue())) {
            return false;
        }
        int x0 = (int) Math.floor(minX.doubleValue() + 1.0E-7);
        int y0 = (int) Math.floor(minY.doubleValue() + 1.0E-7);
        int z0 = (int) Math.floor(minZ.doubleValue() + 1.0E-7);
        int x1 = (int) Math.floor(maxX.doubleValue() - 1.0E-7);
        int y1 = (int) Math.floor(maxY.doubleValue() - 1.0E-7);
        int z1 = (int) Math.floor(maxZ.doubleValue() - 1.0E-7);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Object state = blockStateAt(x, y, z);
                    if (state == null) {
                        // 未加载区块不能当成空位：那会把"读不到"判成"可以走"。
                        return false;
                    }
                    if (hasBlockCollision(state, blockPos(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 盒体坐标必须有限、有序且单轴跨度不超过 {@link #MAX_BOX_SPAN}。 */
    static boolean boxSpanValid(double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
        if (!finiteBoxCoordinate(minX) || !finiteBoxCoordinate(minY) || !finiteBoxCoordinate(minZ)
                || !finiteBoxCoordinate(maxX) || !finiteBoxCoordinate(maxY) || !finiteBoxCoordinate(maxZ)) {
            return false;
        }
        return maxX >= minX && maxY >= minY && maxZ >= minZ
                && maxX - minX <= MAX_BOX_SPAN
                && maxY - minY <= MAX_BOX_SPAN
                && maxZ - minZ <= MAX_BOX_SPAN;
    }

    private static boolean finiteBoxCoordinate(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * 方块是否有碰撞形状；形状面不可用时按"可替换方块无碰撞"判定。
     *
     * <p>草、水、火把、花都不是空气却没有碰撞：用 {@code !isAir} 兜底会把它们全算成碰撞，
     * 于是任何草地边缘都被判成安全。读不到形状时只认空气或可替换方块，其余保守算碰撞。
     */
    public boolean hasBlockCollision(Object blockState, Object blockPos) {
        if (blockState == null) {
            return true;
        }
        Object shape = callMapped(blockState, ClassType.BLOCK_STATE, "getCollisionShape",
                world(), blockPos);
        Object empty = callMapped(shape, ClassType.VOXEL_SHAPE, "isEmpty");
        if (empty instanceof Boolean) {
            return !(Boolean) empty;
        }
        if (isAir(blockState)) {
            return false;
        }
        Object replaceable = callMapped(blockState, ClassType.BLOCK_STATE, "canBeReplaced");
        return !(replaceable instanceof Boolean) || !(Boolean) replaceable;
    }

    /** 对手持 PoseStack 应用平移、缩放和 XYZ 轴旋转。 */
    public void transformPose(Object pose, double x, double y, double z,
                              double scaleX, double scaleY, double scaleZ,
                              double rotX, double rotY, double rotZ) {
        if (!callMappedStatus(pose, ClassType.POSE_STACK, "translate",
                (float) x, (float) y, (float) z)) {
            logThrottled("PoseStack#translate unavailable; hand transform skipped", null);
        }
        callMappedStatus(pose, ClassType.POSE_STACK, "scale", (float) scaleX, (float) scaleY, (float) scaleZ);
        float[] rotations = {(float) rotX, (float) rotY, (float) rotZ};
        for (int axis = 0; axis < 3; axis++) {
            if (rotations[axis] == 0.0f) {
                continue;
            }
            float ax = axis == 0 ? 1.0f : 0.0f;
            float ay = axis == 1 ? 1.0f : 0.0f;
            float az = axis == 2 ? 1.0f : 0.0f;
            Object angle = construct(ClassType.AXIS_ANGLE4F,
                    (float) Math.toRadians(rotations[axis]), ax, ay, az);
            Object quaternion = construct(ClassType.QUATERNIONF, angle);
            callMappedStatus(pose, ClassType.POSE_STACK, "mulPose", quaternion);
        }
    }

    public Object moveBox(Object box, double x, double y, double z) {
        return callMapped(box, ClassType.AABB, "move", x, y, z);
    }

    public Object inflateBox(Object box, double x, double y, double z) {
        return callMapped(box, ClassType.AABB, "inflate", x, y, z);
    }

    public boolean boxesIntersect(Object first, Object second) {
        Object result = callMapped(first, ClassType.AABB, "intersects", second);
        return result instanceof Boolean && (Boolean) result;
    }

    /**
     * 调用一个映射方法。
     *
     * <p>候选（名字 + 配套 JNI 描述符）由 {@link Mapping#methodCandidates} 按命名空间优先级给出：
     * 每个候选自带<b>与它同命名空间</b>的描述符（跨命名空间混用名字与描述符会导致参数类型解析失败），
     * 逐个尝试「按描述符解析形参 → 失败则按实参类型推断 → 反射调用」，第一个成功者胜出。
     *
     * <p>描述符解析失败或无描述符时按实参推导形参（恒等映射、以及描述符引用类在本地加载器里
     * 不存在的跨命名空间场景）；匹配不到或存在歧义时记录限流日志并跳过该候选（M-69）。
     *
     * @param target          接收调用的实例，可为 {@code null}（直接返回 {@code null}）
     * @param owner           声明该方法的类
     * @param canonicalMethod 未混淆的规范方法名
     * @param args            实参；反射调用会按目标方法的形参自动装箱
     * @return 方法返回值；全部候选均未命中或调用失败时为 {@code null}
     */
    public Object callMapped(Object target, ClassType owner, String canonicalMethod, Object... args) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        // 候选逐个试（原版混淆名 → intermediary → SRG → NeoForge → 规范名）：Forge 目标上方法名是
        // SRG（getMinecraft → func_71410_x），只试一个名字会让整条调用链静默返回 null。
        for (MethodCandidate candidate : mapping.methodCandidates(owner, canonicalMethod)) {
            String descriptor = candidate.descriptor();
            Class<?>[] parameters = descriptor == null
                    ? null
                    : descriptorParameters(type, candidate.name(), descriptor);
            if (parameters != null && parameters.length != (args == null ? 0 : args.length)) {
                // 表里的描述符只覆盖该规范方法在某一种签名。同一个规范名在不同版本之间换过 arity
                // （典型：Entity#getEyePosition 在 1.8.9–1.16.5 是 (F)，1.17+ 才是零参），按描述符
                // 去找必然找不到匹配的重载、调用也必然失败——必须退化为按实参推断，否则整条调用链
                // 静默返回 null（症状：世界覆盖层什么都不画，日志只有一句"读不到成员"）。
                parameters = null;
            }
            if (parameters == null) {
                // 没有描述符（恒等映射/表缺条目），或该命名空间的描述符在本加载器里解析不出来：
                // 退化为按实参类型推断，而不是把整条链就此中断。
                parameters = inferParameters(type, candidate.name(), args);
            }
            if (parameters == null) {
                continue;
            }
            Method method = findMethodCached(type, candidate.name(), parameters);
            if (method != null) {
                return Reflect.call(method, target, args);
            }
        }
        return null;
    }

    /** 调用映射类上的静态工厂方法；用于 Component.literal 等无公开构造器的类型。 */
    public Object callStaticMapped(ClassType owner, String canonicalMethod, Object... args) {
        Class<?> ownerType = type(owner);
        if (ownerType == null) {
            return null;
        }
        for (MethodCandidate candidate : mapping.methodCandidates(owner, canonicalMethod)) {
            Class<?>[] parameters = candidate.descriptor() == null ? null
                    : descriptorParameters(ownerType, candidate.name(), candidate.descriptor());
            if (parameters != null && parameters.length != (args == null ? 0 : args.length)) {
                parameters = null;
            }
            if (parameters == null) {
                parameters = inferParameters(ownerType, candidate.name(), args);
            }
            if (parameters == null) {
                continue;
            }
            Method method = findMethodCached(ownerType, candidate.name(), parameters);
            if (method != null && Modifier.isStatic(method.getModifiers())) {
                return Reflect.call(method, null, args);
            }
        }
        return null;
    }

    /** 调用 void 映射方法并返回真实成功状态；候选查找规则与 callMapped 完全一致。 */
    public boolean callMappedStatus(Object target, ClassType owner, String canonicalMethod,
                                    Object... args) {
        if (target == null) {
            return false;
        }
        Class<?> type = target.getClass();
        for (MethodCandidate candidate : mapping.methodCandidates(owner, canonicalMethod)) {
            String descriptor = candidate.descriptor();
            Class<?>[] parameters = descriptor == null ? null
                    : descriptorParameters(type, candidate.name(), descriptor);
            if (parameters != null && parameters.length != (args == null ? 0 : args.length)) {
                parameters = null;
            }
            if (parameters == null) {
                parameters = inferParameters(type, candidate.name(), args);
            }
            if (parameters == null) {
                continue;
            }
            Method method = findMethodCached(type, candidate.name(), parameters);
            if (method != null) {
                return Reflect.callStatus(method, target, args);
            }
        }
        return false;
    }

    /**
     * 把"聊天组件"读成纯文本。
     *
     * <p>为什么需要它：{@code Entity#getName} 在任何版本都不返回 {@code String}——1.8.9 是
     * {@code IChatComponent}、1.13+ 是 {@code Component}。调用方写 {@code instanceof String} 时，
     * 结论永远是"读不到名字"：标签一个字符都画不出来，而计数器照常增长（看起来像渲染坏了）。
     *
     * <p>读文本走映射表里的 {@code Component#getString()}，因此混淆版本也对得上（1.8.9 是
     * {@code d} / {@code method_10865} / {@code func_150254_d}）。调用方拿到 {@code null} 表示
     * 该版本读不出文本，应当跳过这一项，而不是画个空标签。
     *
     * @param component 组件实例；{@code null} 时返回 {@code null}
     * @return 纯文本；该实例不是组件、或映射不到取文本方法时为 {@code null}
     */
    public String componentText(Object component) {
        if (component == null) {
            return null;
        }
        if (component instanceof String) {
            // 未混淆构建、或将来某个版本直接给字符串
            return (String) component;
        }
        Object text = callMapped(component, ClassType.CHAT_COMPONENT, "getString");
        return text instanceof String ? (String) text : null;
    }

    /** 解析表中记录的描述符；结构非法时记录限流日志并返回 {@code null}。 */
    private Class<?>[] descriptorParameters(Class<?> type, String methodName, String descriptor) {
        Class<?>[] parameters = JniTypes.parameterTypes(descriptor, type.getClassLoader());
        if (parameters == null) {
            logThrottled("unparsable descriptor for " + methodName + ": " + descriptor, null);
        }
        return parameters;
    }

    /**
     * 在恒等映射（无描述符）下按实参类型推导形参列表：只接受参数个数匹配且每个参数与实参兼容的
     * 唯一重载；若仍有多个候选，再尝试精确类型匹配，仍不唯一则记为歧义并返回 {@code null}。
     */
    private Class<?>[] inferParameters(Class<?> type, String methodName, Object[] args) {
        int expected = args == null ? 0 : args.length;
        List<Method> candidates = new ArrayList<Method>();
        for (Method method : collectMethods(type)) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != expected) {
                continue;
            }
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!isCompatible(parameters[i], args[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                candidates.add(method);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0).getParameterTypes();
        }
        if (candidates.size() > 1) {
            List<Method> exact = new ArrayList<Method>();
            for (Method method : candidates) {
                if (exactMatch(method.getParameterTypes(), args)) {
                    exact.add(method);
                }
            }
            if (exact.size() == 1) {
                return exact.get(0).getParameterTypes();
            }
            logThrottled("ambiguous overload for " + methodName + " " + describeArgs(args), null);
            return null;
        }
        logThrottled("no overload for " + methodName + " " + describeArgs(args), null);
        return null;
    }

    /**
     * 在类及其父类链、并在各级实现的接口上查找方法。
     *
     * <p>接口上的方法（含默认方法）也必须可达：{@code getSuperclass()} 对接口返回 {@code null}，
     * 只沿父类上溯会漏掉接口声明（M-78）。
     *
     * @return 找到的可访问 {@link Method}；未找到返回 {@code null}
     */
    private Method findMethod(Class<?> type, String name, Class<?>[] parameters) {
        for (Method method : collectMethods(type)) {
            if (method.getName().equals(name) && sameParameters(method.getParameterTypes(), parameters)) {
                Method accessible = Reflect.accessible(method);
                if (accessible != null) {
                    return accessible;
                }
            }
        }
        return null;
    }

    /**
     * 字段查找的起点类：优先用 {@code owner} 解析出的类（它才是字段声明的宿主），
     * 解析不到或不是 {@code target} 的父类型时才退回实例类。
     */
    private Class<?> fieldOwnerType(ClassType owner, Object target) {
        Class<?> declared = owner == null ? null : type(owner);
        return declared != null && declared.isInstance(target) ? declared : target.getClass();
    }

    /** 命中缓存则直接返回；未命中时查找，仅缓存成功结果以便失败可重试。 */
    private Method findMethodCached(Class<?> type, String name, Class<?>[] parameters) {
        MethodKey key = new MethodKey(type, name, signature(parameters));
        Method cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        Method found = findMethod(type, name, parameters);
        if (found != null) {
            methodCache.putIfAbsent(key, found);
        }
        return found;
    }

    /**
     * 写入一个映射字段。
     *
     * @return 写入真正生效返回 true；字段缺失、不可访问或类型不匹配返回 false
     */
    public boolean writeField(Object target, ClassType owner, String canonicalField, Object value) {
        if (target == null) {
            return false;
        }
        // 候选名逐个试：原版混淆名 → SRG 名 → 规范名（见 Mapping#fieldNameCandidates）。
        // 只试一个名字时，Forge 目标上写字段会静默失败（成员名是 SRG），
        // 表现成毫不相关的症状——例如"拖 GUI 时视角跟着转"。
        Class<?> start = fieldOwnerType(owner, target);
        for (String candidate : mapping.fieldNameCandidates(owner, canonicalField)) {
            Field field = findFieldCached(start, candidate);
            if (field == null) {
                continue;
            }
            try {
                field.set(target, value);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /**
     * 读取一个映射字段，沿类继承链向上查找声明处（候选名顺序同 {@link #writeField}）。
     *
     * <p><b>起点必须是 {@code owner} 解析出的类，而不是 {@code target.getClass()}</b>：
     * 子类里同名的静态字段会遮蔽父类的实例字段。真机踩过：{@code BlockPos}（混淆 {@code fx}）
     * 有一个静态字段 {@code a}（类型是 BlockPos），而 {@code Vec3i}（{@code gr}）的实例字段
     * {@code a} 才是 x 坐标；从实例类开始查就会拿到那个静态字段，读出来不是数字，
     * StorageESP 于是整片容器都读不到坐标（`scanned=200 matched=196 visible=0`）。
     */
    public Object readField(Object target, ClassType owner, String canonicalField) {
        if (target == null) {
            return null;
        }
        Class<?> start = fieldOwnerType(owner, target);
        for (String candidate : mapping.fieldNameCandidates(owner, canonicalField)) {
            Field field = findFieldCached(start, candidate);
            if (field == null) {
                continue;
            }
            try {
                return field.get(target);
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 命中缓存则直接返回；未命中时查找，仅缓存成功结果以便失败可重试。 */
    private Field findFieldCached(Class<?> type, String name) {
        FieldKey key = new FieldKey(type, name);
        Field cached = fieldCache.get(key);
        if (cached != null) {
            return cached;
        }
        Field found = findField(type, name);
        if (found == null) {
            return null;
        }
        Field accessible = Reflect.accessible(found);
        if (accessible == null) {
            return null;
        }
        fieldCache.putIfAbsent(key, accessible);
        return accessible;
    }

    /**
     * 诊断：解析不到 Minecraft 时，把"我们找的名字"与"实际存在的近似名字"都列出来。
     *
     * <p>写它的原因很具体：Forge 运行期会把官方混淆名重映射（类名走 deobf、成员走 srg），
     * 而我们的映射表记的是**原版混淆名**。在那种环境里 {@link #minecraft()} 会一直返回
     * {@code null}，日志里却看不出"到底该叫什么"。这里把候选类的存在性，以及该类上
     * 「返回自身类型、无参」的 static 方法名（那正是 {@code getMinecraft} 的形状）打印出来，
     * 一眼就能定位真实命名。
     *
     * @return 一行可读诊断文本（不含换行）
     */
    public String describeResolution() {
        StringBuilder out = new StringBuilder(256);
        String[] candidates = minecraftClassCandidates();
        out.append("candidates=").append(java.util.Arrays.toString(candidates));
        for (String candidate : candidates) {
            Class<?> type = findLoadedClass(candidate);
            if (type == null) {
                out.append("; ").append(candidate).append("=missing");
                continue;
            }
            out.append("; ").append(candidate).append("=loaded[");
            java.util.List<String> selfFactories = new java.util.ArrayList<String>();
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == type) {
                    selfFactories.add(method.getName());
                }
            }
            out.append("selfFactories=").append(selfFactories).append(']');
        }
        return out.toString();
    }

    /** 清除已解析状态，使下一次 {@link #resolve()} 重新查找。 */
    private void invalidate() {
        minecraftClass = null;
        getInstanceMethod = null;
        setLastResolveAttemptNow();   // 失败时保留退避，不清零否则每帧一次全表扫描（definite / critical）
        // 句柄缓存以 Class 对象（identityHashCode）为键且永不失效；类被重载/attach 二次引导
        // 时，旧 Class 强引用其 ClassLoader → 泄漏落地。javadoc 承诺的「适应重载」在这里落实。
        fieldCache.clear();
        methodCache.clear();
        methodListCache.clear();
    }

    /** 记录一次「现在已尝试过解析」，供 {@link #resolve()} 的 250ms 退避判定使用。 */
    private void setLastResolveAttemptNow() {
        lastResolveAttemptNanos = System.nanoTime();
    }

    /** 依次尝试候选名称，返回第一个已加载的类；全部未加载返回 {@code null}。 */
    private Class<?> findLoadedClass(String[] names) {
        for (String name : names) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
        }
        return null;
    }

    /**
     * 按名称查找已加载的类。
     *
     * <p>优先扫描 {@code instrumentation} 的已加载类表——它能看见游戏的自定义类加载器加载的类；
     * 没有仪表时退回线程上下文类加载器。
     */
    private Class<?> findLoadedClass(String name) {
        if (instrumentation != null) {
            for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
                if (candidate.getName().equals(name)) {
                    return candidate;
                }
            }
            return null;
        }
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在类/父类/接口上查找静态、无参、返回类型可赋给 {@code expectedReturn} 的访问器。 */
    private Method findStaticAccessor(Class<?> type, String name, Class<?> expectedReturn) {
        Method fallback = null;
        for (Method method : collectMethods(type)) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != 0) {
                continue;
            }
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) {
                continue;
            }
            if (!expectedReturn.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Method accessible = Reflect.accessible(method);
            if (accessible == null) {
                continue;
            }
            if (method.getReturnType() == expectedReturn) {
                return accessible;
            }
            if (fallback == null) {
                fallback = accessible;
            }
        }
        return fallback;
    }

    /** 沿类继承链向上查找声明的字段；未找到返回 {@code null}。 */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类上溯
            }
        }
        return null;
    }

    /**
     * 收集类、其父类链以及各级所实现接口上声明的全部方法，按“本类 → 父类 → 接口”的顺序去重。
     *
     * <p>接口必须单独遍历：只走 {@code getSuperclass()} 会漏掉接口上的默认方法（M-78）。
     */
    private List<Method> collectMethods(Class<?> type) {
        List<Method> cached = methodListCache.get(type);
        if (cached != null) {
            return cached;
        }
        List<Method> out = new ArrayList<Method>();
        collectMethodsInto(type, out, new HashSet<String>());
        List<Method> existing = methodListCache.putIfAbsent(type, out);
        return existing == null ? out : existing;
    }

    /** 递归收集的辅助实现；{@code seen} 以“名字+形参签名”去重，避免重复扫描同一签名。 */
    private static void collectMethodsInto(Class<?> type, List<Method> out, Set<String> seen) {
        if (type == null) {
            return;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (seen.add(method.getName() + signature(method.getParameterTypes()))) {
                out.add(method);
            }
        }
        collectMethodsInto(type.getSuperclass(), out, seen);
        for (Class<?> iface : type.getInterfaces()) {
            collectMethodsInto(iface, out, seen);
        }
    }

    /** 形参列表是否逐项相等（用于按描述符挑选重载）。 */
    private static boolean sameParameters(Class<?>[] declared, Class<?>[] expected) {
        if (declared.length != expected.length) {
            return false;
        }
        for (int i = 0; i < declared.length; i++) {
            if (declared[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** 实参能否赋给形参：引用类型走 {@code isInstance}，原始类型只接受对应包装类。 */
    private static boolean isCompatible(Class<?> parameter, Object argument) {
        if (argument == null) {
            return !parameter.isPrimitive();
        }
        if (parameter.isInstance(argument)) {
            return true;
        }
        return parameter.isPrimitive() && boxed(parameter).isInstance(argument);
    }

    /** 实参类型与形参类型是否逐一完全一致（用于在多个候选中挑精确匹配）。 */
    private static boolean exactMatch(Class<?>[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            Object argument = args[i];
            if (argument == null) {
                if (parameter.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (parameter.isPrimitive()) {
                parameter = boxed(parameter);
            }
            if (parameter != argument.getClass()) {
                return false;
            }
        }
        return true;
    }

    /** 原始类型到包装类型的映射；非原始类型原样返回。 */
    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }

    /** 形参签名（用于缓存键与去重），如 {@code Ljava/lang/String;II}。 */
    private static String signature(Class<?>[] parameters) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> parameter : parameters) {
            sb.append(parameter.getName()).append(',');
        }
        return sb.toString();
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    /** 实参的人类可读描述，用于歧义/未命中的限流日志。 */
    private static String describeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getName());
        }
        return sb.append(')').toString();
    }

    /** 限流日志：前若干次与之后每 {@code 600} 次打印一条，避免热路径刷屏。 */
    private void logThrottled(String message, Throwable cause) {
        long count = logCount.incrementAndGet();
        if (count <= 5 || count % 600 == 0) {
            System.out.println("[nocturne] GameBridge: " + message + (cause == null ? "" : " (" + cause + ")"));
        }
    }

    /** 构造器缓存键：真实类 + 实参形状。 */
    private static final class ConstructorKey {
        private final Class<?> owner;
        private final String arguments;

        ConstructorKey(Class<?> owner, String arguments) {
            this.owner = owner;
            this.arguments = arguments;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ConstructorKey)) {
                return false;
            }
            ConstructorKey key = (ConstructorKey) other;
            return owner == key.owner && arguments.equals(key.arguments);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(owner) * 31 + arguments.hashCode();
        }
    }

    /** 字段句柄缓存键：宿主类 + 字段名。 */
    private static final class FieldKey {
        private final Class<?> owner;
        private final String name;

        FieldKey(Class<?> owner, String name) {
            this.owner = owner;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldKey)) {
                return false;
            }
            FieldKey key = (FieldKey) other;
            return owner == key.owner && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(owner) * 31 + name.hashCode();
        }
    }

    /** 方法句柄缓存键：宿主类 + 方法名 + 形参签名。 */
    private static final class MethodKey {
        private final Class<?> owner;
        private final String name;
        private final String signature;

        MethodKey(Class<?> owner, String name, String signature) {
            this.owner = owner;
            this.name = name;
            this.signature = signature;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MethodKey)) {
                return false;
            }
            MethodKey key = (MethodKey) other;
            return owner == key.owner && name.equals(key.name) && signature.equals(key.signature);
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(owner) * 31 + name.hashCode()) * 31 + signature.hashCode();
        }
    }
}
