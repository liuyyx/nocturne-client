package dev.nocturne.client.action;

import dev.nocturne.client.action.ActionContract.ActionSpec;
import dev.nocturne.client.action.ActionContract.CandidateSpec;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.JniTypes;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.mapping.MethodCandidate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 按 {@link ActionContract} 把语义动作绑定到当前版本的真实 {@link Method}。
 *
 * <p>只缓存成功解析的类与方法；启动时全部候选均未命中的动作立即降级，后续调用不再反复
 * 扫描或刷屏。方法调用失败同样只禁用一次，避免在热路径持续抛异常。
 */
public final class ActionBinder {

    private final ActionContract contract;
    private final Mapping mapping;
    private final ClassLoader loader;
    private final Consumer<String> logger;
    private final Map<ClassType, Class<?>> classes = new HashMap<ClassType, Class<?>>();
    private final Map<String, MethodBinding> bindings = new HashMap<String, MethodBinding>();
    private final Map<String, Boolean> available = new HashMap<String, Boolean>();
    private final Set<String> logged = new HashSet<String>();
    private String lastBindFailure;
    private Constructor<?> blockHitResultConstructor;
    private boolean blockHitResultHasInside;
    private Constructor<?> vec3Constructor;
    private Constructor<?> blockPosConstructor;
    private Object mainHand;

