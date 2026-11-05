package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 按配置缩放或延迟本地玩家的实体速度包。 */
public final class VelocityModule extends GameModule {
    private final BooleanValue waterCheck = add(new BooleanValue("Water check", false));
    private final NumberValue chance = add(new NumberValue("Chance", 40, 0, 100, 1));
    private final NumberValue horizontal = add(new NumberValue("Horizontal", 90, 0, 100, 1));
    private final NumberValue vertical = add(new NumberValue("Vertical", 100, 0, 100, 1));
    private final NumberValue ticks = add(new NumberValue("Ticks", 1, 0, 10, 1));
    private final BooleanValue onlyTargeting = add(new BooleanValue("Only when targeting", false));
    private final Random random = new Random();
    private final TargetManager.Options targetOptions = new TargetManager.Options();
    private double[] pending;
    private int pendingTicks;

    public VelocityModule() {
        super("Velocity", Category.COMBAT);
        targetOptions.range = 6.0;
        targetOptions.maxAngle = 180.0;
        targetOptions.mobs = true;
        targetOptions.invisibles = true;
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, EventBus.Priority.LOWEST, this::onPacket);
    }

    @Override
    protected void onStop() {
        pending = null;
        pendingTicks = 0;
    }

    private void onPacket(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE) {
            return;
        }
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present() || !bridge.isLocalVelocity(event.packet)
                || random.nextDouble() * 100.0 >= chance.get()
                || waterCheck.get() && bridge.inWater(state.player())
                || onlyTargeting.get() && client().targets().find(client(), targetOptions) == null) {
            return;
        }
        int[] data = bridge.velocityData(event.packet);
        if (data == null) {
            return;
        }
        int x = (int) Math.round(data[1] * horizontal.get() / 100.0);
        int y = (int) Math.round(data[2] * vertical.get() / 100.0);
        int z = (int) Math.round(data[3] * horizontal.get() / 100.0);
        if (ticks.asInt() <= 0) {
            Object replacement = bridge.construct(ClassType.CLIENTBOUND_ENTITY_VELOCITY, data[0], x, y, z);
            if (replacement != null) {
                event.setPacket(replacement);
                return;
            }
        }
        event.cancelled = true;
        pending = new double[]{x / 8000.0, y / 8000.0, z / 8000.0};
        pendingTicks = Math.max(1, ticks.asInt());
    }

    @Override
    public void onTick() {
        if (pending == null || --pendingTicks > 0) {
            return;
        }
        ActionApi actions = actions();
        if (actions != null) {
            actions.setMotion(pending[0], pending[1], pending[2]);
        }
        pending = null;
    }
}
