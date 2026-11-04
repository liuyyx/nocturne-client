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
        return world == null || position == null ? null
                : callMapped(world, ClassType.CLIENT_LEVEL, "getBlockState", position);
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

    /** 读取实体主/副手物品。 */
    public Object heldItem(Object entity, boolean offHand) {
        return callMapped(entity, ClassType.LIVING_ENTITY,
                offHand ? "getOffhandItem" : "getMainHandItem");
    }

    /** 掉落物携带的物品栈。 */
    public Object droppedItem(Object itemEntity) {
        return callMapped(itemEntity, ClassType.ITEM_ENTITY, "getItem");
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
        Object noEntities = callMapped(world, ClassType.LEVEL, "checkNoEntityCollision", box);
        Object blockCollision = callMapped(world, ClassType.LEVEL, "checkBlockCollision", box);
        return noEntities instanceof Boolean && blockCollision instanceof Boolean
                && (Boolean) noEntities && !(Boolean) blockCollision;
    }

    /** 方块是否有碰撞形状；形状面不可用时按非空气保守处理。 */
    public boolean hasBlockCollision(Object blockState, Object blockPos) {
        Object shape = callMapped(blockState, ClassType.BLOCK_STATE, "getCollisionShape",
                world(), blockPos);
        Object empty = callMapped(shape, ClassType.VOXEL_SHAPE, "isEmpty");
        return empty instanceof Boolean ? !(Boolean) empty : !isAir(blockState);
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
