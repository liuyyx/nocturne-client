package dev.nocturne.client.event;

/** 本地玩家实际位移前沿；修改 x/y/z 会替换本次 Entity.move 的实参。 */
public final class MoveEvent {

    public double x;
    public double y;
    public double z;
    private final double originalX;
    private final double originalY;
    private final double originalZ;

    public MoveEvent(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.originalX = x;
        this.originalY = y;
        this.originalZ = z;
    }

    boolean changed() {
        return x != originalX || y != originalY || z != originalZ;
    }

    boolean finite() {
        return !Double.isNaN(x) && !Double.isInfinite(x)
                && !Double.isNaN(y) && !Double.isInfinite(y)
                && !Double.isNaN(z) && !Double.isInfinite(z);
    }
}
