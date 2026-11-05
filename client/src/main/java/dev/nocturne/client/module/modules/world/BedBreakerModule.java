package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;

/** 扫描近距离床方块，瞄准后调用真实挖掘点击。 */
public final class BedBreakerModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Normal", "Normal", "Hypixel"));
    private long nextScan;

    public BedBreakerModule() {
        super("BedBreaker", Category.WORLD);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextScan) {
            return;
        }
        nextScan = now + 250L;
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        PlayerState state = state();
        if (bridge == null || actions == null || !state.present()) {
            return;
        }
        int px = (int) Math.floor(state.x());
        int py = (int) Math.floor(state.y());
        int pz = (int) Math.floor(state.z());
        for (int y = py - 4; y <= py + 4; y++) {
            for (int x = px - 8; x <= px + 8; x++) {
                for (int z = pz - 8; z <= pz + 8; z++) {
                    String id = bridge.blockDescription(bridge.blockStateAt(x, y, z));
                    if (!(id.endsWith("_bed") || id.equals("tile.bed") || id.endsWith(".bed"))) {
                        continue;
                    }
                    double dx = x + 0.5 - state.x();
                    double dz = z + 0.5 - state.z();
                    float yaw = RotationController.yawTo(dx, dz);
                    float pitch = RotationController.pitchTo(Math.sqrt(dx * dx + dz * dz),
                            y + 0.5 - state.y() - 1.62);
                    actions.setRotation(yaw, pitch);
                    actions.click();
                    return;
                }
            }
        }
    }
}
