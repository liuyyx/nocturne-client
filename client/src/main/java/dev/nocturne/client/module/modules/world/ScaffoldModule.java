package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.event.PlayerTickEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.inventory.InventorySupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.Locale;

/** Epsilon Scaffold：六主模式、真实支撑面放置、换槽/输入/旋转与放置淡出。 */
public final class ScaffoldModule extends GameModule implements WorldOverlay {
    private static final float EYE_HEIGHT = 1.62f;
    /** 放置失败后的冷却：失败也必须有间隔，否则每个 tick 都会重发一次 C08。 */
    private static final long FAIL_COOLDOWN_MS = 50L;
    /** 放置后等待服务端确认再验证目标格（2 tick 量级）。 */
    private static final long VERIFY_DELAY_MS = 100L;
    private static final long ROTATION_TTL_NANOS = 200_000_000L;
    private static final int ROTATION_PRIORITY = 20;

    private final TextValue blacklist = add(new TextValue("Blacklisted Blocks", "chest,ender_chest,shulker,barrel,furnace,anvil,enchanting_table,crafting_table,beacon,bed,button,pressure_plate,door,trapdoor,fence_gate,note_block,tnt,slime_block,pumpkin,obsidian", 2048));
    private final BooleanValue toggleOnTeleport = add(new BooleanValue("Toggle On Teleport", false));
    private final ModeValue mode = mode("Mode", "TellyBridge", "TellyBridge", "GodBridge", "Heypixel", "Hypixel", "Elowen", "RavenB4");
    private final ModeValue swapMode = mode("Swap Mode", "Normal", "None", "Normal", "Silent", "InvSwitch");
    private final BooleanValue swapBack = bool("Swap Back", true);
    private final BooleanValue snap = bool("Snap", false);
    private final BooleanValue fortyFive = bool("45 Degrees", false);
    private final ModeValue rotationMode = mode("Rotation Mode", "Static", "Static", "Hypixel", "Heypixel");
    private final ModeValue raytraceMode = mode("Raytrace Mode", "Normal", "Normal", "Strict");
    private final NumberValue rotationSpeed = number("Rotation Speed", 127, 10, 180, 1);
    private final NumberValue rotationSpeed2 = number("Rotation Speed 2", 36, 10, 180, 1);
    private final NumberValue rotationBackSpeed = number("Rotation Back Speed", 180, 10, 180, 1);
    private final NumberValue tellyTicks = number("Telly Ticks", 1, 0, 6, 1);
    private final NumberValue placeDelay = number("Place Delay", 1, 0, 5, 1);
    private final NumberValue placeDelayRandom = number("Place Delay Random", 2, 0, 5, 1);
    private final BooleanValue emergency = bool("Emergency Placement", true);
    private final ModeValue godVariant = mode("God Bridge Variant", "Classic", "Classic", "Polar");
    private final BooleanValue godJump = bool("Jump", true);
    private final BooleanValue godSneak = bool("Sneak", false);
    private final BooleanValue godStopInput = bool("Stop Input", false);
    private final BooleanValue godStepBack = bool("Step Back", false);
    private final NumberValue forceSneak = number("Force Sneak Below Count", 5, 0, 64, 1);
    private final NumberValue sneakMin = number("Sneak Time Min", 1, 0, 20, 1);
    private final NumberValue sneakMax = number("Sneak Time Max", 1, 0, 20, 1);
    private final ModeValue rotationTiming = mode("Rotation Timing", "Normal", "Normal", "OnTick", "OnTickSnap");
    private final BooleanValue considerInventory = bool("Consider Inventory", false);
    private final NumberValue delayMin = number("Delay Min", 1, 0, 40, 1);
    private final NumberValue delayMax = number("Delay Max", 1, 0, 40, 1);
    private final NumberValue minDist = number("Min Dist", 0, 0, 0.25, 0.01);
    private final BooleanValue ledge = bool("Ledge", true);
    private final BooleanValue sneakBridging = bool("Sneak While Bridging", true);
    private final BooleanValue debugLog = bool("Debug Log", true);
    private final ModeValue swingMode = mode("Swing", "HideForClient", "DoNotHide", "HideForBoth", "HideForClient", "HideForServer");
    private final BooleanValue simulateAttempts = bool("Simulate Placement Attempts", true);
    private final NumberValue clickCpsMin = number("Click CPS Min", 11, 1, 20, 1);
    private final NumberValue clickCpsMax = number("Click CPS Max", 12, 1, 20, 1);
    private final ModeValue clickTechnique = mode("Click Technique", "STABILIZED", "STABILIZED", "NORMAL", "BUTTERFLY", "DOUBLE", "DRAG");
    private final BooleanValue failedOnly = bool("Failed Attempts Only", false);
    private final ModeValue angleSmooth = mode("Angle Smooth", "Sigmoid", "Linear", "Sigmoid");
    private final NumberValue horizontalMin = number("Horizontal Turn Speed Min", 19.8, 1, 180, 0.1);
    private final NumberValue horizontalMax = number("Horizontal Turn Speed Max", 40.5, 1, 180, 0.1);
    private final NumberValue verticalMin = number("Vertical Turn Speed Min", 8.1, 1, 90, 0.1);
    private final NumberValue verticalMax = number("Vertical Turn Speed Max", 30.6, 1, 90, 0.1);
    private final NumberValue steepness = number("Steepness", 10, 1, 30, 0.1);
    private final NumberValue midpoint = number("Midpoint", 0.3, 0, 1, 0.05);
    private final NumberValue resetThreshold = number("Reset Threshold", 1, 0, 20, 0.1);
    private final NumberValue ticksReset = number("Ticks Until Reset", 20, 1, 100, 1);
    private final ModeValue movementCorrection = mode("Movement Correction", "SILENT", "OFF", "SILENT", "STRICT");
    private final BooleanValue eagleEnabled = bool("Eagle Enabled", false);
    private final NumberValue eagleMin = number("Blocks To Eagle Min", 0, 0, 20, 1);
    private final NumberValue eagleMax = number("Blocks To Eagle Max", 0, 0, 20, 1);
    private final NumberValue edgeMin = number("Edge Distance Min", 0.01, 0, 0.5, 0.01);
    private final NumberValue edgeMax = number("Edge Distance Max", 0.05, 0, 0.5, 0.01);
    private final BooleanValue eagleGround = bool("Only On Ground", true);
    private final BooleanValue uitemsTelly = bool("Uitems Telly", true);
    private final NumberValue uitemsTicks = number("Uitems Telly Ticks", 5, 0, 10, 1);
    private final NumberValue uitemsRotation = number("Uitems Rotation Speed", 180, 10, 180, 1);
    private final NumberValue uitemsBack = number("Uitems Rotation Back Speed", 180, 10, 180, 1);
    private final BooleanValue duplicateRot = bool("Duplicate Rot Place", false);
    private final BooleanValue overrideRaycast = bool("Override raycast", true);
    private final BooleanValue interactBefore = bool("Interact before place", false);
    private final BooleanValue uitemsSafeWalk = bool("SafeWalk", true);
    private final ModeValue selfRescue = mode("Self rescue mode", "Disabled", "Disabled", "Normal", "Force");
    private final ModeValue elowenMode = mode("Elowen Mode", "Normal", "Normal", "TellyBridge", "KeepY", "Snap");
    private final ModeValue elowenRotation = mode("Elowen Rotation Mode", "None", "None", "Linear", "Curve", "Aca", "Heypixel");
    private final BooleanValue elowenEagle = bool("Elowen Eagle", false);
    private final BooleanValue elowenSneak = bool("Elowen Sneak", true);
    private final BooleanValue elowenJumpSprint = bool("Elowen Jump Sprint", false);
    private final NumberValue elowenSpeed = number("Elowen Rotation Speed", 50, 30, 180, 1);
    private final NumberValue elowenBack = number("Elowen Back Rotation Speed", 180, 10, 180, 1);
    private final NumberValue elowenDelay = number("Elowen Place Delay", 0, 0, 2, 1);
    private final BooleanValue elowenSwing = bool("Elowen Swing", false);
    private final BooleanValue elowenPacketN = bool("Elowen Packet Order N", false);
    private final BooleanValue elowenCount = bool("Elowen Block Count", true);
    private final NumberValue elowenHudY = number("Elowen HUD Y", -50, -500, 500, 1);
    private final BooleanValue elowenSideway = bool("Elowen Sideway Rotation", false);
    private final ModeValue ravenRotation = mode("Raven Rotation Mode", "Strict", "None", "Strict", "Smooth", "Simple", "Lazy", "Offset");
    private final ModeValue ravenFast = mode("Raven Fast Scaffold", "Disabled", "Disabled", "Normal", "Strict");
    private final ModeValue ravenFastRmb = mode("Raven Fast Scaffold RMB", "Disabled", "Disabled", "Normal", "Strict");
    private final ModeValue ravenMulti = mode("Raven Multi-place", "Disabled", "Disabled", "Normal", "Strict");
    private final ModeValue ravenPrecision = mode("Raven Precision", "High", "Low", "Normal", "High");
    private final NumberValue ravenMotion = number("Raven Motion", 0.95, 0, 1, 0.01);
    private final NumberValue ravenFastMotion = number("Raven Fast Scaffold Motion", 0.95, 0, 1, 0.01);
    private final NumberValue ravenSneakEvery = number("Raven Sneak Every", 0, 0, 20, 1);
    private final NumberValue ravenBoost = number("Raven Speed Boost", 0, 0, 2, 0.05);
    private final BooleanValue ravenLegit = bool("Raven Legit Motion", false);
    private final BooleanValue ravenSafe = bool("Raven Safe Walk", false);
    private final BooleanValue ravenForward = bool("Raven Jump Facing Forward", false);
    private final BooleanValue ravenAutoSwap = bool("Raven Auto Swap", true);
    private final BooleanValue ravenSilentSwing = bool("Raven Silent Swing", false);
    private final BooleanValue ravenSlowEnable = bool("Raven Slow On Enable", false);
    private final BooleanValue ravenSlowPotion = bool("Raven Slow Speed Potion", false);
    private final BooleanValue ravenHighlight = bool("Raven Highlight Blocks", false);
    private final BooleanValue ravenTower = bool("Raven Tower", false);
    private final BooleanValue ravenMovementFix = bool("Raven Movement Fix", true);
    private final BooleanValue swingHand = bool("Swing Hand", true);
    private final BooleanValue render = bool("Render", true);
    private final BooleanValue fade = bool("Fade", true);
    private final NumberValue fadeTime = number("Fade Time", 500, 0, 3000, 50);
    private final BooleanValue shrink = bool("Shrink", false);
    private final ColorValue sideColor = color("Side Color", 0x64FFB7C5);
    private final ColorValue lineColor = color("Line Color", 0xFFFF69B4);

