package dev.nocturne.client.render3d;

/**
 * 世界空间 3D 通道的现场自检覆盖层：只在 {@code -Dnocturne.debug.world3d} 下启用。
 *
 * <p>画两样东西，用来证明「世界坐标经相机变换后落在正确位置、且没有污染 HUD」：
 * <ul>
 *   <li>准星正前方 3 格处的绿色线框立方体（1×1×1）；</li>
 *   <li>以相机眼位为圆心、半径 1.5 的白色水平线环。</li>
 * </ul>
 *
 * <p>不依赖玩家/世界状态：位置完全从相机快照推导，主菜单或自由视角下也能看到（后端可用时）。
 * 生产构建里没有任何东西会创建它——属性缺省为关闭。
 */
final class World3DDebugOverlay implements World3DOverlay {

    /** 只在第一次绘制时打日志，避免每帧刷屏。 */
    private boolean logged;

    @Override
    public void drawWorld3D(World3DContext context) {
        WorldCamera camera = context.camera();
        if (camera == null || !camera.valid()) {
            return;
        }
        double yaw = Math.toRadians(camera.yaw());
        double pitch = Math.toRadians(camera.pitch());
        double forwardX = -Math.sin(yaw) * Math.cos(pitch);
        double forwardY = -Math.sin(pitch);
        double forwardZ = Math.cos(yaw) * Math.cos(pitch);
        double centerX = camera.eyeX() + forwardX * 3.0d;
        double centerY = camera.eyeY() + forwardY * 3.0d;
        double centerZ = camera.eyeZ() + forwardZ * 3.0d;

        World3DBatch batch = context.batch();
        batch.depthTest(true).blend(false).cull(false).lineWidth(2.5f);
        batch.color(0xFF00FF66);
        batch.box(centerX - 0.5d, centerY - 0.5d, centerZ - 0.5d,
                centerX + 0.5d, centerY + 0.5d, centerZ + 0.5d);
        batch.color(0xFFFFFFFF);
        batch.ring(camera.eyeX(), camera.eyeY(), camera.eyeZ(), 1.5d, 64);
        batch.flush();

        if (!logged) {
            logged = true;
            System.out.println("[nocturne] world3d debug overlay drawing: box=("
                    + round(centerX) + "," + round(centerY) + "," + round(centerZ)
                    + ") ring r=1.5 viewport=" + context.viewportWidth() + "x"
                    + context.viewportHeight());
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
