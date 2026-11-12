package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.awt.Color;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * eps 上游 Kill Aura 的行为移植：共享选靶、受限旋转、射线校验与两种攻击节奏。
 *
 * <p>上游按手持食物判断 Pause On Eat；当前映射层没有食物组件，这里以“正在使用任意物品”
 * 近似，因此使用非食物物品时也会暂停攻击。Deobf 没有上游纹理资源，改用旋转线框与文字。
 */
public final class KillAuraModule extends GameModule implements WorldOverlay {
    private final BooleanValue pauseOnEat = add(new BooleanValue("Pause On Eat", true));
    private final BooleanValue pauseOnScaffold = add(new BooleanValue("Pause On Scaffold", true));
    private final BooleanValue hitSelect = add(new BooleanValue("Hit Select", true));
    private final ModeValue mode = add(new ModeValue("Mode", "1.8", "1.8", "1.9+"));
    private final ModeValue targetMode = add(new ModeValue("Target Mode", "Single", "Single", "Switch"));
    private final NumberValue switchDelay = add(new NumberValue("Switch Delay", 100, 0, 500, 1));
    private final ModeValue priorityMode = add(new ModeValue("Priority Mode", "None",
            "None", "Health", "Fov", "Range"));
    private final NumberValue searchRange = add(new NumberValue("Search Range", 4.0, 1, 6, 0.1));
    private final NumberValue aimRange = add(new NumberValue("Aim Range", 3.0, 1, 6, 0.1));
    private final NumberValue fov = add(new NumberValue("FOV", 360, 10, 360, 1));
    private final NumberValue rotationSpeed = add(new NumberValue("Rotation Speed", 180, 10, 180, 10));
    private final ModeValue rotationPriority = add(new ModeValue("Rotation Priority", "High",
            "Lowest", "Low", "Medium", "High", "Highest"));
    private final NumberValue cps = add(new NumberValue("CPS", 12, 1, 20, 1));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", true));
    private final BooleanValue animals = add(new BooleanValue("Animals", true));
    private final BooleanValue villagers = add(new BooleanValue("Villagers", false));
    private final BooleanValue ambient = add(new BooleanValue("Ambient", false));
    private final BooleanValue water = add(new BooleanValue("Water", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final BooleanValue invisible = add(new BooleanValue("Invisible", true));
    private final BooleanValue swingHand = add(new BooleanValue("SwingHand", true));
    private final BooleanValue esp = add(new BooleanValue("ESP", true));
    private final ModeValue espMode = add(new ModeValue("ESP Mode", "Circle",
            "CaptureMark", "Circle", "Firefly", "Deobf"));
    private final NumberValue deobfSize = add(new NumberValue("Deobf Size", 0.75, 0.25, 2, 0.05));
    private final NumberValue deobfSpins = add(new NumberValue("Deobf Spins", 3.0, 0.5, 8, 0.25));
    private final NumberValue deobfWobble = add(new NumberValue("Deobf Wobble", 1.0, 0, 2, 0.1));
    private final NumberValue deobfFlyHeight = add(new NumberValue("Deobf Fly Height", 5.0, 1, 12, 0.5));
    private final ColorValue espMain = add(new ColorValue("ESP Main", 0xFFFFB7C5));
    private final ColorValue espSecond = add(new ColorValue("ESP Second", 0xFFFF85A1));
    private final NumberValue espSize = add(new NumberValue("ESP Size", 1.2, 0.5, 3, 0.1));
    private final NumberValue rotSpeed = add(new NumberValue("Rot Speed", 2.0, 0.5, 10, 0.1));
    private final NumberValue waveSpeed = add(new NumberValue("Wave Speed", 3.0, 0.5, 10, 0.1));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0xFFFFFFFF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xE9FFFFFF));
    private final NumberValue circleRadius = add(new NumberValue("Circle Radius", 0.75, 0.1, 2, 0.05));
    private final NumberValue circleAlphaFactor = add(new NumberValue("Circle Alpha Factor", 1.0, 0, 2, 0.05));
    private final ModeValue fireflyColorMode = add(new ModeValue("Firefly Color Mode", "Blend",
            "Solid", "Blend", "Rainbow"));
    private final ColorValue fireflyColor = add(new ColorValue("Firefly Color", 0xFF959595));
    private final ColorValue fireflyColor2 = add(new ColorValue("Firefly Color 2", 0xFFFF85A1));
    private final NumberValue fireflyColorMix = add(new NumberValue("Firefly Color Mix", 0.65, 0, 1, 0.05));
    private final NumberValue fireflyColorSpeed = add(new NumberValue("Firefly Color Speed", 1.2, 0.1, 6, 0.1));
    private final NumberValue fireflyRainbowSpeed = add(new NumberValue("Firefly Rainbow Speed", 1.0, 0.1, 6, 0.1));
    private final NumberValue fireflyRainbowSaturation = add(new NumberValue(
            "Firefly Rainbow Saturation", 0.85, 0.1, 1, 0.05));
    private final NumberValue fireflyRainbowBrightness = add(new NumberValue(
            "Firefly Rainbow Brightness", 1.0, 0.1, 1, 0.05));
    private final NumberValue fireflyLength = add(new NumberValue("Firefly Length", 14, 8, 128, 1));
    private final NumberValue fireflyFactor = add(new NumberValue("Firefly Factor", 8, 1, 10, 1));
    private final NumberValue fireflyShaking = add(new NumberValue("Firefly Shaking", 1.8, 0.25, 10, 0.25));
    private final NumberValue fireflyAmplitude = add(new NumberValue("Firefly Amplitude", 3.0, 0, 10, 0.25));