    /** 换槽状态（原槽 + 是否真的换过），与 -1 哨兵解耦。 */
    private final SlotSwap slotSwap = new SlotSwap();
    /** 成功放置后延迟到下一 tick 才换回，避免同一 tick 内手持物来回跳。 */
    private boolean pendingRestore;
    /** 是否处于我们写回视角的状态。 */
    private boolean rotating;
    /** 未被旋转改写的基准 yaw；target 只读它，写回 yaw 不会反向影响它。 */
    private float baseYaw;
    /** 上一次真正写到客户端/控制器的 yaw，用于平滑进度与反馈扣除。 */
    private float appliedYaw;
    private float restorePitch;
    private int placedTicks;
    private int sneakTicks;
    private long nextPlace;
    private int[] lastPlaced;
    private int[] pendingVerify;
    private long verifyAt;
    private long placedAt;
    private boolean inputJump;
    /** 计时倍率写回失败只提示一次（该版本没有可写字段）。 */
    private boolean timerWarned;

    public ScaffoldModule() {
        super("Scaffold", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, this::correctMovementInput);
        // 放置挂在真实玩家 tick 上：帧驱动 20Hz 只是近似节拍，帧率变化会直接改变放置频率。
        listen(PlayerTickEvent.Pre.class, event -> onPlayerTick());
    }

