package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;

/** 集中的 NPC/队伍目标过滤器，供所有使用 TargetManager 的模块共享。 */
public final class TargetFilterModule extends GameModule {
    private final BooleanValue antiBot = add(new BooleanValue("AntiBot", false));
    private final BooleanValue teamsByServer = add(new BooleanValue("Teams by server", false));

    public TargetFilterModule() {
        super("TargetFilter", Category.MISC);
    }

    public static boolean validTarget(GameBridge bridge, Object local, Object entity) {
        NocturneClient client = NocturneClient.get();
        Module module = client == null ? null : client.modules().get("TargetFilter");
        if (!(module instanceof TargetFilterModule) || !module.isEnabled()) {
            return true;
        }
        TargetFilterModule filter = (TargetFilterModule) module;
        if (filter.teamsByServer.get() && bridge.isAllied(local, entity)) {
            return false;
        }
        if (filter.antiBot.get()) {
            String name = bridge.entityName(entity);
            if (bridge.entityId(entity) < 0 || name.contains("[NPC]") || name.contains("CIT-")) {
                return false;
            }
        }
        return true;
    }
}