    private TargetManager.Target target;
    private float aimYaw = Float.NaN;
    private int switchIndex;
    private long lastSwitch;
    private long nextAttack;
    private final Map<Integer, DeobfEffect> deobfEffects = new LinkedHashMap<Integer, DeobfEffect>();
    private final WorldProjection.ScreenPoint[] points = new WorldProjection.ScreenPoint[8];
    private final int[] captureColors = new int[4];

    public KillAuraModule() {
        super("Kill Aura", Category.COMBAT);
        for (int i = 0; i < points.length; i++) {
            points[i] = new WorldProjection.ScreenPoint();
        }
    }

    @Override
    protected void onStart() {
        listen(WorldChangeEvent.class, event -> clearState());
    }

    @Override
    protected void onStop() {
        clearState();
        NocturneClient client = client();
        if (client != null) {
            client.rotations().release(rotationPriorityValue());
        }
    }

    private void clearState() {
        target = null;
        aimYaw = Float.NaN;
        switchIndex = 0;
        lastSwitch = 0;
        nextAttack = 0;
        deobfEffects.clear();
    }

    @Override
    public void onTick() {
        NocturneClient client = client();
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (client == null || bridge == null || actions == null) {
            return;
        }
        PlayerState state = state();
        if (!state.present()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!esp.get() || !espMode.is("Deobf")) {
            deobfEffects.clear();
        } else {
            pruneDeobf(now);
        }
        if (pauseOnScaffold.get() && moduleEnabled("Scaffold")) {
            clearState();
            return;
        }

        TargetManager.Options options = new TargetManager.Options();
        options.range = searchRange.get();
        options.maxAngle = fov.get();
        options.players = players.get();
        options.mobs = mobs.get();
        options.animals = animals.get();
        options.villagers = villagers.get();
        options.ambient = ambient.get();
        options.water = water.get();
        options.others = others.get();
        options.invisibles = invisible.get();
        // 上游由攻击射线处理隔墙目标，选靶阶段不预先剔除。
        options.lineOfSight = false;
        options.sort = sortForPriority();
        List<TargetManager.Target> targets = client.targets().list(client, options);
        if (targets.isEmpty()) {
            target = null;
            aimYaw = Float.NaN;
            switchIndex = 0;
            return;
        }

        if (targetMode.is("Switch")) {
            if (now - lastSwitch >= switchDelay.asInt()) {
                switchIndex++;
                lastSwitch = now;
            }
        } else {
            switchIndex = 0;
        }
        switchIndex %= targets.size();
        target = targets.get(switchIndex);
        aim(target, state);
        attack(target, state);
    }

    private boolean moduleEnabled(String name) {
        NocturneClient client = client();
        Module module = client == null ? null : client.modules().get(name);
        return module != null && module.isEnabled();
    }

    private TargetManager.Sort sortForPriority() {
        if (priorityMode.is("Health")) {
            return TargetManager.Sort.HEALTH;
        }
        if (priorityMode.is("Fov")) {
            return TargetManager.Sort.ANGLE;
        }
        return TargetManager.Sort.DISTANCE;
    }