    /** 帧驱动只保留覆盖层维护，放置已移到 {@link PlayerTickEvent.Pre}。 */
    @Override
    public void onTick() {
        if (lastPlaced != null && fade.get()
                && System.currentTimeMillis() - placedAt > Math.max(1.0f, fadeTime.asFloat())) {
            lastPlaced = null;
        }
    }

    private void onPlayerTick() {
        ActionApi actions = actions();
        GameBridge bridge = bridge();
        // 每 tick 只取一次快照，后续全部复用，避免热路径反复反射。
        PlayerState player = state();
        if (actions == null || bridge == null || !player.present()) {
            restore();
            return;
        }
        long now = System.currentTimeMillis();
        if (bridge.currentScreen() != null || player.health() <= 0.0f) {
            // 开界面/死亡时继续发 C08 只会被拒或误触，先恢复槽位与视角。
            stopBridging(true);
            return;
        }
        if (pendingVerify != null && now >= verifyAt) {
            Object placed = bridge.blockStateAt(pendingVerify[0], pendingVerify[1], pendingVerify[2]);
            if (placed != null && !bridge.canBeReplaced(placed)) {
                lastPlaced = pendingVerify;
                placedAt = now;
                placedTicks++;
            }
            pendingVerify = null;
        }
        if (pendingRestore) {
            restore();
        }
        if (now < nextPlace) {
            return;
        }
        int slot = blockSlot(player);
        if (slot < 0) {
            restore();
            return;
        }
        try {
            if (!selectSlot(actions, player, slot)) {
                return;
            }
            float yaw = baseYawFor(player);
            double[] target = target(yaw, player);
            if (target == null) {
                return;
            }
            int x = (int) Math.floor(target[0]);
            int y = (int) Math.floor(target[1]);
            int z = (int) Math.floor(target[2]);
            float[] rotation = aim(x, y, z, yaw, player);
            // 控制器只写客户端字段；不显式发包，服务端看到的仍是旧朝向，于是 C08 先于 C03。
            actions.sendRotation(rotation[0], rotation[1], player.onGround());
            if (interactBefore.get() && (mode.is("Heypixel") || mode.is("Hypixel"))) {
                actions.useItem();
            }
            boolean placed = PlacementSupport.placeNear(bridge, actions, x, y, z);
            if (!placed && emergency.get()) {
                int below = (int) Math.floor(player.y()) - 1;
                placed = PlacementSupport.placeNear(bridge, actions,
                        (int) Math.floor(player.x()), below, (int) Math.floor(player.z()));
            }
            if (!placed) {
                nextPlace = now + FAIL_COOLDOWN_MS;
                if (simulateAttempts.get() && debugLog.get()) {
                    System.out.println("[nocturne] Scaffold placement attempt failed");
                }
                return;
            }
            // 放置结果先记成待验证：只有下一 tick 目标格真的变成实体方块才计入成功。
            pendingVerify = new int[]{x, y, z};
            verifyAt = now + VERIFY_DELAY_MS;
            if (swingHand.get() && !mode.is("Elowen") || elowenSwing.get()) {
                actions.swing(false, swingMode.is("HideForClient") || swingMode.is("HideForBoth")
                        || mode.is("RavenB4") && ravenSilentSwing.get());
            }
            pendingRestore = swapBack.get();
            int base = mode.is("Elowen") ? elowenDelay.asInt() : placeDelay.asInt();
            int random = mode.is("GodBridge") ? EpsilonSupport.randomInt(delayMin.asInt(), delayMax.asInt())
                    : EpsilonSupport.randomInt(0, placeDelayRandom.asInt());
            nextPlace = now + (base + random) * 50L;
            if (mode.is("GodBridge") && placedTicks >= forceSneak.asInt()) {
                sneakTicks = EpsilonSupport.randomInt(sneakMin.asInt(), sneakMax.asInt());
                placedTicks = 0;
            }
        } finally {
            // 任何提前 return 都不能把玩家留在方块槽；成功路径留给下一 tick 才换回。
            if (!pendingRestore && slotSwap.swapped()) {
                restore();
            }
        }
    }

