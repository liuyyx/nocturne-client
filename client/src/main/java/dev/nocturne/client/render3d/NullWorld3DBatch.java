package dev.nocturne.client.render3d;

/** 批次空实现：后端没给出批次时用它兜底，模块代码不必判空。 */
final class NullWorld3DBatch implements World3DBatch {

    static final World3DBatch INSTANCE = new NullWorld3DBatch();

    private NullWorld3DBatch() {
    }

    @Override
    public World3DBatch color(int argb) {
        return this;
    }

    @Override
    public World3DBatch lineWidth(float width) {
        return this;
    }

    @Override
    public World3DBatch depthTest(boolean enabled) {
        return this;
    }

    @Override
    public World3DBatch cull(boolean enabled) {
        return this;
    }

    @Override
    public World3DBatch blend(boolean enabled) {
        return this;
    }

    @Override
    public void line(double x1, double y1, double z1, double x2, double y2, double z2) {
    }

    @Override
    public void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    @Override
    public void ring(double centerX, double centerY, double centerZ, double radius, int segments) {
    }

    @Override
    public void triangleStrip(float[] xyz, int vertexCount) {
    }

    @Override
    public void flush() {
    }
}
