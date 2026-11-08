package dev.nocturne.client.module.modules.render;

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
        if (!valid(event.entity)) {
            return;
        }
        RenderCapabilities capabilities = client().renderCapabilities();
        if (capabilities == null) {
            return;
        }
        if (!event.post) {
            capabilities.depthTest(false);
        } else {
            restore(capabilities);
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
        RenderCapabilities capabilities = client().renderCapabilities();
        if (capabilities != null) {
            restore(capabilities);
        }
    }

    private void restore(RenderCapabilities capabilities) {
        capabilities.depthTest(true);
        capabilities.texture2d(true);
        capabilities.polygonOffset(false);
        capabilities.color(0xFFFFFFFF);
    }
}
