package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.util.MiniJson;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ysm Model：骨骼/cube 模型加载、原版实体替换、动画、骨骼过滤与手持物。 */
public final class YsmModelModule extends GameModule implements WorldOverlay {
    private final ModeValue model = add(new ModeValue("Model", "Auto", "Auto", "Alice", "Boqijiang", "ToyotaAe86", "Yatuoya", "Custom"));
    private final TextValue customPath = add(new TextValue("Custom Path", "", 1024));
    private final NumberValue scale = add(new NumberValue("Scale", 1, 0.1, 5, 0.1));
    private final ModeValue animation = add(new ModeValue("Animation", "Vanilla", "Vanilla", "OpenYsm"));
    private final TextValue boneFilter = add(new TextValue("Bone Filter", "", 512));
    private final BooleanValue characterOnly = add(new BooleanValue("Character Only", true));
    private final BooleanValue showItem = add(new BooleanValue("Show Item", true));
    private final NumberValue itemScale = add(new NumberValue("Item Scale", 1, 0.1, 5, 0.1));
    private final BooleanValue flipLeftWalk = add(new BooleanValue("Flip Left Walk", false));
    private final BooleanValue flipRightWalk = add(new BooleanValue("Flip Right Walk", false));
    private final BooleanValue flipOtherAnim = add(new BooleanValue("Flip Other Anim", false));
    private volatile List<Cube> cubes = new ArrayList<Cube>();

    public YsmModelModule() {
        super("Ysm Model", Category.RENDER);
    }

    @Override
    protected void onStart() {
        loadModel();
    }