    private void aim(TargetManager.Target target, PlayerState state) {
        double dx = target.x() - state.x();
        double dz = target.z() - state.z();
        double horizontal = Math.hypot(dx, dz);
        float targetYaw = RotationController.yawTo(dx, dz);
        float targetPitch = RotationController.pitchTo(horizontal, target.aimY() - eyeY(state));
        float step = rotationSpeed.asFloat();
        boolean accepted = client().rotations().request(
                RotationController.approach(state.yaw(), targetYaw, step),
                RotationController.approach(state.pitch(), targetPitch, step),
                rotationPriorityValue(), 150_000_000L);
        aimYaw = accepted ? targetYaw : Float.NaN;
    }

    private double eyeY(PlayerState state) {
        GameBridge bridge = bridge();
        Object eye = bridge.callMapped(state.player(), ClassType.ENTITY, "getEyePosition", Float.valueOf(0.0f));
        if (eye == null) {
            eye = bridge.callMapped(state.player(), ClassType.ENTITY, "getEyePosition");
        }
        Object y = bridge.readField(eye, ClassType.VEC3, "y");
        return y instanceof Number ? ((Number) y).doubleValue() : state.y() + 1.62;
    }

    private int rotationPriorityValue() {
        if (rotationPriority.is("Lowest")) {
            return 0;
        }
        if (rotationPriority.is("Low")) {
            return 10;
        }
        if (rotationPriority.is("Medium")) {
            return 50;
        }
        if (rotationPriority.is("Highest")) {
            return 1000;
        }
        return 100;
    }

    private void attack(TargetManager.Target target, PlayerState state) {
        if (Float.isNaN(aimYaw) || !client().rotations().isAimedAt(aimYaw, 4.0f)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAttack) {
            return;
        }
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        Object hit = actions.pick(aimRange.get(), 1.0f);
        Object hitEntity = bridge.hitEntity(hit);
        if (hitSelect.get()) {
            if (hitEntity == null || hitEntity != target.entity()) {
                return;
            }
            if (bridge.isInstance(hitEntity, ClassType.PLAYER)) {
                Object self = state.player();
                if (bridge.isAllied(self, hitEntity)) {
                    return;
                }
                // 没有 ping 映射，上游的延迟 tick 按 0 处理；自身受击退时放行。
                if (bridge.hurtTime(hitEntity) > 1 && bridge.hurtTime(self) < 6) {
                    return;
                }
            }
        } else if (bridge.isBlockHit(hit)) {
            return;
        }
        if (mode.is("1.9+") && state.attackStrengthScale() < 1.0f) {
            return;
        }
        if (pauseOnEat.get() && bridge.usingItem(state.player())) {
            return;
        }
        if (!actions.attack(target.entity())) {
            return;
        }
        actions.swing(false, !swingHand.get());
        if (esp.get() && espMode.is("Deobf")) {
            markHit(target.entity());
        }
        nextAttack = mode.is("1.8")
                ? now + Math.max(1L, (long) Math.ceil(1000.0 / Math.max(1, cps.asInt())))
                : 0L;
    }

    private void markHit(Object entity) {
        int id = bridge().entityId(entity);
        if (id < 0) {
            return;
        }
        DeobfEffect current = deobfEffects.get(id);
        if (current == null || current.killedAtMs >= 0) {
            deobfEffects.put(id, new DeobfEffect(entity, System.currentTimeMillis()));
        }
    }

    private void pruneDeobf(long now) {
        GameBridge bridge = bridge();
        Iterator<DeobfEffect> iterator = deobfEffects.values().iterator();
        while (iterator.hasNext()) {
            DeobfEffect effect = iterator.next();
            if (!bridge.isInstance(effect.entity, ClassType.LIVING_ENTITY)) {
                iterator.remove();
                continue;
            }
            Float health = bridge.healthOrNull(effect.entity);
            if ((health == null || health <= 0.0f) && effect.killedAtMs < 0) {
                effect.killedAtMs = now;
            }
            if ((effect.killedAtMs >= 0 && now - effect.killedAtMs > 1800)
                    || now - effect.startedAtMs > 10_000) {
                iterator.remove();
            }
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!esp.get()) {
            return;
        }
        if (espMode.is("Deobf")) {
            drawDeobf(draw, projection);
            return;
        }
        TargetManager.Target current = target;
        if (current == null) {
            return;
        }
        if (espMode.is("CaptureMark")) {
            drawCaptureMark(draw, projection, current);
        } else if (espMode.is("Circle")) {
            drawCircle(draw, projection, current);
        } else {
            drawFirefly(draw, projection, current);
        }
    }

