package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Jump Circle：落地生成 Default/Portal 扩散环，支持缓出、旋转与仅自己。 */
public final class JumpCircleModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "Default", "Default", "Portal"));
    private final BooleanValue easeOut = add(new BooleanValue("Ease Out", true));
    private final NumberValue rotateSpeed = add(new NumberValue("Rotate Speed", 2, 0.5, 5, 0.1));
    private final NumberValue circleScale = add(new NumberValue("Circle Scale", 1, 0.5, 5, 0.1));
    private final BooleanValue onlySelf = add(new BooleanValue("Only Self", false));
    private final Map<Integer, Boolean> grounded = new HashMap<Integer, Boolean>();
    private final List<Circle> circles = new ArrayList<Circle>();

    public JumpCircleModule() {
        super("Jump Circle", Category.RENDER);
    }

    @Override
    public void onTick() {
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, dev.nocturne.client.mapping.ClassType.LIVING_ENTITY)
                    || onlySelf.get() && entity != bridge().player()) {
                return;
            }
            int id = bridge().entityId(entity);
            Object value = bridge().readField(entity, dev.nocturne.client.mapping.ClassType.ENTITY, "onGround");
            boolean now = Boolean.TRUE.equals(value);
            boolean before = grounded.containsKey(id) && grounded.get(id);
            grounded.put(id, now);
            if (now && !before) {
                double[] pos = bridge().entityPosition(entity);
                if (pos != null) {
                    circles.add(new Circle(pos[0], Math.floor(pos[1]) + 0.001, pos[2], System.currentTimeMillis()));
                }
            }
        });
        long now = System.currentTimeMillis();
        circles.removeIf(circle -> now - circle.created > (easeOut.get() ? 5000L : 6000L));
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        long now = System.currentTimeMillis();
        for (Circle circle : circles) {
            float age = (now - circle.created) / (easeOut.get() ? 5000.0f : 6000.0f);
            float eased = easeOut.get() ? (float) (1.0 - Math.pow(1.0 - age, 4)) : age;
            int color = mode.is("Portal") ? 0xFF9B5CFF : 0xFFFFB7C5;
            color = color & 0xFFFFFF | (int) (255 * (1.0 - age)) << 24;
            WorldGeometry.circle(draw, projection, circle.x, circle.y, circle.z,
                    circleScale.get() * (0.2 + eased), 32,
                    rotateSpeed.asFloat() * 0.5f, color);
        }
    }

    @Override
    protected void onStop() {
        grounded.clear();
        circles.clear();
    }

    private static final class Circle {
        private final double x;
        private final double y;
        private final double z;
        private final long created;

        private Circle(double x, double y, double z, long created) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.created = created;
        }
    }
}
