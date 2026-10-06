package gg.vape.module.utility;

import gg.vape.Vape;
import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventPreTick;
import gg.vape.module.UtilityMod;
import gg.vape.module.control.SharedModuleControlClaims;
import gg.vape.rotation.AdaptiveRotationController;
import gg.vape.rotation.FixedRotationController;
import gg.vape.rotation.RotationControlClaim;
import gg.vape.rotation.RotationManager;
import gg.vape.unmap.ModeOption;
import gg.vape.value.BooleanValue;
import gg.vape.value.ModeValue;
import gg.vape.value.NumberValue;
import gg.vape.wrapper.impl.EntityPlayerSP;
import gg.vape.wrapper.impl.InventoryPlayer;
import gg.vape.wrapper.impl.Item;
import gg.vape.wrapper.impl.ItemStack;
import gg.vape.wrapper.impl.KeyBinding;
import gg.vape.wrapper.impl.Minecraft;

/**
 * Throws an ender pearl and then intersects it with a wind charge.
 *
 * <p>The trajectory constants and search ranges mirror the 4.21 beta module:
 * pearl drag is 0.99, pearl gravity is 0.03 and both projectiles start at
 * 1.5 blocks per tick.</p>
 */
public class PearlCatch extends UtilityMod {
    private static final double PROJECTILE_SPEED = 1.5;
    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_GRAVITY = 0.03;
    // 拦截语义：风弹必须碰撞珍珠实体（WindChargeProjectile 撞实体才爆炸，爆炸
    // 半径 3 格波及珍珠）。预测 miss 距离 3 格是"擦边球"——风弹飞近但碰不到，
    // 玩家稍有移动就落空；收紧到 1.5 格（平方 2.25）只接受"路径确实相交/极近"，
    // 让 solveChargeDirection 不再误以为 3 格外也算命中。
    private static final double MAX_INTERCEPT_DISTANCE_SQUARED = 2.25;
    // 珍珠上抛后落回玩家高度约需 100+ tick；Current aim 需要等珍珠
    // 落回与水平风弹相交，模拟窗口必须覆盖整条珍珠弹道。
    private static final int MAX_PEARL_TICKS = 100;
    private static final int MAX_AIM_TICKS = 120;

    private final ModeOption upwardMode = new ModeOption("Upward");
    private final ModeOption currentAimMode = new ModeOption("Current aim");
    private final ModeValue aimMode = ModeValue.create(
            this,
            "Aim mode",
            "Upward - throws the pearl straight up\nCurrent aim - throws the wind charge where you were looking",
            this.upwardMode,
            this.upwardMode,
            this.currentAimMode);
    private final NumberValue aimSpeed = NumberValue.create(
            this, "Aim speed", "#.#", "", 1.0, 10.0, 10.0);
    private final BooleanValue silentAim = BooleanValue.create(this, "Silent aim", true, null);
    private final NumberValue chargeDelay = NumberValue.create(
            this,
            "Charge delay",
            "#",
            " ticks",
            0.0,
            0.0,
            10.0,
            1.0,
            "Ticks to wait after the pearl before throwing the wind charge");

    private final RotationControlClaim rotationClaim = SharedModuleControlClaims.rotation;

    private State state = State.IDLE;
    private FixedRotationController rotationController;
    private InterceptPlan plan;
    private int pearlSlot = -1;
    private int chargeSlot = -1;
    private int savedSlot = -1;
    private int stateTicks;
    private int ticksSincePearl;

    // 珍珠飞行模拟状态：抛珍珠那一 tick 记录初始位置/速度，之后每 tick
    // 按 MC ThrowableEntity 物理推进。风弹方向每 tick 基于珍珠实时状态
    // 重新解算，玩家移动导致的轨迹偏差被持续修正。
    private boolean pearlSimActive;
    private double pearlX;
    private double pearlY;
    private double pearlZ;
    private double pearlMotionX;
    private double pearlMotionY;
    private double pearlMotionZ;

    public PearlCatch() {
        super("PearlCatch", "Throws a pearl, then throws a wind charge to catch it");
        this.addValue(this.aimMode, this.aimSpeed, this.silentAim, this.chargeDelay);
        this.rotationClaim.setPriority(this, 7);
        Vape.debugLog("[PearlCatch] constructed");
    }