    /** 按基准 yaw 补偿 WASD 输入，再交给各模式自己的输入改写。 */
    private void correctMovementInput(MovementInputEvent event) {
        inputJump = event.jump;
        if (rotating) {
            // 视角已经被我们转开：把 WASD 输入反向补偿，否则按 W 会朝身后走。
            float[] corrected = rotateInput(event.forward, event.strafe,
                    RotationController.wrap(appliedYaw - baseYaw));
            event.forward = corrected[0];
            event.strafe = corrected[1];
        }
        if (mode.is("GodBridge")) {
            if (godStopInput.get()) {
                event.forward = 0.0f;
                event.strafe = 0.0f;
            }
            if (godStepBack.get()) {
                event.forward = -1.0f;
            }
            if (godJump.get() && event.forward > 0.0f) {
                event.jump = true;
            }
            if (godSneak.get() || sneakBridging.get() && sneakTicks > 0) {
                event.sneak = true;
                sneakTicks--;
            }
        }
        if (mode.is("Elowen")) {
            event.sneak |= elowenSneak.get() && edgeNear();
            event.jump |= elowenJumpSprint.get();
            event.sprint |= elowenJumpSprint.get();
        }
        if ((mode.is("Heypixel") || mode.is("Hypixel")) && uitemsSafeWalk.get()) {
            event.sneak |= edgeNear();
        }
        if (mode.is("RavenB4") && ravenSafe.get()) {
            event.sneak |= edgeNear();
        }
    }