    /** CircleESP 的双水平环与侧墙线条近似。 */
    private void drawCircle(OverlayDraw draw, WorldProjection projection, TargetManager.Target target) {
        float ticks = (System.currentTimeMillis() % 1_000_000L) * 0.004f;
        float alpha = 0.35f + 0.65f * ((float) Math.sin(ticks * 1.8f) + 1.0f) * 0.5f
                * circleAlphaFactor.asFloat();
        double y = target.y() + 1.0 + Math.sin(ticks);
        int line = withAlpha(lineColor.argb(), alpha);
        int side = withAlpha(sideColor.argb(), 0.52f * alpha);
        double radius = circleRadius.get();
        WorldGeometry.circle(draw, projection, target.x(), y, target.z(), radius, 64, 2.0f, line);
        double lower = y - Math.sin(ticks + 1.0f) / 2.7;
        WorldGeometry.circle(draw, projection, target.x(), lower, target.z(), radius, 64, 1.0f, side);
        for (int i = 0; i < 32; i++) {
            double angle = Math.PI * 2.0 * i / 32.0;
            double x = target.x() + Math.cos(angle) * radius;
            double z = target.z() + Math.sin(angle) * radius;
            projection.project(x, lower, z, points[0]);
            projection.project(x, y, z, points[1]);
            if (points[0].visible && points[1].visible) {
                draw.line(points[0].x, points[0].y, points[1].x, points[1].y, 1.0f, side);
            }
        }
    }

    /** CaptureMarkESP 的旋转 billboard 四角连线近似。 */
    private void drawCaptureMark(OverlayDraw draw, WorldProjection projection, TargetManager.Target target) {
        double timeSeconds = System.currentTimeMillis() * 0.001;
        float rotation = (float) -((timeSeconds * rotSpeed.get() * 60.0) % 360.0);
        double size = espSize.get() * 0.5;
        double centerY = target.y() + target.height() * 0.5;
        for (int corner = 0; corner < 4; corner++) {
            double angle = Math.toRadians(rotation + corner * 90.0f);
            double horizontal = Math.cos(angle) * size;
            double vertical = Math.sin(angle) * size;
            double x = target.x() + horizontal * projection.rightX() + vertical * projection.upX();
            double y = centerY + horizontal * projection.rightY() + vertical * projection.upY();
            double z = target.z() + horizontal * projection.rightZ() + vertical * projection.upZ();
            projection.project(x, y, z, points[corner]);
            float wave = (float) ((Math.sin(corner * Math.PI * 0.5
                    + timeSeconds * waveSpeed.get()) + 1.0) * 0.5);
            captureColors[corner] = blend(espMain.argb(), espSecond.argb(), wave);
        }
        for (int corner = 0; corner < 4; corner++) {
            WorldProjection.ScreenPoint first = points[corner];
            WorldProjection.ScreenPoint second = points[(corner + 1) % 4];
            if (first.visible && second.visible) {
                draw.line(first.x, first.y, second.x, second.y, 2.0f, captureColors[corner]);
            }
        }
    }

    /** FireflyESP 的三层环绕轨迹文字近似。 */
    private void drawFirefly(OverlayDraw draw, WorldProjection projection, TargetManager.Target target) {
        Number tickCount = number(bridge().readField(target.entity(), ClassType.ENTITY, "tickCount"));
        float age = tickCount == null ? 0.0f : tickCount.floatValue();
        double width = target.width();
        double halfHeight = target.height() * 0.5;
        int length = fireflyLength.asInt();
        float factor = fireflyFactor.asFloat();
        for (int layer = 0; layer < 3; layer++) {
            for (int i = 0; i <= length; i++) {
                double radians = Math.toRadians((((i / 1.5f) + age) * factor + layer * 120.0f)
                        % (factor * 360.0f));
                double sinQuad = Math.sin(Math.toRadians(age * 2.5f + i * (layer + 1))
                        * fireflyAmplitude.get()) / fireflyShaking.get();
                double x = target.x() + Math.cos(radians) * width;
                double y = target.y() + halfHeight + sinQuad;
                double z = target.z() + Math.sin(radians) * width;
                float offset = i / (float) length;
                projection.project(x, y, z, points[0]);
                if (points[0].visible) {
                    draw.text("✦", points[0].x, points[0].y, 2.0f + 6.0f * offset,
                            fireflyColor(age, offset, layer));
                }
            }
        }
    }