    /** 用当前反射桥的映射与游戏类加载器装配动作。 */
    public static ActionBinder create(GameBridge bridge) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge == null");
        }
        ActionBinder binder = new ActionBinder(ActionContract.load(), bridge.mapping(),
                bridge.gameLoader(), new Consumer<String>() {
                    @Override
                    public void accept(String message) {
                        System.out.println("[nocturne] " + message);
                    }
                });
        binder.bind();
        return binder;
    }

    ActionBinder(ActionContract contract, Mapping mapping, ClassLoader loader,
                 Consumer<String> logger) {
        this.contract = contract;
        this.mapping = mapping;
        this.loader = loader == null ? ActionBinder.class.getClassLoader() : loader;
        this.logger = logger;
    }

    /** 创建面向模块的语义 API。 */
    public ActionApi api(GameBridge bridge) {
        return new BoundActionApi(this, bridge);
    }

    /** 动作是否可用；未知 id 一律 false。 */
    public boolean isAvailable(String actionId) {
        return Boolean.TRUE.equals(available.get(actionId));
    }

    /** 调用已绑定方法；返回 true 只表示真实调用成功，不解释游戏返回值。 */
    boolean invoke(String actionId, Object receiver, Object... args) {
        if (!isAvailable(actionId) || receiver == null) {
            return false;
        }
        MethodBinding binding = bindings.get(actionId);
        return binding != null && binding.invoke(receiver, args);
    }

    /** 已绑定候选的语义实参顺序；旧契约未声明时为空。 */
    java.util.List<String> arguments(String actionId) {
        MethodBinding binding = bindings.get(actionId);
        return binding == null ? java.util.Collections.<String>emptyList() : binding.arguments;
    }

    /** 构造一个方块坐标；类或构造器缺失时返回 {@code null}。 */
    Object newBlockPos(int x, int y, int z) {
        if (blockPosConstructor == null) {
            return null;
        }
        try {
            return blockPosConstructor.newInstance(x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按六个面名取游戏的静态方向常量；未知面返回 {@code null}。 */
    Object direction(String face) {
        if (face == null) {
            return null;
        }
        String canonical = face.trim().toUpperCase(java.util.Locale.ROOT);
        if (!canonical.equals("UP") && !canonical.equals("DOWN") && !canonical.equals("NORTH")
                && !canonical.equals("SOUTH") && !canonical.equals("EAST")
                && !canonical.equals("WEST")) {
            return null;
        }
        try {
            return resolveStaticField(ClassType.DIRECTION, canonical);
        } catch (Throwable t) {
            return null;
        }
    }

    Object newVec3(double x, double y, double z) {
        if (vec3Constructor == null) {
            return null;
        }
        try {
            return vec3Constructor.newInstance(x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按版本形状构造方块命中结果：1.14+ 四参，1.8.9/1.12.2 三参。 */
    Object newBlockHitResult(Object vec, Object direction, Object blockPos) {
        if (blockHitResultConstructor == null) {
            return null;
        }
        try {
            return blockHitResultHasInside
                    ? blockHitResultConstructor.newInstance(vec, direction, blockPos, false)
                    : blockHitResultConstructor.newInstance(vec, direction, blockPos);
        } catch (Throwable t) {
            return null;
        }
    }

    Object mainHand() {
        return mainHand;
    }

    void bind() {
        for (ActionSpec action : contract.actions()) {
            try {
                bind(action);
            } catch (Throwable t) {
                disable(action.id(), "binding failed: " + t);
            }
        }
    }

    private void bind(ActionSpec action) throws Exception {
        String operation = action.operation();
        if ("snapshot".equals(operation)) {
            available.put(action.id(), true);
            return;
        }
        if ("rotation".equals(operation)) {
            available.put(action.id(), true);
            return;
        }
        if ("place".equals(operation)) {
            Class<?> vec3 = resolveClass(ClassType.VEC3);
            Class<?> direction = resolveClass(ClassType.DIRECTION);
            Class<?> blockPos = resolveClass(ClassType.BLOCK_POS);
            Class<?> hitResult = resolveClass(ClassType.BLOCK_HIT_RESULT);
            if (vec3 == null || direction == null || blockPos == null || hitResult == null) {
                disable(action.id(), "BlockHitResult support is unavailable");
                return;
            }
            vec3Constructor = findConstructor(vec3, double.class, double.class, double.class);
            blockPosConstructor = findConstructor(blockPos, int.class, int.class, int.class);
            blockHitResultConstructor = findConstructor(hitResult, vec3, direction, blockPos,
                    boolean.class);
            blockHitResultHasInside = blockHitResultConstructor != null;
            if (blockHitResultConstructor == null) {
                blockHitResultConstructor = findConstructor(hitResult, vec3, direction, blockPos);
            }
            if (vec3Constructor == null || blockPosConstructor == null
                    || blockHitResultConstructor == null) {
                disable(action.id(), "placement constructors are unavailable");
                return;
            }
        }
        if ("place".equals(operation) || "useItem".equals(operation)) {
            // 旧版没有 InteractionHand：现代候选才需要 MAIN_HAND，缺它只淘汰带 hand 的候选。
            try {
                mainHand = resolveStaticField(ClassType.INTERACTION_HAND, "MAIN_HAND");
            } catch (Throwable ignored) {
                mainHand = null;
            }
        }
        lastBindFailure = null;
        for (CandidateSpec candidate : action.candidates()) {
            MethodBinding binding = bindCandidate(action.id(), candidate);
            if (binding != null) {
                bindings.put(action.id(), binding);
                available.put(action.id(), true);
                return;
            }
        }
        disable(action.id(), "no candidate matched"
                + (lastBindFailure == null ? "" : ": " + lastBindFailure));
    }

    private MethodBinding bindCandidate(String actionId, CandidateSpec candidate) {
        final ClassType ownerType;
        try {
            ownerType = ClassType.valueOf(candidate.owner());
        } catch (IllegalArgumentException e) {
            lastBindFailure = "unknown owner " + candidate.owner();
            return null;
        }
        Class<?> owner = resolveClass(ownerType);
        if (owner == null) {
            lastBindFailure = "owner class not found " + candidate.owner();
            return null;
        }
        for (MethodCandidate mapped : mapping.methodCandidates(ownerType, candidate.method())) {
            Class<?>[] parameters = null;
            String descriptor = mapped.descriptor();
            if (descriptor != null && !descriptor.isEmpty()) {
                parameters = JniTypes.parameterTypes(descriptor, loader);
            } else {
                parameters = resolveParameters(candidate);
            }
            if (parameters == null || parameters.length != candidate.parameters().size()) {
                lastBindFailure = "parameters not resolved for " + mapped.name();
                continue;
            }
            Method method = findMethod(owner, mapped.name(), parameters);
            if (method != null) {
                return new MethodBinding(actionId, method, candidate.arguments());
            }
            lastBindFailure = "method not found " + mapped.name();
        }
        return null;
    }

    private Class<?>[] resolveParameters(CandidateSpec candidate) {
        Class<?>[] parameters = new Class<?>[candidate.parameters().size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = loadCanonical(candidate.parameters().get(i));
            if (parameters[i] == null) {
                return null;
            }
        }
        return parameters;
    }

    private Class<?> loadCanonical(String name) {
        if ("boolean".equals(name)) {
            return boolean.class;
        }
        if ("byte".equals(name)) {
            return byte.class;
        }
        if ("short".equals(name)) {
            return short.class;
        }
        if ("int".equals(name)) {
            return int.class;
        }
        if ("long".equals(name)) {
            return long.class;
        }
        if ("float".equals(name)) {
            return float.class;
        }
        if ("double".equals(name)) {
            return double.class;
        }
        if ("char".equals(name)) {
            return char.class;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private Class<?> resolveClass(ClassType type) {
        Class<?> cached = classes.get(type);
        if (cached != null) {
            return cached;
        }
        for (String name : mapping.classNameCandidates(type)) {
            try {
                Class<?> resolved = Class.forName(name.replace('/', '.'), false, loader);
                classes.put(type, resolved);
                return resolved;
            } catch (Throwable ignored) {
                // 按映射候选顺序继续；全部失败由调用方降级。
            }
        }
        return null;
    }

    private Object resolveStaticField(ClassType type, String canonicalField) throws Exception {
        Class<?> owner = resolveClass(type);
        if (owner == null) {
            return null;
        }
        for (String name : mapping.fieldNameCandidates(type, canonicalField)) {
            try {
                return owner.getField(name).get(null);
            } catch (NoSuchFieldException ignored) {
                // 按字段候选顺序继续。
            }
        }
        return null;
    }

    private static Constructor<?> findConstructor(Class<?> owner, Class<?>... parameters) {
        try {
            Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>[] parameters) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 沿继承链继续。
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private void disable(String actionId, String reason) {
        available.put(actionId, false);
        bindings.remove(actionId);
        if (logged.add(actionId)) {
            logger.accept("action '" + actionId + "' disabled (" + reason + "); degrade applied");
        }
    }

    private final class MethodBinding {
        private final String actionId;
        private final Method method;
        private final java.util.List<String> arguments;

        private MethodBinding(String actionId, Method method, java.util.List<String> arguments) {
            this.actionId = actionId;
            this.method = method;
            this.arguments = arguments;
        }

        private boolean invoke(Object receiver, Object... args) {
            try {
                method.invoke(receiver, args);
                return true;
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                        && t.getCause() != null ? t.getCause() : t;
                disable(actionId, "invocation failed: " + cause);
                return false;
            }
        }
    }
}