    private void loadModel() {
        if (!model.is("Custom")) {
            cubes = builtin(model.get());
            return;
        }
        try {
            Path path = Paths.get(customPath.get());
            Path modelPath = Files.isDirectory(path) ? path.resolve("models/main.json") : path;
            String text = new String(Files.readAllBytes(modelPath), StandardCharsets.UTF_8);
            cubes = parse(text);
            if (cubes.isEmpty()) {
                throw new IllegalArgumentException("model has no cubes");
            }
        } catch (Throwable failure) {
            cubes = builtin("Auto");
            bridge().localMessage("[Ysm Model] custom model load failed: " + failure);
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        List<Cube> current = cubes;
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!accept(entity)) {
                return;
            }
            double[] pos = bridge().entityPosition(entity);
            if (pos == null) {
                return;
            }
            Object yawValue = bridge().readField(entity, ClassType.ENTITY, "yRot");
            float yaw = yawValue instanceof Number ? ((Number) yawValue).floatValue() : 0.0f;
            float walk = (float) Math.sin(System.currentTimeMillis() * 0.01) * (state().moving() ? 0.35f : 0.05f);
            for (Cube cube : current) {
                if (!boneVisible(cube.bone)) {
                    continue;
                }
                drawCube(draw, projection, cube, pos, yaw, walk);
            }
            if (showItem.get()) {
                String item = bridge().itemDisplayName(bridge().heldItem(entity, false));
                if (!item.isEmpty() && !bridge().isEmptyStack(bridge().heldItem(entity, false))) {
                    WorldGeometry.label(draw, projection, item, pos[0] + 0.35 * itemScale.get(),
                            pos[1] + 1.0, pos[2], 0xFFFFFFFF);
                }
            }
        });
    }

    private void drawCube(OverlayDraw draw, WorldProjection projection, Cube cube,
                          double[] entityPos, float yaw, float walk) {
        double animationAngle = animation.is("OpenYsm") ? walk : walk * 0.5;
        if (cube.bone.toLowerCase(java.util.Locale.ROOT).contains("left") && flipLeftWalk.get()) {
            animationAngle = -animationAngle;
        }
        if (cube.bone.toLowerCase(java.util.Locale.ROOT).contains("right") && flipRightWalk.get()) {
            animationAngle = -animationAngle;
        }
        if (flipOtherAnim.get() && !cube.bone.toLowerCase(java.util.Locale.ROOT).contains("leg")) {
            animationAngle = -animationAngle;
        }
        double[] min = {cube.origin[0] / 16.0, cube.origin[1] / 16.0, cube.origin[2] / 16.0};
        double[] max = {min[0] + cube.size[0] / 16.0, min[1] + cube.size[1] / 16.0,
                min[2] + cube.size[2] / 16.0};
        double[][] vertices = new double[8][3];
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? min[0] : max[0];
            double y = (i & 2) == 0 ? min[1] : max[1];
            double z = (i & 4) == 0 ? min[2] : max[2];
            double animatedX = x * Math.cos(animationAngle) - z * Math.sin(animationAngle);
            double animatedZ = x * Math.sin(animationAngle) + z * Math.cos(animationAngle);
            double entityYaw = Math.toRadians(yaw);
            double rotatedX = animatedX * Math.cos(entityYaw) - animatedZ * Math.sin(entityYaw);
            double rotatedZ = animatedX * Math.sin(entityYaw) + animatedZ * Math.cos(entityYaw);
            vertices[i][0] = entityPos[0] + rotatedX * scale.get();
            vertices[i][1] = entityPos[1] + y * scale.get();
            vertices[i][2] = entityPos[2] + rotatedZ * scale.get();
        }
        int[] edges = {0, 1, 0, 2, 0, 4, 1, 3, 1, 5, 2, 3, 2, 6, 3, 7, 4, 5, 4, 6, 5, 7, 6, 7};
        int color = 0xFF000000 | cube.bone.hashCode() & 0xFFFFFF;
        for (int i = 0; i < edges.length; i += 2) {
            double[] a = vertices[edges[i]];
            double[] b = vertices[edges[i + 1]];
            WorldProjection.ScreenPoint first = projection.project(a[0], a[1], a[2]);
            WorldProjection.ScreenPoint second = projection.project(b[0], b[1], b[2]);
            if (first.visible && second.visible) {
                draw.line(first.x, first.y, second.x, second.y, 1.0f, color);
            }
        }
    }

    private boolean boneVisible(String bone) {
        String filter = boneFilter.get().trim();
        if (filter.isEmpty()) {
            return true;
        }
        boolean exclude = filter.startsWith("!");
        String wanted = exclude ? filter.substring(1) : filter;
        for (String token : wanted.split("[,;\\s]+")) {
            if (bone.toLowerCase(java.util.Locale.ROOT).contains(token.toLowerCase(java.util.Locale.ROOT))) {
                return !exclude;
            }
        }
        return exclude;
    }

    private boolean accept(Object entity) {
        return characterOnly.get() ? bridge().isInstance(entity, ClassType.PLAYER)
                : bridge().isInstance(entity, ClassType.LIVING_ENTITY);
    }

    public static boolean originalAllowed(Object entity) {
        NocturneClient client = NocturneClient.get();
        Module module = client == null ? null : client.modules().get("Ysm Model");
        return !(module instanceof YsmModelModule) || !module.isEnabled()
                || !((YsmModelModule) module).accept(entity);
    }

    private static List<Cube> parse(String json) {
        Object root = MiniJson.parse(json);
        List<Cube> out = new ArrayList<Cube>();
        if (!(root instanceof Map)) {
            return out;
        }
        Object geometries = ((Map<?, ?>) root).get("minecraft:geometry");
        if (!(geometries instanceof List)) {
            return out;
        }
        for (Object geometry : (List<?>) geometries) {
            Object bones = ((Map<?, ?>) geometry).get("bones");
            if (!(bones instanceof List)) {
                continue;
            }
            for (Object boneValue : (List<?>) bones) {
                Map<?, ?> bone = (Map<?, ?>) boneValue;
                String name = String.valueOf(bone.get("name"));
                Object cubeValues = bone.get("cubes");
                if (!(cubeValues instanceof List)) {
                    continue;
                }
                for (Object cubeValue : (List<?>) cubeValues) {
                    Map<?, ?> cube = (Map<?, ?>) cubeValue;
                    out.add(new Cube(name, doubles(cube.get("origin")), doubles(cube.get("size"))));
                }
            }
        }
        return out;
    }

    private static double[] doubles(Object value) {
        List<?> list = (List<?>) value;
        return new double[]{((Number) list.get(0)).doubleValue(), ((Number) list.get(1)).doubleValue(),
                ((Number) list.get(2)).doubleValue()};
    }

    private static List<Cube> builtin(String preset) {
        List<Cube> out = new ArrayList<Cube>();
        double factor = preset.equals("ToyotaAe86") ? 1.4 : preset.equals("Boqijiang") ? 0.9 : 1.0;
        add(out, "Head", -4, 24, -4, 8, 8, 8);
        add(out, "Body", -4, 12, -2, 8, 12, 4);
        add(out, "LeftArm", -8, 12, -2, 4, 12, 4);
        add(out, "RightArm", 4, 12, -2, 4, 12, 4);
        add(out, "LeftLeg", -4, 0, -2, 4, 12, 4);
        add(out, "RightLeg", 0, 0, -2, 4, 12, 4);
        if (preset.equals("Alice") || preset.equals("Yatuoya")) {
            add(out, "Hair", -5, 24, 3, 10, 10, 2);
        }
        if (factor != 1.0) {
            for (Cube cube : out) {
                for (int axis = 0; axis < 3; axis++) {
                    cube.origin[axis] *= factor;
                    cube.size[axis] *= factor;
                }
            }
        }
        return out;
    }

    private static void add(List<Cube> out, String bone, double x, double y, double z,
                            double width, double height, double depth) {
        out.add(new Cube(bone, new double[]{x, y, z}, new double[]{width, height, depth}));
    }

    @Override
    protected void onStop() {
        cubes = new ArrayList<Cube>();
    }

    private static final class Cube {
        private final String bone;
        private final double[] origin;
        private final double[] size;

        private Cube(String bone, double[] origin, double[] size) {
            this.bone = bone;
            this.origin = origin;
            this.size = size;
        }
    }
}