    private int fireflyColor(float age, float offset, int layer) {
        if (fireflyColorMode.is("Solid")) {
            return fireflyColor.argb();
        }
        if (fireflyColorMode.is("Rainbow")) {
            float hue = frac(age * 0.01f * fireflyRainbowSpeed.asFloat() + offset + layer * 0.17f);
            int rgb = Color.HSBtoRGB(hue, fireflyRainbowSaturation.asFloat(),
                    fireflyRainbowBrightness.asFloat());
            return (fireflyColor.argb() & 0xFF000000) | (rgb & 0x00FFFFFF);
        }
        float wave = (float) ((Math.sin(age * fireflyColorSpeed.asFloat() * 0.25f
                + offset * Math.PI * 2.0 + layer) + 1.0) * 0.5);
        return blend(fireflyColor.argb(), fireflyColor2.argb(),
                clamp(wave * fireflyColorMix.asFloat()));
    }

    /** DeobfESP 命中特效的旋转线框近似，不加载纹理。 */
    private void drawDeobf(OverlayDraw draw, WorldProjection projection) {
        long now = System.currentTimeMillis();
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        for (DeobfEffect effect : deobfEffects.values()) {
            float intro = Math.min(1.0f, (now - effect.startedAtMs) / 1600.0f);
            double size = deobfSize.get() * intro;
            if (size <= 0.0) {
                continue;
            }
            Object position = bridge.callMapped(effect.entity, ClassType.ENTITY, "position");
            Number xValue = number(bridge.readField(position, ClassType.VEC3, "x"));
            Number yValue = number(bridge.readField(position, ClassType.VEC3, "y"));
            Number zValue = number(bridge.readField(position, ClassType.VEC3, "z"));
            if (xValue == null || yValue == null || zValue == null) {
                continue;
            }
            double x = xValue.doubleValue();
            double feetY = yValue.doubleValue();
            double z = zValue.doubleValue();
            double time = now * 0.001;
            double topBaseY = feetY + deobfFlyHeight.get() * 0.1
                    + Math.sin(time * waveSpeed.get()) * deobfWobble.get();
            float rotation = (float) ((time * deobfSpins.get() * 60.0) % 360.0);
            int color = espSecond.argb();
            if (effect.killedAtMs >= 0) {
                color = withAlpha(color, Math.max(0.0f, 1.0f - (now - effect.killedAtMs) / 1800.0f));
            }
            for (int corner = 0; corner < 4; corner++) {
                double angle = Math.toRadians(rotation + corner * 90.0f);
                double px = x + Math.cos(angle) * size;
                double pz = z + Math.sin(angle) * size;
                projection.project(px, feetY, pz, points[corner]);
                projection.project(px, topBaseY + size * 2.0, pz, points[corner + 4]);
            }
            for (int corner = 0; corner < 4; corner++) {
                int next = (corner + 1) % 4;
                line(draw, points[corner], points[next], 2.0f, color);
                line(draw, points[corner + 4], points[next + 4], 2.0f, color);
                line(draw, points[corner], points[corner + 4], 1.0f, color);
            }
            projection.project(x, topBaseY + size, z, points[0]);
            if (points[0].visible) {
                draw.text("◈", points[0].x, points[0].y, 12.0f, espMain.argb());
            }
        }
    }

    private static void line(OverlayDraw draw, WorldProjection.ScreenPoint first,
                             WorldProjection.ScreenPoint second, float width, int color) {
        if (first.visible && second.visible) {
            draw.line(first.x, first.y, second.x, second.y, width, color);
        }
    }

    private static int withAlpha(int argb, float factor) {
        int alpha = Math.round(((argb >>> 24) & 255) * clamp(factor));
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int first, int second, float amount) {
        float t = clamp(amount);
        int red = Math.round(((first >> 16) & 255) * (1.0f - t) + ((second >> 16) & 255) * t);
        int green = Math.round(((first >> 8) & 255) * (1.0f - t) + ((second >> 8) & 255) * t);
        int blue = Math.round((first & 255) * (1.0f - t) + (second & 255) * t);
        return (first & 0xFF000000) | (red << 16) | (green << 8) | blue;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float frac(float value) {
        return (float) (value - Math.floor(value));
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static final class DeobfEffect {
        private final Object entity;
        private final long startedAtMs;
        private long killedAtMs = -1;

        private DeobfEffect(Object entity, long startedAtMs) {
            this.entity = entity;
            this.startedAtMs = startedAtMs;
        }
    }
}
