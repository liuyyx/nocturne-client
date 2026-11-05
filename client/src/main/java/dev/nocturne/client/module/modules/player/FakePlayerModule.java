package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayList;
import java.util.List;

/** FakePlayer：生成本地假人，支持背包复制、轨迹记录与循环回放。 */
public final class FakePlayerModule extends GameModule {
    private final TextValue name = add(new TextValue("Name", "BC_zxy", 64));
    private final BooleanValue copyInventory = add(new BooleanValue("Copy Inventory", true));
    private final BooleanValue record = add(new BooleanValue("Record", false));
    private final BooleanValue playback = add(new BooleanValue("Playback", false));
    private Object fake;
    private final List<double[]> positions = new ArrayList<double[]>();
    private int playbackIndex;
    private int deadTicks;

    public FakePlayerModule() {
        super("Fake Player", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        fake = bridge() != null ? bridge().spawnFakePlayer(name.get(), copyInventory.get()) : null;
        positions.clear();
        playbackIndex = 0;
        deadTicks = 0;
    }

    @Override
    public void onTick() {
        if (fake == null) {
            return;
        }
        if (record.get()) {
            positions.add(new double[]{state().x(), state().y(), state().z(), state().yaw(), state().pitch()});
        }
        if (playback.get() && !positions.isEmpty()) {
            double[] frame = positions.get(playbackIndex++ % positions.size());
            bridge().setPosition(fake, frame[0], frame[1], frame[2]);
            bridge().writeField(fake, ClassType.ENTITY, "yRot", (float) frame[3]);
            bridge().writeField(fake, ClassType.ENTITY, "xRot", (float) frame[4]);
        }
        if (bridge().health(fake) <= 0.0f && ++deadTicks >= 10) {
            setEnabled(false);
        }
    }

    @Override
    protected void onStop() {
        if (bridge() != null) {
            bridge().removeFakePlayer(fake);
        }
        fake = null;
        positions.clear();
    }
}