    /**
     * 把输入按"视角已被旋转 {@code deltaDegrees}"补偿回用户意图。
     *
     * <p>原版用 {@code (strafe·cosθ - forward·sinθ, forward·cosθ + strafe·sinθ)} 把输入旋到世界空间；
     * 要让世界向量在旋转后的 θ+Δ 下保持不变，输入必须反向旋转 Δ。
     */
    static float[] rotateInput(float forward, float strafe, float deltaDegrees) {
        double delta = Math.toRadians(deltaDegrees);
        float cos = (float) Math.cos(delta);
        float sin = (float) Math.sin(delta);
        return new float[]{forward * cos - strafe * sin, forward * sin + strafe * cos};
    }

    /**
     * 取"没有被自己旋转改写"的玩家朝向。
     *
     * <p>只把上一次写回的旋转扣掉：{@code 当前 yaw - appliedYaw} 就是玩家这段时间自己转过的角度，
     * 照常计入基准。这样 target 不会读到被 aim 改写后的 yaw，再叠加 +180 形成绕圈。
     */
    private float baseYawFor(PlayerState player) {
        float current = player.yaw();
        if (rotating) {
            baseYaw = RotationController.wrap(baseYaw + RotationController.wrap(current - appliedYaw));
        } else {
            baseYaw = current;
            appliedYaw = current;
            restorePitch = player.pitch();
        }
        return baseYaw;
    }