    /**
     * 允许 GUI 点击直接启用（默认 UtilityMod 要求绑定按键才能触发，
     * 导致 GUI 点击被拦截、onEnable 永不执行）。
     */
    @Override
    public boolean isRequiresBind() {
        return false;
    }

    @Override
    public void onEnable() {
        Vape.debugLog("[PearlCatch] onEnable entered");
        try {
            this.enableInternal();
        } catch (Throwable t) {
            Vape.debugLog("[PearlCatch] onEnable threw " + t.getClass().getName() + ": " + t.getMessage());
            this.abort();
        }
    }

    private void enableInternal() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNull() || Minecraft.theWorld().isNull()) {
            Vape.debugLog("[PearlCatch] abort: player/world null");
            this.abort();
            return;
        }

        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        this.pearlSlot = this.findHotbarSlot(inventory, "minecraft:ender_pearl", "Ender Pearl");
        this.chargeSlot = this.findHotbarSlot(inventory, "minecraft:wind_charge", "Wind Charge", "Windcharge");
        Vape.debugLog("[PearlCatch] enable pearlSlot=" + this.pearlSlot + " chargeSlot=" + this.chargeSlot
                + " aimMode=" + this.aimMode.getValue());
        if (this.pearlSlot == -1 || this.chargeSlot == -1) {
            Vape.debugLog("[PearlCatch] abort: item not found");
            this.abort();
            return;
        }

        this.savedSlot = inventory.v();
        int configuredDelay = this.chargeDelay.getValue().intValue();
        this.plan = this.currentAimMode.isSelected()
                ? this.findCurrentAimPlan(player, Math.max(2, configuredDelay))
                : this.findUpwardPlan(player, configuredDelay);
        if (this.plan == null && this.currentAimMode.isSelected()) {
            // Current aim 静态计划无解（玩家移动中视角方向刁钻）时退化为
            // Upward：珍珠垂直上抛，风弹仍由动态拦截修正。
            this.plan = this.findUpwardPlan(player, configuredDelay);
        }
        if (this.plan == null) {
            Vape.debugLog("[PearlCatch] abort: no intercept plan");
            this.abort();
            return;
        }
        Vape.debugLog("[PearlCatch] plan pearlYaw=" + this.plan.pearlYaw + " pearlPitch=" + this.plan.pearlPitch
                + " chargeYaw=" + this.plan.chargeYaw + " chargePitch=" + this.plan.chargePitch
                + " chargeTick=" + this.plan.chargeTick + " miss=" + this.plan.missDistanceSquared);

        this.state = State.AIMING_PEARL;
        this.stateTicks = 0;
        this.ticksSincePearl = 0;
        this.setRotation(this.plan.pearlYaw, this.plan.pearlPitch);
    }

    @Override
    public void onDisable() {
        this.releaseRotation();
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNotNull() && this.savedSlot != -1) {
            player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.savedSlot);
        }
        this.state = State.IDLE;
        this.rotationController = null;
        this.plan = null;
        this.pearlSlot = -1;
        this.chargeSlot = -1;
        this.savedSlot = -1;
        this.stateTicks = 0;
        this.ticksSincePearl = 0;
        this.pearlSimActive = false;
        this.pearlX = 0.0;
        this.pearlY = 0.0;
        this.pearlZ = 0.0;
        this.pearlMotionX = 0.0;
        this.pearlMotionY = 0.0;
        this.pearlMotionZ = 0.0;
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        EntityPlayerSP player = event.getThePlayer();
        if (player.isNull() || Minecraft.theWorld().isNull() || this.plan == null) {
            this.abort();
            return;
        }

        ++this.stateTicks;
        if (this.state == State.AIMING_CHARGE || this.state == State.WAITING_FOR_CHARGE) {
            ++this.ticksSincePearl;
        }
        if (this.stateTicks > MAX_AIM_TICKS && this.state != State.WAITING_FOR_CHARGE) {
            Vape.debugLog("[PearlCatch] abort: aim timeout state=" + this.state);
            this.abort();
            return;
        }

        switch (this.state) {
            case AIMING_PEARL:
                if (!this.hasRotationControl()) {
                    return;
                }
                if (this.isRotationReady()) {
                    Vape.debugLog("[PearlCatch] pearl rotation ready ticks=" + this.stateTicks);
                    this.state = State.THROWING_PEARL;
                }
                break;
            case THROWING_PEARL:
                Vape.debugLog("[PearlCatch] throw pearl slot=" + this.pearlSlot);
                this.throwFromSlot(this.pearlSlot);
                // 记录珍珠初始状态（抛那一 tick 的玩家位置/速度）——
                // 珍珠自此独立飞行，模拟与实际一致。
                this.pearlX = player.z();
                this.pearlY = player.N() + player.Y() - 0.1;
                this.pearlZ = player.h();
                Vec pearlDir = direction(this.plan.pearlYaw, this.plan.pearlPitch);
                // MC 1.21 ThrowableProjectile 发射语义（EnderPearlItem.use →
                // shootFromRotation → shoot）：初速 = 朝向向量 × 1.5，纯方向，
                // 不含玩家速度；发射位置 = 玩家眼睛 - 0.1。玩家速度只影响发射
                // 时刻的位置（生成在服务端玩家当前位置），不进入初速。
                this.pearlMotionX = pearlDir.x * PROJECTILE_SPEED;
                this.pearlMotionY = pearlDir.y * PROJECTILE_SPEED;
                this.pearlMotionZ = pearlDir.z * PROJECTILE_SPEED;
                this.pearlSimActive = true;
                this.state = State.AIMING_CHARGE;
                this.stateTicks = 0;
                this.ticksSincePearl = 0;
                this.setRotation(this.plan.chargeYaw, this.plan.chargePitch);
                break;
            case AIMING_CHARGE:
                if (!this.hasRotationControl()) {
                    return;
                }
                // 每 tick 推进珍珠模拟并重新解算风弹拦截方向（动态追踪玩家移动）
                this.updateChargeAim(player);
                // 就绪判定：误差 < 10°（风弹爆炸范围兜底）或尝试超过 12 tick
                // 强制进入发射阶段——不能用 isRotationReady()（isComplete 会被
                // 动态更新反复重置，实测卡 88 tick 直到珍珠落地才"就绪"）。
                if (this.isChargeAimed(10.0f) || this.stateTicks > 12) {
                    Vape.debugLog("[PearlCatch] charge aimed ticks=" + this.stateTicks
                            + " sincePearl=" + this.ticksSincePearl + " chargeTick=" + this.plan.chargeTick);
                    this.state = State.WAITING_FOR_CHARGE;
                    this.stateTicks = 0;
                }
                break;
            case WAITING_FOR_CHARGE:
                // 持续追踪珍珠（玩家/珍珠都在动），到达最早发射 tick 后
                // 强制瞄准（silent aim 下直接改渲染视角到最新目标方向）并发射。
                this.updateChargeAim(player);
                if (this.ticksSincePearl >= this.plan.chargeTick) {
                    Vape.debugLog("[PearlCatch] throw charge slot=" + this.chargeSlot
                            + " sincePearl=" + this.ticksSincePearl);
                    this.forceAimOnTarget();
                    this.throwFromSlot(this.chargeSlot);
                    // 保留 1 tick 再关闭：KeyBinding 在下一 tick processKeyBinds
                    // 才真正投出，此时 rotation 仍由本模块持有，投掷方向才正确；
                    // 立即 setEnabled(false) 会释放旋转，下 tick 用玩家真实视角投。
                    this.state = State.FINISHED;
                    this.stateTicks = 0;
                }
                break;
            case FINISHED:
                if (this.stateTicks >= 1) {
                    this.setEnabled(false, true);
                }
                break;
            default:
                break;
        }
    }

    private boolean hasRotationControl() {
        return this.rotationClaim.isOwnedBy(this)
                || this.rotationClaim.acquire(this, this.silentAim.getEffectiveValue());
    }

    private boolean isRotationReady() {
        if (this.rotationController == null || !this.rotationClaim.isOwnedBy(this)) {
            return false;
        }
        if (RotationManager.INSTANCE.getActiveController() != this.rotationController) {
            RotationManager.INSTANCE.setController(this.rotationController);
        }
        return this.rotationController.isComplete();
    }

    /**
     * 动态目标下的就绪判定：直接比较控制器当前视角与目标视角的误差。
     * 不能依赖 isComplete()——每 tick 更新目标都会重置它，动态追踪下
     * 永远追不上（实测卡 88 tick）。误差用容差内即可（风弹爆炸范围兜底）。
     */
    private boolean isChargeAimed(float tolerance) {
        if (this.rotationController == null || !this.rotationClaim.isOwnedBy(this)) {
            return false;
        }
        float yawError = Math.abs(wrapAngle(this.rotationController.getTargetYaw()
                - this.rotationController.getCurrentYaw()));
        float pitchError = Math.abs(this.rotationController.getTargetPitch()
                - this.rotationController.getCurrentPitch());
        return yawError <= tolerance && pitchError <= tolerance;
    }

    /**
     * 发射前强制瞄准（silent）：只改 AdaptiveRotationController 的渲染视角
     * 到最新解算方向，不动玩家真实视角（用户要求 PearlCatch 全程 silent aim）。
     * 投掷走 KeyBinding 模拟（延迟 1 tick），届时 RotationManager 已把该渲染
     * 视角同步进玩家 rotationYaw/Pitch，风弹按正确方向飞出。
     */
    private void forceAimOnTarget() {
        if (!(this.rotationController instanceof AdaptiveRotationController)) {
            return;
        }
        AdaptiveRotationController adaptive = (AdaptiveRotationController) this.rotationController;
        adaptive.setCurrentYaw(adaptive.getTargetYaw());
        adaptive.setCurrentPitch(adaptive.getTargetPitch());
    }

    private void setRotation(float yaw, float pitch) {
        FixedRotationController controller = this.silentAim.getEffectiveValue()
                ? new AdaptiveRotationController(yaw, pitch)
                : new FixedRotationController(yaw, pitch);
        controller.setTargetRotation(yaw, pitch);
        controller.setSpeed(this.aimSpeed.getValue().floatValue());
        controller.setTolerance(0.35f);
        controller.setScaleAxesProportionally(true);
        controller.setLinearAcceleration(true);
        controller.setClampStepToRemaining(true);
        controller.setRetainAfterCompletion(true);
        this.rotationController = controller;
        if (this.rotationClaim.isOwnedBy(this)) {
            RotationManager.INSTANCE.setController(controller);
        }
    }

    private void releaseRotation() {
        if (this.rotationController != null
                && RotationManager.INSTANCE.getActiveController() == this.rotationController) {
            RotationManager.INSTANCE.releaseController(this.rotationController);
        }
        this.rotationClaim.release(this);
    }

    private void throwFromSlot(int slot) {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNull()) {
            return;
        }
        player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(slot);
        // KeyBinding 模拟右键：投掷在下一 tick 的 processKeyBinds 执行。
        // 这 1 tick 延迟恰好让 RotationManager 把 silent 渲染视角同步进玩家
        // rotationYaw/Pitch——投掷方向 = 发射前 forceAimOnTarget 的瞄准方向。
        KeyBinding useKey = Minecraft.gameSettings().b$src$Lgg_vape_wrapper_impl_KeyBinding_$1yi3362();
        SharedModuleControlClaims.rightClickUse.blockUse();
        try {
            KeyBinding.setKeyBindState(useKey, true);
            KeyBinding.onTick(useKey);
        } finally {
            KeyBinding.setKeyBindState(useKey, false);
            SharedModuleControlClaims.rightClickUse.clearClaimed();
        }
    }

    private int findHotbarSlot(InventoryPlayer inventory, String itemId, String... displayNames) {
        String keySuffix = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inventory.c(slot);
            String slotName = null;
            try {
                if (!stack.isNull() && !stack.getItem().isNull()) {
                    slotName = stack.getItem().A();
                }
            } catch (Throwable t) {
                slotName = "<A() threw " + t.getClass().getSimpleName() + ">";
            }
            Vape.debugLog("[PearlCatch] scan slot=" + slot + " isNull=" + stack.isNull()
                    + " target=" + keySuffix + " name=" + slotName);
            if (stack.isNull() || stack.getItem().isNull()) {
                continue;
            }
            Item item = stack.getItem();
            String name = null;
            try {
                name = item.A();
            } catch (Throwable t) {
                Vape.debugLog("[PearlCatch] A() threw at slot=" + slot + ": " + t.getClass().getName());
                continue;
            }
            if (name != null && name.contains(keySuffix)) {
                return slot;
            }
            if (name != null) {
                for (String displayName : displayNames) {
                    if (displayName != null && !displayName.isEmpty()
                            && name.contains(displayName.toLowerCase())) {
                        return slot;
                    }
                }
            }
        }
        return -1;
    }

    private void abort() {
        if (this.isEnabled()) {
            this.setEnabled(false, true);
        } else {
            this.onDisable();
        }
    }

    private InterceptPlan findUpwardPlan(EntityPlayerSP player, int minimumDelay) {
        // 垂直上抛（pitch=-90）时 yaw 不影响弹道方向：保持当前视角 yaw，
        // 只把 pitch 转到 -90，避免远距离 yaw 旋转导致瞄准超时。
        float referenceYaw = player.J();
        float pearlPitch = -90.0f;
        float chargePitch = -90.0f;
        float pearlYaw = referenceYaw;
        float chargeYaw = referenceYaw;

        // 两弹同竖直线：风弹无重力、衰减慢，珍珠有重力先升后停。风弹越早扔
        // 越会在低空追上珍珠引爆——快且直观。旧版在 delayBase+20~+50 搜索
        // "最小 miss"，实际选中 chargeTick=44（约 2.2 秒），珍珠已快飞到顶点，
        // 用户实测"珍珠扔了很久才扔风弹"、拦截点太高无效。
        // 改为短窗口（2~8 tick）内尽早交汇：优先最早可执行的 chargeTick；
        // 该窗口内无解（极端情况）再放宽到 50 tick 兜底，保证不直接失败。
        int base = Math.max(2, minimumDelay);
        InterceptPlan best = this.sweepUpwardCharge(player, referenceYaw, base, base + 6, minimumDelay, true);
        if (best == null) {
            best = this.sweepUpwardCharge(player, referenceYaw, base + 7, base + 50, minimumDelay, false);
        }
        return best;
    }

    private InterceptPlan sweepUpwardCharge(EntityPlayerSP player, float yaw,
                                            int from, int to, int minimumDelay, boolean preferEarliest) {
        InterceptPlan best = null;
        double bestScore = Double.MAX_VALUE;
        for (int chargeTick = from; chargeTick <= to; ++chargeTick) {
            InterceptPlan candidate = this.evaluatePlan(
                    player, yaw, -90.0f, yaw, -90.0f, chargeTick, minimumDelay);
            if (candidate == null) {
                continue;
            }
            // preferEarliest：与当前最优 miss 差距超过阈值才替换，保持最早计划；
            // 否则按 miss 最小择优。
            double score = candidate.missDistanceSquared;
            if (score < bestScore - (preferEarliest ? 1.0E-6 : 0.0)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private InterceptPlan findCurrentAimPlan(EntityPlayerSP player, int minimumDelay) {
        float chargeYaw = player.J();
        float chargePitch = player.V();
        InterceptPlan best = null;
        double bestScore = Double.MAX_VALUE;

        // 风弹沿当前视角方向；珍珠通常需要斜上抛、等它落回与水平风弹相交，
        // 相交时刻约在 20~60 tick（珍珠弹道全程 100 tick）。延迟搜索放宽到
        // minimumDelay + 40，规格允许 "wait longer when needed"。
        for (int delay = Math.max(3, minimumDelay); delay <= Math.max(3, minimumDelay) + 40; ++delay) {
            for (float pitch = -90.0f; pitch <= 60.0f; pitch += 2.5f) {
                for (float yawOffset = -180.0f; yawOffset < 180.0f; yawOffset += 5.0f) {
                    float pearlYaw = chargeYaw + yawOffset;
                    InterceptPlan candidate = this.evaluatePlan(
                            player, pearlYaw, pitch, chargeYaw, chargePitch, delay, delay);
                    if (candidate == null) {
                        continue;
                    }
                    double score = candidate.missDistanceSquared
                            + Math.abs(wrapAngle(pearlYaw - chargeYaw)) * 0.0005
                            + delay * 0.0001;
                    if (score < bestScore) {
                        best = candidate;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 推进珍珠模拟 1 tick。严格对齐 MC 1.21.2+（数据驱动投射物）的
     * {@code ThrowableProjectile.tick()} 真实顺序：
     * <pre>
     *   applyGravity();   // v.y -= 0.03
     *   applyDrag();      // v *= 0.99（三轴）
     *   setPosition(pos + v);  // 位置用更新后的速度积分
     * </pre>
     * 即 v' = (v − g)·d，pos += v'。注意：
     * <ul>
     *   <li>1.21.0/1.21.1 旧顺序是 pos += v_old（位移用旧速度）→ v *= d → v.y −= g，
     *       与 vape Trajectories 模块一致；1.21.2 数据驱动重构改为上面的新顺序，
     *       官方确认 1.21.3+ 投掷物"落点比旧版短约 1 格"（Mojira MC-278577）；
     *   </li>
     *   <li>既不是 (v − g) 积分后再乘 d，也不是 v·d − g 后积分（两者都差一个 drag
     *       因子的位移基准，100 tick 累积数格偏差）。</li>
     * </ul>
     */
    private void advancePearlSim() {
        this.pearlMotionY -= PEARL_GRAVITY;
        this.pearlMotionX *= PEARL_DRAG;
        this.pearlMotionY *= PEARL_DRAG;
        this.pearlMotionZ *= PEARL_DRAG;
        this.pearlX += this.pearlMotionX;
        this.pearlY += this.pearlMotionY;
        this.pearlZ += this.pearlMotionZ;
    }

    /**
     * 动态风弹瞄准：推进珍珠模拟，再用玩家当前位置/速度实时解算风弹拦截
     * 方向并更新旋转目标。玩家移动时珍珠与玩家的相对位置每 tick 都在变，
     * 旧版一次性静态计划因此失效——这里每 tick 重新解算，持续修正。
     */
    private void updateChargeAim(EntityPlayerSP player) {
        if (!this.pearlSimActive || this.rotationController == null) {
            return;
        }
        this.advancePearlSim();
        // 风弹通过 KeyBinding 模拟右键，原版在下一 tick 的 processKeyBinds 才真正
        // 投出，因此实际发射点比"本 tick 解算点"晚 1 tick——发射点预测补偿。
        int minDelay = Math.max(0, this.plan.chargeTick - this.ticksSincePearl) + 1;
        float[] aim = this.solveChargeDirection(player, minDelay);
        if (aim != null) {
            this.rotationController.setTargetRotation(aim[0], aim[1]);
        }
    }

    /**
     * 解算风弹拦截方向。物理模型严格对齐 MC 1.21：
     * <ul>
     *   <li>风弹初速 = direction × 1.5（纯方向，<b>不含</b>玩家速度——原版
     *       WindChargeItem.use → shootFromRotation 语义）；</li>
     *   <li>发射点 = 玩家眼睛位置 + 玩家速度 × minDelay（等待期间玩家继续
     *       移动，发射点预测包含该位移）；</li>
     *   <li>风弹无重力，逐 tick 乘 0.99 阻力；</li>
     *   <li>珍珠有重力 0.03、阻力 0.99，顺序同 MC（v·d − g 后积分）。</li>
     * </ul>
     * 对每个到达时间 t（1..40 tick）解出方向并精确模拟验证最小间距，取最优。
     *
     * @return {yaw, pitch}；无解返回 null
     */
    private float[] solveChargeDirection(EntityPlayerSP player, int minDelay) {
        double pvx = player.t();
        double pvy = player.b$src$Z$fqlxe4() ? 0.0 : player.q();
        double pvz = player.T();
        // 预测发射点（眼睛高度）：minDelay tick 后玩家位置
        double cx = player.z() + pvx * minDelay;
        double cy = player.N() + player.Y() + pvy * minDelay;
        double cz = player.h() + pvz * minDelay;
        // 珍珠在 minDelay tick 后的状态（从当前模拟状态继续推进）
        double ex = this.pearlX;
        double ey = this.pearlY;
        double ez = this.pearlZ;
        double emx = this.pearlMotionX;
        double emy = this.pearlMotionY;
        double emz = this.pearlMotionZ;
        // 珍珠从当前状态推进到风弹生成时刻（1.21.2+ 顺序：减重力 → 乘阻力 → 积分）
        for (int i = 0; i < minDelay; ++i) {
            emy -= PEARL_GRAVITY;
            emx *= PEARL_DRAG;
            emy *= PEARL_DRAG;
            emz *= PEARL_DRAG;
            ex += emx;
            ey += emy;
            ez += emz;
        }
        double bestMiss = MAX_INTERCEPT_DISTANCE_SQUARED;
        float bestYaw = 0.0f;
        float bestPitch = 0.0f;
        boolean found = false;
        for (int t = 1; t <= 40; ++t) {
            // 珍珠在 (minDelay + t) tick 后的位置
            double tx = ex;
            double ty = ey;
            double tz = ez;
            double tmxx = emx;
            double tmyy = emy;
            double tmzz = emz;
            for (int i = 0; i < t; ++i) {
                tmyy -= PEARL_GRAVITY;
                tmxx *= PEARL_DRAG;
                tmyy *= PEARL_DRAG;
                tmzz *= PEARL_DRAG;
                tx += tmxx;
                ty += tmyy;
                tz += tmzz;
            }
            // 风弹 t tick 内总位移系数 S = 1 + 0.99 + ... + 0.99^(t-1)
            //                              = (1 - 0.99^t) / 0.01
            // （旧代码从 0.99 起累加、少了 i=0 项，解算距离系统性偏小）
            double sSum = (1.0 - Math.pow(PEARL_DRAG, t)) / (1.0 - PEARL_DRAG);
            // 目标 − 发射点 = v0 × S，因此 v0 = (目标 − 发射点) / S
            double dx = tx - cx;
            double dy = ty - cy;
            double dz = tz - cz;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-9) {
                continue;
            }
            // v0 长度 = len / S；风弹初速上限 1.5，超限说明 t tick 内够不到
            double requiredSpeed = len / sSum;
            if (requiredSpeed > PROJECTILE_SPEED) {
                continue;
            }
            double ux = dx / len;
            double uy = dy / len;
            double uz = dz / len;
            // 用真实风弹物理精确模拟验证最小间距（初速 = 方向×1.5，无玩家速度）
            double chx = cx;
            double chy = cy;
            double chz = cz;
            double cmx = ux * PROJECTILE_SPEED;
            double cmy = uy * PROJECTILE_SPEED;
            double cmz = uz * PROJECTILE_SPEED;
            double px2 = ex;
            double py2 = ey;
            double pz2 = ez;
            double pmx2 = emx;
            double pmy2 = emy;
            double pmz2 = emz;
            double minDist = Double.MAX_VALUE;
            for (int i = 1; i <= t; ++i) {
                // 珍珠：applyGravity → applyDrag → pos += v（1.21.2+ 顺序）
                pmy2 -= PEARL_GRAVITY;
                pmx2 *= PEARL_DRAG;
                pmy2 *= PEARL_DRAG;
                pmz2 *= PEARL_DRAG;
                px2 += pmx2;
                py2 += pmy2;
                pz2 += pmz2;
                // 风弹：pos += v（本 tick 开始时的速度）→ v *= d（无重力）
                // （AbstractHurtingProjectile 未在 1.21.2 重构内，顺序不变）
                chx += cmx;
                chy += cmy;
                chz += cmz;
                cmx *= PEARL_DRAG;
                cmy *= PEARL_DRAG;
                cmz *= PEARL_DRAG;
                double ddx = px2 - chx;
                double ddy = py2 - chy;
                double ddz = pz2 - chz;
                double d = ddx * ddx + ddy * ddy + ddz * ddz;
                if (d < minDist) {
                    minDist = d;
                }
            }
            if (minDist < bestMiss) {
                bestMiss = minDist;
                bestYaw = (float) Math.toDegrees(Math.atan2(-ux, uz));
                bestPitch = (float) Math.toDegrees(Math.asin(-uy));
                found = true;
            }
        }
        if (!found) {
            return null;
        }
        return new float[]{bestYaw, bestPitch};
    }

    private InterceptPlan evaluatePlan(EntityPlayerSP player,
                                       float pearlYaw, float pearlPitch,
                                       float chargeYaw, float chargePitch,
                                       int chargeTick, int minimumDelay) {
        Vec pearlDirection = direction(pearlYaw, pearlPitch);
        Vec chargeDirection = direction(chargeYaw, chargePitch);
        double playerMotionX = player.t();
        double playerMotionY = player.b$src$Z$fqlxe4() ? 0.0 : player.q();
        double playerMotionZ = player.T();
        // MC 1.21 投掷物发射语义：珍珠/风弹初速 = 朝向×1.5 纯方向（不含玩家
        // 速度）；玩家速度只用于预测发射点（chargeTick tick 后玩家位置）。
        double pearlX = player.z();
        double pearlY = player.N() + player.Y() - 0.1;
        double pearlZ = player.h();
        double pearlMotionX = pearlDirection.x * PROJECTILE_SPEED;
        double pearlMotionY = pearlDirection.y * PROJECTILE_SPEED;
        double pearlMotionZ = pearlDirection.z * PROJECTILE_SPEED;
        double chargeX = player.z() + playerMotionX * chargeTick;
        double chargeY = player.N() + player.Y() + playerMotionY * chargeTick;
        double chargeZ = player.h() + playerMotionZ * chargeTick;
        double chargeMotionX = chargeDirection.x * PROJECTILE_SPEED;
        double chargeMotionY = chargeDirection.y * PROJECTILE_SPEED;
        double chargeMotionZ = chargeDirection.z * PROJECTILE_SPEED;

        double closest = Double.MAX_VALUE;
        // 珍珠：applyGravity → applyDrag → pos += v（MC 1.21.2+ ThrowableProjectile）。
        // 风弹：pos += v（本 tick 开始时的速度）→ v *= d，无重力
        // （MC AbstractHurtingProjectile，1.21.2 数据驱动重构未改此顺序）。
        for (int tick = 1; tick <= MAX_PEARL_TICKS; ++tick) {
            pearlMotionY -= PEARL_GRAVITY;
            pearlMotionX *= PEARL_DRAG;
            pearlMotionY *= PEARL_DRAG;
            pearlMotionZ *= PEARL_DRAG;
            pearlX += pearlMotionX;
            pearlY += pearlMotionY;
            pearlZ += pearlMotionZ;
            if (tick <= chargeTick) {
                continue;
            }
            chargeX += chargeMotionX;
            chargeY += chargeMotionY;
            chargeZ += chargeMotionZ;
            chargeMotionX *= PEARL_DRAG;
            chargeMotionY *= PEARL_DRAG;
            chargeMotionZ *= PEARL_DRAG;
            double dx = pearlX - chargeX;
            double dy = pearlY - chargeY;
            double dz = pearlZ - chargeZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < closest) {
                closest = distance;
            }
        }

        if (chargeTick < minimumDelay || closest > MAX_INTERCEPT_DISTANCE_SQUARED) {
            return null;
        }
        return new InterceptPlan(
                pearlYaw, pearlPitch, chargeYaw, chargePitch,
                chargeTick, closest);
    }

    private static Vec direction(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        return new Vec(
                -Math.sin(yawRadians) * Math.cos(pitchRadians),
                -Math.sin(pitchRadians),
                Math.cos(yawRadians) * Math.cos(pitchRadians));
    }

    private static float wrapAngle(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) {
            angle -= 360.0f;
        }
        if (angle < -180.0f) {
            angle += 360.0f;
        }
        return angle;
    }

    private enum State {
        IDLE,
        AIMING_PEARL,
        THROWING_PEARL,
        AIMING_CHARGE,
        WAITING_FOR_CHARGE,
        FINISHED
    }

    private static final class InterceptPlan {
        private final float pearlYaw;
        private final float pearlPitch;
        private final float chargeYaw;
        private final float chargePitch;
        private final int chargeTick;
        private final double missDistanceSquared;

        private InterceptPlan(float pearlYaw, float pearlPitch,
                              float chargeYaw, float chargePitch,
                              int chargeTick,
                              double missDistanceSquared) {
            this.pearlYaw = pearlYaw;
            this.pearlPitch = pearlPitch;
            this.chargeYaw = chargeYaw;
            this.chargePitch = chargePitch;
            this.chargeTick = chargeTick;
            this.missDistanceSquared = missDistanceSquared;
        }
    }

    private static final class Vec {
        private final double x;
        private final double y;
        private final double z;

        private Vec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

    }
}
