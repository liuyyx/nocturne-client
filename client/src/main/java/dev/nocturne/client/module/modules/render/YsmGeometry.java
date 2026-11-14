package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.util.MiniJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bedrock 几何的立方体子集；不解释骨骼层级、旋转、贴图和动画。 */
final class YsmGeometry {
    static final class Cube {
        final String bone;
        final double x, y, z, width, height, depth;
        final int color;

        Cube(String bone, double x, double y, double z, double width, double height, double depth) {
            this.bone = bone;
            this.x = x; this.y = y; this.z = z;
            this.width = width; this.height = height; this.depth = depth;
            this.color = 0xFF000000 | (bone.hashCode() & 0xFFFFFF);
        }
    }

    final Cube[] cubes;

    private YsmGeometry(List<Cube> cubes) { this.cubes = cubes.toArray(new Cube[cubes.size()]); }

    /** 读取全部 geometry 的具名骨骼及立方体；结构错误时拒绝整个模型。 */
    static YsmGeometry parse(String json) {
        Map<?, ?> root = object(MiniJson.parse(json));
        List<?> geometries = array(root.get("minecraft:geometry"));
        List<Cube> cubes = new ArrayList<Cube>();
        for (Object geometry : geometries) {
            for (Object boneValue : array(object(geometry).get("bones"))) {
                Map<?, ?> bone = object(boneValue);
                Object name = bone.get("name");
                if (!(name instanceof String) || ((String) name).isEmpty()) throw new IllegalArgumentException("bone name");
                Object cubeValues = bone.get("cubes");
                if (cubeValues == null) continue;
                for (Object cubeValue : array(cubeValues)) {
                    Map<?, ?> cube = object(cubeValue);
                    double[] origin = vector(cube.get("origin"));
                    double[] size = vector(cube.get("size"));
                    if (size[0] < 0 || size[1] < 0 || size[2] < 0) throw new IllegalArgumentException("negative size");
                    cubes.add(new Cube((String) name, origin[0], origin[1], origin[2], size[0], size[1], size[2]));
                }
            }
        }
        if (cubes.isEmpty()) throw new IllegalArgumentException("empty geometry");
        return new YsmGeometry(cubes);
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("expected object");
        return (Map<?, ?>) value;
    }

    private static List<?> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("expected array");
        return (List<?>) value;
    }

    private static double[] vector(Object value) {
        List<?> values = array(value);
        if (values.size() != 3) throw new IllegalArgumentException("expected three coordinates");
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) {
            Object item = values.get(i);
            if (!(item instanceof Number)) throw new IllegalArgumentException("expected number");
            result[i] = ((Number) item).doubleValue();
            if (Double.isNaN(result[i]) || Double.isInfinite(result[i])) throw new IllegalArgumentException("non-finite coordinate");
        }
        return result;
    }

    static YsmGeometry builtin(String name) {
        double factor = "ToyotaAe86".equals(name) ? 1.4 : "Boqijiang".equals(name) ? .9 : 1;
        List<Cube> cubes = new ArrayList<Cube>();
        add(cubes, "head", -4, 24, -4, 8, 8, 8, factor);
        add(cubes, "body", -4, 12, -2, 8, 12, 4, factor);
        add(cubes, "left_arm", 4, 12, -2, 4, 12, 4, factor);
        add(cubes, "right_arm", -8, 12, -2, 4, 12, 4, factor);
        add(cubes, "left_leg", 0, 0, -2, 4, 12, 4, factor);
        add(cubes, "right_leg", -4, 0, -2, 4, 12, 4, factor);
        if ("Alice".equals(name) || "Yatuoya".equals(name)) add(cubes, "hair", -5, 23, 3, 10, 10, 2, factor);
        return new YsmGeometry(cubes);
    }

    private static void add(List<Cube> cubes, String name, double x, double y, double z,
                            double w, double h, double d, double factor) {
        cubes.add(new Cube(name, x * factor, y * factor, z * factor, w * factor, h * factor, d * factor));
    }

    static boolean matches(String bone, String needle, boolean exclude, boolean empty) {
        return empty || bone.contains(needle) != exclude;
    }
}
