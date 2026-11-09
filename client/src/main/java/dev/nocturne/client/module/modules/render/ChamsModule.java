package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.control.Friends;
import dev.nocturne.client.event.EntityRenderEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.RenderCapabilities;
import dev.nocturne.client.value.BooleanValue;

/** Epsilon Chams：按 Players/Self/Friends/Crystals/Chests 与生物类别启用透视渲染状态。 */
public final class ChamsModule extends GameModule {
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue self = add(new BooleanValue("Self", true));
    private final BooleanValue friends = add(new BooleanValue("Friends", true));
    private final BooleanValue crystals = add(new BooleanValue("Crystals", true));
    private final BooleanValue chests = add(new BooleanValue("Chests", true));
    private final BooleanValue creatures = add(new BooleanValue("Creatures", false));
    private final BooleanValue monsters = add(new BooleanValue("Monsters", false));
    private final BooleanValue ambients = add(new BooleanValue("Ambients", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));

    /**
     * 每个线程的深度状态快照栈：实体渲染可能嵌套（画 A 的过程中又画 B）。
     * 单槽 ThreadLocal 会让内层覆盖外层，post 时把外层的“原值”写成内层的值。
     */
    private final ThreadLocal<java.util.ArrayDeque<Boolean>> depthSnapshots =
            new ThreadLocal<java.util.ArrayDeque<Boolean>>() {
                @Override
                protected java.util.ArrayDeque<Boolean> initialValue() {
                    return new java.util.ArrayDeque<Boolean>();
                }
            };

    public ChamsModule() {
        super("Chams", Category.RENDER);
    }

    @Override
    protected void onStart() {
        // 能力检查：1.21.5–1.21.8 的实体绘制首参是 EntityRenderState（不持有实体），
        // 1.21.9+ 连 EntityRenderDispatcher#render 都没有。两种情况都没有真实绘制入口，
        // 模块必须显式降级——注册一个"看起来生效"的假实现比不提供更糟。
        if (!dev.nocturne.client.bootstrap.GameplayHook.isWired("chams")) {
            String reason = dev.nocturne.client.bootstrap.GameplayHook.unavailableReason("chams");
            System.out.println("[nocturne] Chams: unavailable on this version"
                    + (reason == null ? " (entity render hook not wired)" : " (" + reason + ")")
                    + "; no depth-test override is applied");
        }
        listen(EntityRenderEvent.class, this::onRender);
    }

    private void onRender(EntityRenderEvent event) {
        RenderCapabilities capabilities = client().renderCapabilities();
        if (capabilities == null) {
            return;
        }
        if (event.post) {
            // post 必须与 pre 严格配对：实体在绘制期间死亡/变为无效时仍要还原深度，
            // 否则 Chams 关掉的深度测试会永久留在关闭状态。
            restoreOne(capabilities);
            return;
        }
        if (event.entity == null || !valid(event.entity)) {
            // null 不压栈：钩子层已经为 null 压了哨兵，模块层再压就会和 post 失配。
            return;
        }
        // 先快照原深度：不同版本的进入态不一定是 true（前一模块/游戏自己可能已经关了）。
        // 拿不到原值时宁可不改——猜一个值会把世界画成穿透或被 HUD 遮挡。
        Boolean previous = capabilities.depthTestEnabled();
        if (previous == null) {
            return;
        }
        java.util.ArrayDeque<Boolean> stack = depthSnapshots.get();
        stack.push(previous);
        try {
            capabilities.depthTest(false);
        } catch (RuntimeException failure) {
            stack.pop();
            throw failure;
        } catch (Error failure) {
            stack.pop();
            throw failure;
        }
    }

    private boolean valid(Object entity) {
        if (bridge().isInstance(entity, ClassType.END_CRYSTAL)) {
            return crystals.get();
        }
        if (bridge().isInstance(entity, ClassType.PLAYER)) {
            if (entity == bridge().player()) {
                return self.get();
            }
            if (Friends.isFriend(bridge(), entity)) {
                return friends.get();
            }
            return players.get();
        }
        if (bridge().isInstance(entity, ClassType.AMBIENT)) {
            return ambients.get();
        }
        if (bridge().isInstance(entity, ClassType.ANIMAL)) {
            return creatures.get();
        }
        if (bridge().isInstance(entity, ClassType.MOB)) {
            return monsters.get();
        }
        if (bridge().isInstance(entity, ClassType.CHEST_BLOCK_ENTITY)) {
            return chests.get();
        }
        return others.get();
    }

    @Override
    protected void onStop() {
        NocturneClient client = client();
        RenderCapabilities capabilities = client == null ? null : client.renderCapabilities();
        if (capabilities != null) {
            restoreAll(capabilities);
        }
    }

    /** 内层返回：还原这一层进入前的深度。找不到快照（没有对应 pre）时不动任何状态。 */
    private void restoreOne(RenderCapabilities capabilities) {
        java.util.ArrayDeque<Boolean> stack = depthSnapshots.get();
        Boolean previous = stack.poll();
        if (previous != null) {
            capabilities.depthTest(previous.booleanValue());
        }
        if (stack.isEmpty()) {
            depthSnapshots.remove();
        }
    }

    /**
     * 模块停用：把没配对的 pre（渲染中途异常/提前返回）恢复成最外层进入前的值。
     *
     * <p>不再硬编码 {@code true}、也不再写回白色：Chams 只改过深度测试，颜色/纹理从未被修改，
     * 写“默认值”会覆盖游戏当帧的真实状态。
     */
    private void restoreAll(RenderCapabilities capabilities) {
        java.util.ArrayDeque<Boolean> stack = depthSnapshots.get();
        Boolean original = stack.peekLast();
        stack.clear();
        depthSnapshots.remove();
        if (original != null) {
            capabilities.depthTest(original.booleanValue());
        }
    }
}