    private double[] target(float baseYawDegrees, PlayerState player) {
        double distance = mode.is("TellyBridge") || mode.is("Elowen") && elowenMode.is("TellyBridge") ? 1.2 : 1.0;
        if (mode.is("RavenB4") && ravenTower.get() && inputJump) {
            return new double[]{player.x(), player.y(), player.z()};
        }
        double[] point = targetPoint(baseYawDegrees, player.x(), player.y(), player.z(), distance,
                mode.is("Elowen") && elowenSideway.get(),
                mode.is("Elowen") && elowenMode.is("KeepY"));
        if (Math.abs(point[0] - Math.floor(point[0]) - 0.5) < minDist.get()
                || Math.abs(point[2] - Math.floor(point[2]) - 0.5) < minDist.get()) {
            return null;
        }
        return point;
    }

    /** 目标点只由未旋转的基准 yaw 决定，写回视角不会改变它。 */
    static double[] targetPoint(float baseYaw, double x, double y, double z, double distance,
                                boolean sideways, boolean keepY) {
        double yaw = Math.toRadians(baseYaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        if (sideways) {
            double old = forwardX;
            forwardX = forwardZ;
            forwardZ = -old;
        }
        return new double[]{x + forwardX * distance,
                keepY ? Math.floor(y) - 1.0 : y - 1.0,
                z + forwardZ * distance};
    }

    /** 请求朝向并返回真正写入的 {yaw, pitch}；平滑从上次写回的角度推进，不从反馈读数的 yaw 推进。 */
    private float[] aim(int x, int y, int z, float baseYawDegrees, PlayerState player) {
        float targetYaw = RotationController.wrap(baseYawDegrees + 180.0f);
        if (snap.get() || fortyFive.get() || mode.is("Elowen") && elowenMode.is("Snap")) {
            targetYaw = Math.round(targetYaw / 45.0f) * 45.0f;
        }
        if (mode.is("RavenB4") && ravenForward.get() && inputJump) {
            targetYaw = baseYawDegrees;
        }
        double dx = x + 0.5 - player.x();
        double dz = z + 0.5 - player.z();
        float pitch = RotationController.pitchTo(Math.hypot(dx, dz),
                y + 1.0 - player.y() - EYE_HEIGHT);
        float speed = rotationSpeed.asFloat();
        if (mode.is("Elowen")) {
            speed = elowenSpeed.asFloat();
        } else if (mode.is("Heypixel") || mode.is("Hypixel")) {
            speed = uitemsRotation.asFloat();
        } else if (mode.is("GodBridge")) {
            float t = (float) Math.min(1.0, Math.abs(RotationController.wrap(
                    targetYaw - appliedYaw)) / 180.0);
            float sigmoid = (float) (1.0 / (1.0 + Math.exp(-steepness.get() * (t - midpoint.get()))));
            speed = angleSmooth.is("Linear") ? horizontalMin.asFloat()
                    : horizontalMin.asFloat() + (float) ((horizontalMax.get() - horizontalMin.get()) * sigmoid);
        }
        if (rotationTiming.is("OnTickSnap")) {
            speed = 180.0f;
        }
        float yaw = RotationController.approach(appliedYaw, targetYaw, speed);
        client().rotations().request(yaw, pitch, ROTATION_PRIORITY, ROTATION_TTL_NANOS);
        // 更高优先级的请求会覆盖我们：以控制器实际持有的角度为准，下一 tick 的扣除才不会漂移。
        appliedYaw = client().rotations().yaw(yaw);
        rotating = true;
        return new float[]{appliedYaw, pitch};
    }

    /** 快捷栏里的方块槽；黑名单全命中时返回 -1，绝不退回"任意方块槽"。 */
    private int blockSlot(PlayerState player) {
        if (swapMode.is("None")) {
            return player.selectedSlot();
        }
        for (int i = 0; i < 9; i++) {
            Object stack = bridge().hotbarStack(i);
            Object item = bridge().callMapped(stack, ClassType.ITEM_STACK, "getItem");
            if (!bridge().isInstance(item, ClassType.BLOCK_ITEM)) {
                continue;
            }
            if (blacklisted(blacklist.get(), bridge().itemDescription(stack))) {
                continue;
            }
            return i;
        }
        if (swapMode.is("InvSwitch") || considerInventory.get()) {
            int inventory = EpsilonSupport.findInventory(bridge(), "block");
            if (inventory >= 9) {
                Object stack = bridge().inventoryStack(inventory);
                if (!blacklisted(blacklist.get(), bridge().itemDescription(stack))) {
                    actions().clickSlot(InventorySupport.menuSlot(inventory), 8, "swap");
                    return 8;
                }
            }
        }
        return -1;
    }

    /**
     * 黑名单按"方块 id 的连续词元"匹配。
     *
     * <p>{@code contains("bed")} 会把 {@code bedrock} 一起拉黑；反过来 {@code oak_door} 又必须被
     * {@code door} 命中。因此把 id 与词条都按 {@code _} 切词，再做连续子序列匹配。
     * 读不到 id 时按黑名单处理（无法确认就不放）。
     */
    static boolean blacklisted(String config, String id) {
        if (config == null || id == null || id.isEmpty()) {
            return true;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("block.minecraft.")) {
            normalized = normalized.substring("block.minecraft.".length());
        } else if (normalized.startsWith("item.minecraft.")) {
            normalized = normalized.substring("item.minecraft.".length());
        }
        String[] parts = normalized.split("_");
        for (String raw : config.toLowerCase(Locale.ROOT).split("[,\\s;/]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            if (containsTokenRun(parts, raw.split("_"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTokenRun(String[] parts, String[] token) {
        if (token.length == 0 || token.length > parts.length) {
            return false;
        }
        outer:
        for (int i = 0; i + token.length <= parts.length; i++) {
            for (int j = 0; j < token.length; j++) {
                if (!parts[i + j].equals(token[j])) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 选中方块槽并记录原槽。
     *
     * <p>Silent 只发换槽包、客户端手持物不变，放置会用错物品：这里同时做本地切换，
     * 放置结束（或失败）再换回。读不到原槽时放弃换槽，绝不把玩家留在无法恢复的槽位。
     */
    private boolean selectSlot(ActionApi actions, PlayerState player, int slot) {
        if (swapMode.is("None")) {
            return true;
        }
        if (!slotSwap.begin(player.selectedSlot(), slot)) {
            // 读不到原槽就放弃换槽：换出去就再也换不回来。
            return false;
        }
        if (!slotSwap.swapped()) {
            return true;
        }
        return applySwap(actions, swapMode.is("Silent"), slot);
    }

    /**
     * 换槽只发一种动作。
     *
     * <p>Silent 只发 silent 包（服务端换槽、客户端手持物不动）；其余做本地切换。旧实现先发
     * silent 再发本地，每 tick 两个 ServerboundSetCarriedItem，Silent 与 Normal 没有区别。
     */
    static boolean applySwap(ActionApi actions, boolean silent, int slot) {
        return actions.selectSlot(slot, silent);
    }

    private boolean edgeNear() {
        Object box = bridge().callMapped(bridge().player(), ClassType.ENTITY, "getBoundingBox");
        return bridge().noCollision(bridge().player(), bridge().moveBox(box, 0, -0.5, 0));
    }

    /** 换回进入搭路前的槽位；状态机保证只恢复一次，且原槽 0 不会被当成"没有原槽"。 */
    private void restore() {
        pendingRestore = false;
        ActionApi actions = actions();
        if (actions == null) {
            // 动作层不可用时保留记录：丢状态等于把玩家留在方块槽里。
            return;
        }
        int original = slotSwap.takeRestore();
        if (original < 0) {
            return;
        }
        applySwap(actions, swapMode.is("Silent"), original);
    }

    /** 停止搭路的全部副作用：换回槽位、写回视角、清空待验证、恢复计时器。 */
    private void stopBridging(boolean writeBack) {
        if (rotating) {
            if (client() != null && client().rotations() != null) {
                client().rotations().release(ROTATION_PRIORITY);
            }
            ActionApi actions = actions();
            if (writeBack && actions != null) {
                // 只 release 会把视角永久停在反向：显式写回进入旋转前的朝向。
                actions.setRotation(RotationController.wrap(baseYaw), restorePitch);
            }
            rotating = false;
        }
        restore();
        pendingVerify = null;
        GameBridge bridge = bridge();
        if (bridge != null && !bridge.setTimerSpeed(1.0f) && !timerWarned) {
            timerWarned = true;
            System.out.println("[nocturne] Scaffold: 该版本没有可写的计时倍率字段，"
                    + "无法把速度还原为 1.0（若其它模块改过速度请手动关闭）");
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!render.get() && !(mode.is("RavenB4") && ravenHighlight.get()) || lastPlaced == null) {
            return;
        }
        float remaining = fade.get() ? Math.max(0.0f, 1.0f - (System.currentTimeMillis() - placedAt)
                / Math.max(1.0f, fadeTime.asFloat())) : 1.0f;
        if (remaining <= 0.0f) {
            lastPlaced = null;
            return;
        }
        double inset = shrink.get() ? (1.0 - remaining) * 0.5 : 0.0;
        WorldGeometry.box(draw, projection, lastPlaced[0] + inset, lastPlaced[1] + inset,
                lastPlaced[2] + inset, lastPlaced[0] + 1.0 - inset, lastPlaced[1] + 1.0 - inset,
                lastPlaced[2] + 1.0 - inset, 1.0f, alpha(lineColor.argb(), remaining));
        if ((sideColor.argb() >>> 24) > 0) {
            WorldGeometry.blockFilled(draw, projection, lastPlaced[0], lastPlaced[1], lastPlaced[2],
                    1.0f, alpha(sideColor.argb(), remaining), alpha(lineColor.argb(), remaining));
        }
    }

    private static int alpha(int color, float factor) {
        return color & 0xFFFFFF | (int) ((color >>> 24) * factor) << 24;
    }

    @Override
    protected void onStop() {
        stopBridging(true);
        // 关闭时动作层可能已不可用（换不回去）：只能丢弃记录，避免下次启用时恢复过期槽位。
        slotSwap.clear();
        rotating = false;
        pendingRestore = false;
        pendingVerify = null;
        lastPlaced = null;
    }

    /**
     * 换槽状态机。
     *
     * <p>"是否换过槽"必须是独立布尔：旧实现用 {@code original == -1} 同时表示"没有原槽"，
     * 于是原槽恰好是 0 或恢复路径被提前 return 跳过时，玩家会被卡在方块槽里。
     */
    static final class SlotSwap {
        private int original = -1;
        private boolean swapped;

        /** 记录进入前的槽位；读不到原槽（<0）返回 false，调用方必须放弃换槽。 */
        boolean begin(int originalSlot, int targetSlot) {
            if (swapped) {
                return true;
            }
            if (originalSlot < 0) {
                return false;
            }
            original = originalSlot;
            swapped = originalSlot != targetSlot;
            return true;
        }

        boolean swapped() {
            return swapped;
        }

        /** 取出待恢复的槽位并复位；没有需要恢复的槽时返回 -1（-1 只表示"无"）。 */
        int takeRestore() {
            int slot = swapped ? original : -1;
            clear();
            return slot;
        }

        /** 丢弃记录（动作层不可用、无法换回时使用）。 */
        void clear() {
            swapped = false;
            original = -1;
        }
    }

    private BooleanValue bool(String name, boolean value) {
        return add(new BooleanValue(name, value));
    }

    private NumberValue number(String name, double value, double min, double max, double step) {
        return add(new NumberValue(name, value, min, max, step));
    }

    private ModeValue mode(String name, String value, String... options) {
        return add(new ModeValue(name, value, options));
    }

    private ColorValue color(String name, int value) {
        return add(new ColorValue(name, value));
    }
}
