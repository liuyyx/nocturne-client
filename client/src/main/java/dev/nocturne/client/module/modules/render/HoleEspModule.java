package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Collections;
import java.util.List;

/**
 * 单格、双格、四格安全洞与基岩墙的二维投影高亮。
 *
 * <p>上游降级：立体填色沿真实投影面绘制，描边使用十二边线框。标记贴到实际支撑方块、
 * 顶棚方块或三连基岩墙，不再为相邻空气格涂出悬空盒。Air Y Check 与 Side Check
 * 不再作为设置暴露；安全洞仍要求每格下方和全部外墙都是基岩。
 */
public final class HoleEspModule extends GameModule implements WorldOverlay {
    private final NumberValue startFade = add(new NumberValue("Start Fade", 5.0, 1.0, 20.0, 0.1));
    private final NumberValue distance = add(new NumberValue("Distance", 6.0, 1.0, 20.0, 0.1));
    private final NumberValue height = add(new NumberValue("Height", 1.0, -3.0, 3.0, 0.1));
    private final NumberValue updateDelay = add(new NumberValue("Update Delay", 50, 0, 1000, 10));

    private final NumberValue airHeight = add(new NumberValue("Air Height", 1.0, -3.0, 3.0, 0.01));
    private final BooleanValue airFill = add(new BooleanValue("Air Fill", true));
    private final ColorValue airFillColor = add(new ColorValue("Air Fill Color", 0x64940000));
    private final BooleanValue airFade = add(new BooleanValue("Air Fade", true));
    private final ColorValue airFadeColor = add(new ColorValue("Air Fade Color", 0x00940000));
    private final BooleanValue airBox = add(new BooleanValue("Air Box", true));
    private final ColorValue airBoxColor = add(new ColorValue("Air Box Color", 0x64940000));

    private final BooleanValue unsafeFill = add(new BooleanValue("Unsafe Fill", true));
    private final ColorValue unsafeFillColor = add(new ColorValue("Unsafe Fill Color", 0x32FF0000));
    private final BooleanValue unsafeFade = add(new BooleanValue("Unsafe Fade", true));
    private final ColorValue unsafeFadeColor = add(new ColorValue("Unsafe Fade Color", 0x00FF0000));
    private final BooleanValue unsafeBox = add(new BooleanValue("Unsafe Box", true));
    private final ColorValue unsafeBoxColor = add(new ColorValue("Unsafe Box Color", 0x64FF0000));

    private final BooleanValue safeFill = add(new BooleanValue("Safe Fill", true));
    private final ColorValue safeFillColor = add(new ColorValue("Safe Fill Color", 0x3208FF4F));
    private final BooleanValue safeFade = add(new BooleanValue("Safe Fade", true));
    private final ColorValue safeFadeColor = add(new ColorValue("Safe Fade Color", 0x6408FF4F));
    private final BooleanValue safeBox = add(new BooleanValue("Safe Box", true));
    private final ColorValue safeBoxColor = add(new ColorValue("Safe Box Color", 0x6408FF4F));

    private final NumberValue wallHeight = add(new NumberValue("Wall Height", 3.0, -3.0, 3.0, 0.1));
    private final BooleanValue wallFill = add(new BooleanValue("Wall Fill", true));
    private final ColorValue wallFillColor = add(new ColorValue("Wall Fill Color", 0x8000FFFF));
    private final BooleanValue wallFade = add(new BooleanValue("Wall Fade", true));
    private final ColorValue wallFadeColor = add(new ColorValue("Wall Fade Color", 0x4000FFFF));
    private final BooleanValue wallBox = add(new BooleanValue("Wall Box", true));
    private final ColorValue wallBoxColor = add(new ColorValue("Wall Box Color", 0xFF00E1FF));

    private final BooleanValue wallSideFill = add(new BooleanValue("Wall Side Fill", true));
    private final ColorValue wallSideFillColor = add(new ColorValue("Wall Side Fill Color", 0x8000FFFF));
    private final BooleanValue wallSideFade = add(new BooleanValue("Wall Side Fade", true));
    private final ColorValue wallSideFadeColor = add(new ColorValue("Wall Side Fade Color", 0x4000FFFF));
    private final BooleanValue wallSideBox = add(new BooleanValue("Wall Side Box", true));
    private final ColorValue wallSideBoxColor = add(new ColorValue("Wall Side Box Color", 0xFF00E1FF));

    private volatile List<int[]> snapshots = Collections.emptyList();
    private Object scannedWorld;
    private long nextScanAt;
    private double playerX;
    private double playerY;
    private double playerZ;

    public HoleEspModule() {
        super("Hole ESP", Category.RENDER);
    }

    @Override
    protected void onStart() {
        clear();
        listen(WorldChangeEvent.class, event -> clear());
    }

    @Override
    protected void onStop() {
        clear();
    }

    private void clear() {
        snapshots = Collections.emptyList();
        scannedWorld = null;
        nextScanAt = 0L;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            snapshots = Collections.emptyList();
            scannedWorld = null;
            return;
        }
        playerX = state.x();
        playerY = state.y();
        playerZ = state.z();
        Object world = bridge.world();
        if (world == null) {
            snapshots = Collections.emptyList();
            scannedWorld = null;
            return;
        }
        if (world != scannedWorld) {
            snapshots = Collections.emptyList();
            scannedWorld = world;
            nextScanAt = 0L;
        }
        long now = System.currentTimeMillis();
        if (now < nextScanAt) {
            return;
        }
        nextScanAt = now + updateDelay.asInt();
        snapshots = scan(bridge, world, state);
    }

    private List<int[]> scan(GameBridge bridge, Object world, PlayerState state) {
        int radius = (int) Math.ceil(distance.get()) + 1;
        int centerX = (int) Math.floor(state.x());
        int centerY = (int) Math.floor(state.y() + 1.62);
        int centerZ = (int) Math.floor(state.z());
        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minY = centerY - 3;
        int maxY = centerY + 3;
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;

        if (world == null) {
            return Collections.emptyList();
        }
        // 网格向外扩一圈，并多取下方一格、上方两格，供洞侧、洞顶和三连墙判定复用。
        HoleScan.Grid grid = new HoleScan.Grid(
                minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 2, maxZ + 1);
        for (int y = minY - 1; y <= maxY + 2; y++) {
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    Object position = bridge.blockPos(x, y, z);
                    Object blockState = bridge.blockStateAt(world, position);
                    String description = bridge.blockDescription(blockState);
                    boolean collision = blockState != null
                            && bridge.hasBlockCollision(world, blockState, position);
                    grid.set(x, y, z, bridge.isAir(blockState), collision,
                            description.indexOf("bedrock") >= 0);
                }
            }
        }
        return HoleScan.scan(grid, minX, maxX, minY, maxY, minZ, maxZ);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        List<int[]> current = snapshots;
        double fadeStart = startFade.get();
        double renderDistance = distance.get();
        for (int i = 0; i < current.size(); i++) {
            int[] snapshot = current.get(i);
            double centerX = (snapshot[0] + snapshot[3]) * 0.5;
            double centerY = markerY(snapshot) + 0.5;
            double centerZ = (snapshot[2] + snapshot[4]) * 0.5;
            double dx = centerX - playerX;
            double dy = centerY - playerY;
            double dz = centerZ - playerZ;
            double alpha = HoleScan.distanceAlpha(Math.sqrt(dx * dx + dy * dy + dz * dz),
                    fadeStart, renderDistance);
            if (alpha <= 0.0) {
                continue;
            }
            drawSnapshot(draw, projection, snapshot, alpha);
        }
    }

    private void drawSnapshot(OverlayDraw draw, WorldProjection projection, int[] snapshot, double alpha) {
        BooleanValue fill;
        ColorValue fillColor;
        BooleanValue fade;
        ColorValue fadeColor;
        BooleanValue outline;
        ColorValue outlineColor;
        double boxHeight;
        switch (snapshot[5]) {
            case HoleScan.AIR:
                fill = airFill;
                fillColor = airFillColor;
                fade = airFade;
                fadeColor = airFadeColor;
                outline = airBox;
                outlineColor = airBoxColor;
                boxHeight = airHeight.get();
                break;
            case HoleScan.UNSAFE:
                fill = unsafeFill;
                fillColor = unsafeFillColor;
                fade = unsafeFade;
                fadeColor = unsafeFadeColor;
                outline = unsafeBox;
                outlineColor = unsafeBoxColor;
                boxHeight = height.get();
                break;
            case HoleScan.SAFE:
                fill = safeFill;
                fillColor = safeFillColor;
                fade = safeFade;
                fadeColor = safeFadeColor;
                outline = safeBox;
                outlineColor = safeBoxColor;
                boxHeight = height.get();
                break;
            case HoleScan.WALL:
                fill = wallFill;
                fillColor = wallFillColor;
                fade = wallFade;
                fadeColor = wallFadeColor;
                outline = wallBox;
                outlineColor = wallBoxColor;
                boxHeight = wallHeight.get();
                break;
            case HoleScan.WALL_SIDE:
                fill = wallSideFill;
                fillColor = wallSideFillColor;
                fade = wallSideFade;
                fadeColor = wallSideFadeColor;
                outline = wallSideBox;
                outlineColor = wallSideBoxColor;
                boxHeight = height.get();
                break;
            default:
                return;
        }
        double y = markerY(snapshot);
        double oppositeY = y + boxHeight;
        double minY = Math.min(y, oppositeY);
        double maxY = Math.max(y, oppositeY);
        int minX = snapshot[0];
        int maxX = snapshot[3];
        int minZ = snapshot[2];
        int maxZ = snapshot[4];
        if (fill.get()) {
            int top = withDistanceAlpha(fillColor.argb(), alpha);
            if (fade.get()) {
                int bottom = withDistanceAlpha(fadeColor.argb(), alpha);
                WorldGeometry.silhouetteFade(draw, projection, minX, minY, minZ, maxX, maxY, maxZ,
                        top, bottom);
            } else {
                WorldGeometry.silhouette(draw, projection, minX, minY, minZ, maxX, maxY, maxZ, top);
            }
        }
        if (outline.get()) {
            WorldGeometry.box(draw, projection, minX, minY, minZ, maxX, maxY, maxZ, 1.0f,
                    withDistanceAlpha(outlineColor.argb(), alpha));
        }
    }

    /** 扫描坐标代表可站立空气格；着色坐标必须落到支撑块，顶棚则取空气格上方的实体块。 */
    private static double markerY(int[] snapshot) {
        return snapshot[1] + (snapshot[5] == HoleScan.AIR ? 1 : -1);
    }

    private static int withDistanceAlpha(int argb, double factor) {
        int alpha = (int) Math.round((argb >>> 24) * factor);
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }
}
