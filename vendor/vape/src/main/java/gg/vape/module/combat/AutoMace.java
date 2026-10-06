package gg.vape.module.combat;

import gg.vape.Vape;
import gg.vape.event.EventHandler;
import gg.vape.event.EventPriority;
import gg.vape.event.impl.EventKeyPress;
import gg.vape.event.impl.EventMouseButton;
import gg.vape.event.impl.EventPostAttack;
import gg.vape.event.impl.EventPreTick;
import gg.vape.event.impl.SyntheticAttackRequestEvent;
import gg.vape.input.AttackKeyController;
import gg.vape.mapping.MappedClasses;
import gg.vape.module.Category;
import gg.vape.module.Mod;
import gg.vape.module.blatant.blockin.BlockPathPlanner;
import gg.vape.module.blatant.blockin.BlockPlacementGraph;
import gg.vape.module.combat.automace.AutoMaceRotationController;
import gg.vape.module.control.AttackCancellationAdapter;
import gg.vape.module.control.PhysicalAttackCancellationAdapter;
import gg.vape.module.control.SharedModuleControlClaims;
import gg.vape.module.control.SyntheticAttackCancellationAdapter;
import gg.vape.module.none.ClientSettings;
import gg.vape.rotation.RotationControlClaim;
import gg.vape.rotation.RotationManager;
import gg.vape.unmap.ItemLimitData;
import gg.vape.unmap.ModeOption;
import gg.vape.utils.AttackCooldownUtil;
import gg.vape.utils.ItemStackScoreUtil;
import gg.vape.utils.RotationUtil;
import gg.vape.utils.TimerUtil;
import gg.vape.value.BooleanValue;
import gg.vape.value.EntityTargetFilterValue;
import gg.vape.value.LimitValue;
import gg.vape.value.ModeValue;
import gg.vape.value.NumberValue;
import gg.vape.value.RandomValue;
import gg.vape.wrapper.impl.AxisAlignedBB;
import gg.vape.wrapper.impl.EnchantmentHelper;
import gg.vape.wrapper.impl.Entity;
import gg.vape.wrapper.impl.EntityLivingBase;
import gg.vape.wrapper.impl.EntityOtherPlayerMP;
import gg.vape.wrapper.impl.EntityPlayer;
import gg.vape.wrapper.impl.EntityPlayerSP;
import gg.vape.wrapper.impl.EnumHand;
import gg.vape.wrapper.impl.InventoryPlayer;
import gg.vape.wrapper.impl.ItemStack;
import gg.vape.wrapper.impl.Minecraft;
import gg.vape.wrapper.impl.MonsterAttributesBridge;
import gg.vape.wrapper.impl.RayTraceResult;
import gg.vape.wrapper.impl.RayTraceResult_type;
import gg.vape.wrapper.impl.Vec3;
import gg.vape.wrapper.impl.WorldClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AutoMace extends Mod {
    private static final int MODULE_ID = -16732037;
    // 预测窗口。旧值 20 tick（约 0.5s 自由落体、~5 格落差）只覆盖低空滑翔：
    // 高空（10~40 格）滑翔时预测永远"够不着" → 不脱鞘翅 → 白白飞过（日志
    // 实证：脱鞘翅后的 10/10 全部重锤成功，说明失败全在"没触发脱鞘翅"的
    // 尝试）。放宽到 40 tick（约 2s、~19 格落差），覆盖多数高空滑翔的
    // 脱鞘翅时机；模拟在落地采样点即 break，不产生额外噪声。
    private static final int PREDICTION_TICKS = 40;
    // 瞄准提前锁定的命中窗口。旧值 6 tick 只覆盖"最后一刻"——脱鞘翅后自由落体
    // 时目标通常在滑翔方向（前下方）视野外 60°~120°，6 tick 窗口内 aim 才启动，
    // 视角来不及转到目标，攻击依赖准星 → 大量"脱了却打不出"（日志实证：多数
    // 脱鞘翅后自由落体直接落地、落地才重装）。放宽到 15 tick：aim 提前锁定目标
    // 并持续跟踪，接近时视角已对准；顺带覆盖服务端容器同步延迟（攻击发出时
    // 服务端 fallDistance 已开始累积，重锤判定才生效）。
    private static final int MAX_AIM_IMPACT_TICK = 15;
    // 脱鞘翅窗口内 aim 的预测 tick 上限。脱鞘翅后玩家从高空自由落体冲向
    // 目标，命中点常在 16~30 tick 外（日志实证 aim blocked tick=20~29），
    // 15 tick 上限会让 aim 前半程不锁定 → 准星一直对不上 → crosshair=null
    // → 攻击拖到 fallD 低时才碰巧打出（服务端不认 smash）或直接落地。
    // 放宽到 30：脱鞘翅瞬间即锁定，匀速转视角，攻击由 updateAutoAttack
    // 的 smash 门槛（fallD>1.5）把关，不受影响。
    private static final int ELYTRA_AIM_IMPACT_TICK = 30;
    private static final int ELYTRA_EXIT_TIMEOUT_TICKS = 8;
    private static final int REEQUIP_DELAY_TICKS = 3;
    private static final double REACH_MARGIN = 0.9;
    // 脱鞘翅预测的 reach 余量。瞄准预测用 0.9（命中即锁定、避免脱靶白摔），
    // 但脱鞘翅预测放宽到 2.0——目标常在落点前方稍远处（滑翔方向继续飞一段
    // 才到，立即停飞的落点距目标 4~6 格），1.25 会把这类场景卡成"够不着"而
    // 不脱鞘翅飞过（日志实证 noPredTarget 频发）。放宽到 2.0：静止/慢速目标
    // 在落点 6 格内即脱，脱鞘翅后玩家下落经过目标上方附近，aim（unequipAimTarget
    // 锁定）转头后攻击时距离自然缩小到 reach 内。误触发仅多一段自由落体。
    private static final double UNEQUIP_REACH_MARGIN = 2.0;
    // === 服务端 fallDistance 滞后补偿 ===
    // 脱鞘翅后客户端立即停飞累积 fallDistance，但服务端要等窗口点击包 + 容器
    // 同步（约 2~3 tick）才开始累积。攻击包的 fallDistance 在服务端结算——
    // 客户端 fallD=1.5 时攻击包到达服务端 fallD 可能只有 0.5~1.0，smash 不生效。
    // 要求客户端 fallD 超过 1.5 + 1.0 = 2.5 再发攻击（实测日志大量
    // "attack fired smash=true 但目标没被重锤"即此空锤）。
    private static final float SMASH_FALL_DISTANCE_THRESHOLD = 1.5f;
    private static final float SERVER_LAG_FALL_MARGIN = 1.0f;
    // 脱鞘翅后仅在最开始几 tick 强制清 fallFlying 标志（等窗口点击生效、容器
    // 同步到位——此期间 MC 仍可能因"胸甲槽还是鞘翅 + 按住空格"重新滑翔），
    // 之后不再每 tick 清：脱鞘翅成功后胸甲槽已无鞘翅，MC 物理上不会重新滑翔，
    // 持续清 flag 反而干扰本地移动包（服务端据此推断停飞与 fallD 累积）。
    private int elytraExitFlagClearTicks;

    private static final ModeOption SELECTION_MANUAL = new ModeOption("Manual");
    private static final ModeOption SELECTION_AUTO = new ModeOption("Auto");
    private static final ModeOption TYPE_DENSITY = new ModeOption("Density");
    private static final ModeOption TYPE_BREACH = new ModeOption("Breach");

    public final EntityTargetFilterValue targetFilter = EntityTargetFilterValue.createForModule(this);
    public final ModeValue maceSelection;
    public final ModeValue maceType;
    public final BooleanValue stunSlam;
    public final NumberValue stunSlamChance;
    public final BooleanValue aim;
    public final BooleanValue silentAim;
    public final NumberValue aimRange;
    public final BooleanValue attack;
    public final RandomValue extraDelay;
    public final BooleanValue autoUnequipElytra;
    public final BooleanValue reEquipElytra;
    public final BooleanValue smashOnly;
    public final BooleanValue limitToItems;
    public final LimitValue allowedItems;

    private final TimerUtil inputTimer = new TimerUtil();
    private final RotationControlClaim rotationClaim = SharedModuleControlClaims.rotation;
    private AutoMaceRotationController rotationController;
    private BlockPlacementGraph movementSnapshot;

    private boolean releasePending;
    private boolean dispatchingSyntheticAttack;
    private boolean weaponSwapActive;
    private boolean stunSlamFollowupPending;
    private int originalSlot = -1;
    private int maceSlot = -1;
    private int targetId = -1;
    private int originalAttackTick = -1;
    private int weaponSwapTicks;

    private int armorOriginalSlot = -1;
    private int armorTargetSlot = -1;
    private int armorSwapTicks;
    private boolean armorSwapToElytra;
    private boolean swappedItemWasElytra;
    private boolean shouldReequipElytra;
    private int reequipDelay;
    private int reequipRetries;
    private boolean wasFallFlying;
    private int unequipArmorSlot = -1;
    private boolean smashAttackPerformed;
    private int reequipGraceTicks;
    private ElytraFlightState elytraFlightState = ElytraFlightState.IDLE;
    private int elytraFlightStateTicks;
    private int unequipNoTargetLogCooldown;
    // 脱鞘翅预测选中的目标：unequip 预测（滑翔满速 40 tick）已保证落点可达，
    // aim 在脱鞘翅后用衰减后的速度重算 prediction 可能判"够不着"而不锁定
    // （日志实证 dist=18~25 时 aim 持续 valid=false → crosshair=null → 打不出）。
    // 记录该目标，updateAim 优先锁定它、prediction 失败也转头——把脱鞘翅与
    // 转头绑成一个动作：先脱（停飞累积 fallD）→ 转头（下落同时对准）→ 攻击。
    private EntityLivingBase unequipAimTarget;
    // aim 在滑翔中锁定目标后置位，请求下一 tick 脱鞘翅：滑翔中 updateAim
    // 用滑翔停飞预测判定"停飞下落可打"，锁定即请求脱——用户诉求"触发静默
    // 转头的那一刻脱鞘翅"。updateElystateState 消费后清。
    private boolean unequipRequested;
    // 请求消费失败（容器缓存持续看不到鞘翅）时的重试上限，避免无限重试
    private int unequipRequestRetries;

    public AutoMace() {
        super("AutoMace", MODULE_ID, Category.COMBAT);
        this.maceSelection = ModeValue.create(this, "Mace selection",
                "Manual uses Mace type. Auto chooses the best mace enchantment for your fall distance and target armor.",
                SELECTION_MANUAL, SELECTION_MANUAL, SELECTION_AUTO);
        this.maceType = ModeValue.create(this, "Mace type",
                "Selects which mace enchantment AutoMace should use. Bind this setting to cycle it in game.",
                TYPE_DENSITY, TYPE_DENSITY, TYPE_BREACH);
        this.stunSlam = BooleanValue.create(this, "Stun slam", false,
                "When holding an axe and attacking a shielded player:\nHits with axe first (breaks shield), then swaps to mace for a follow-up slam");
        this.stunSlamChance = NumberValue.create(this, "Chance", "#", "%", 0.0, 100.0, 100.0, 1.0,
                "Chance that Stun slam will trigger");
        this.aim = BooleanValue.create(this, "Aim", false,
                "Aims at the nearest valid target while falling for a smash attack");
        this.silentAim = BooleanValue.create(this, "Silent aim", false, "Uses Silent Aim system");
        this.aimRange = NumberValue.create(this, "Aim range", "#.#", "", 2.0, 6.0, 10.0, 0.1,
                "Maximum horizontal distance to search for targets");
        this.attack = BooleanValue.create(this, "Attack", false, "Automatically attacks valid mace targets");
        this.extraDelay = RandomValue.createWithDescription(this, "Extra delay", "#", "ticks",
                -3.0, 0.0, 0.0, 20.0, 0.1,
                "Extra delay after attack cooldown(in ticks)\nNegative values will attack before cooldown is complete (min -3, avoid server fallDistance desync)");
        this.autoUnequipElytra = BooleanValue.create(this, "Auto unequip Elytra", false,
                "Equips a hotbar chestplate when your predicted fall can reach a mace target");
        this.reEquipElytra = BooleanValue.create(this, "Re-equip Elytra", false,
                "Puts the Elytra back on after upward mace bounce movement is detected");
        this.smashOnly = BooleanValue.create(this, "Smash only", true, "Only swap to mace if will smash");
        this.limitToItems = BooleanValue.create(this, "Limit to items", false);
        this.allowedItems = LimitValue.create(this, "am-alloweditems", "Allowed Items",
                LimitValue.ALLOW_LIST_COLOR, Arrays.asList(new ItemLimitData("swords")));

        this.addValue(this.targetFilter, this.aim, this.silentAim, this.aimRange, this.attack, this.extraDelay,
                this.autoUnequipElytra, this.reEquipElytra, this.smashOnly,
                this.maceSelection, this.maceType,
                this.stunSlam, this.stunSlamChance, this.limitToItems, this.allowedItems);
        this.aim.addDependentValues(this.silentAim, this.aimRange);
        this.attack.addDependentValues(this.extraDelay);
        this.autoUnequipElytra.addDependentValues(this.reEquipElytra);
        this.maceSelection.addModeDependentValues(SELECTION_MANUAL, this.maceType);
        this.stunSlam.addDependentValues(this.stunSlamChance);
        this.limitToItems.addDependentValues(this.allowedItems);
        this.rotationClaim.setPriority(this, 5);
    }

    @Override
    public String getSimpleSuffix() {
        return this.isAutomaticSelection()
                ? this.maceSelection.getValue().toString()
                : this.maceType.getValue().toString();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKeyPress(EventKeyPress event) {
        if (event.isKeybinding(Minecraft.gameSettings().F()) && event.isDown()) {
            this.inputTimer.reset();
            if (!event.isCanceled()) {
                this.handleAttack(new PhysicalAttackCancellationAdapter(event));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMouseButton(EventMouseButton event) {
        if (event.isKeybinding(Minecraft.gameSettings().F()) && event.isDown()) {
            this.inputTimer.reset();
            if (!event.isCanceled()) {
                this.handleAttack(new PhysicalAttackCancellationAdapter(event));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSyntheticAttack(SyntheticAttackRequestEvent event) {
        Mod source = event.getSource();
        if (source == this || source instanceof HitSwap || source instanceof ShieldBreaker) {
            return;
        }
        this.inputTimer.reset();
        if (!event.isCanceled()) {
            this.handleAttack(new SyntheticAttackCancellationAdapter(event));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPostAttack(EventPostAttack event) {
        // 注意：这里不再触发鞘翅重装。普通命中（非重锤、下落距离未蓄满）也会
        // 走到本回调，若在此时重装鞘翅会打断自由落体——日志实证脱→~9tick 即
        // 穿回→循环，重锤永不发生。重装只由 updateBounceReequip（重锤反弹或
        // 落地恢复）触发，与攻击不同时发生。
        if (!this.weaponSwapActive || !this.stunSlamFollowupPending || event.getTarget().isNull()
                || event.getTarget().S() != this.targetId) {
            return;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        if (!this.isHoldingAxe(player)) {
            this.abortWeaponSwap(player);
            return;
        }
        this.completeStunSlamFollowup(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTick(EventPreTick event) {
        EntityPlayerSP player = event.getThePlayer();
        if (player.isNull()) {
            this.releaseRotation();
            return;
        }

        this.movementSnapshot = new BlockPlacementGraph(player);
        this.updateElytraState(player, event.getWorld());
        // 滑翔中也运行 updateAim：滑翔中 aim 用滑翔停飞预测锁定目标（可打才锁），
        // 锁定即请求脱鞘翅（unequipRequested）→ 下一 tick 脱下 → WAITING 窗口
        // aim 继续锁定转头。用户诉求"触发静默转头的那一刻脱鞘翅"。锁定失败时
        // updateAim 内部 release。原 elytraBusy 分支（滑翔中不 aim）是"看到了
        // 转头却没脱"的根源之一：aim 只在自由落体才运行，而自由落体时鞘翅
        // 早已在滑翔中该脱未脱。
        this.updateAim(player, event.getWorld());

        boolean releasedAttackThisTick = false;
        if (this.releasePending) {
            AttackKeyController.releaseAttackKey();
            this.releasePending = false;
            releasedAttackThisTick = true;
        }

        if (this.weaponSwapActive && this.stunSlamFollowupPending) {
            if (player.l() <= this.originalAttackTick) {
                return;
            }
            if (!this.isCrosshairTarget(this.targetId)) {
                this.abortWeaponSwap(player);
                return;
            }
            this.completeStunSlamFollowup(player);
            return;
        }

        if (this.weaponSwapActive && this.weaponSwapTicks++ > 1) {
            if (this.originalSlot != -1) {
                player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
                this.originalSlot = -1;
            }
            this.clearWeaponSwapState();
        }

        // 脱鞘翅后的 WAITING_FOR_GLIDE_END 窗口内，玩家正处于停飞自由落体，
        // 自动攻击不能被滑翔拦截——否则脱下鞘翅也打不出重锤
        // （实测根因："必须攻击后才脱"）。旋转仍照常释放，攻击闸门单独放宽。
        boolean glidingAttackGate = player.Y$src$Z$154rldp()
                && this.elytraFlightState != ElytraFlightState.WAITING_FOR_GLIDE_END;
        if (!releasedAttackThisTick && !glidingAttackGate) {
            this.updateAutoAttack(player);
        }
    }

    private void handleAttack(AttackCancellationAdapter cancellation) {
        if (this.weaponSwapActive || !this.canOperate()) {
            return;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        // 与 updateAutoAttack 同口径：WAITING_FOR_GLIDE_END（刚脱鞘翅、MC 因按住
        // 跳跃键每 tick 重置 fallFlying）不拦截攻击，否则手动/自动攻击都打不出。
        boolean fallFlying = player.Y$src$Z$154rldp()
                && this.elytraFlightState != ElytraFlightState.WAITING_FOR_GLIDE_END;
        if (player.isNull() || fallFlying || player.l$src$Z$1io4duf()) {
            return;
        }
        EntityLivingBase target = this.getCrosshairTarget();
        if (target == null) {
            return;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        if (this.shouldStunSlam(target)) {
            int selectedMaceSlot = this.findBestMaceSlot(target, false);
            if (selectedMaceSlot < 0) {
                return;
            }
            if (this.isHoldingAxe(player)) {
                this.beginStunSlam(player, selectedMaceSlot, target.S());
                return;
            }
            int axeSlot = this.findAxeSlot(inventory);
            if (axeSlot >= 0) {
                cancellation.setCancelled(true);
                this.originalSlot = inventory.v();
                inventory.g(axeSlot);
                this.maceSlot = selectedMaceSlot;
                this.originalAttackTick = player.l();
                this.targetId = target.S();
                this.weaponSwapActive = true;
                this.stunSlamFollowupPending = true;
                this.weaponSwapTicks = 0;
                return;
            }
        }

        int selectedMaceSlot = this.findBestMaceSlot(target, true);
        if (selectedMaceSlot < 0) {
            return;
        }
        this.originalSlot = inventory.v();
        inventory.g(selectedMaceSlot);
        this.weaponSwapActive = true;
        this.weaponSwapTicks = 0;
    }

    private void beginStunSlam(EntityPlayerSP player, int selectedMaceSlot, int selectedTargetId) {
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        this.originalSlot = inventory.v();
        this.maceSlot = selectedMaceSlot;
        this.originalAttackTick = player.l();
        this.targetId = selectedTargetId;
        this.weaponSwapActive = true;
        this.stunSlamFollowupPending = true;
        this.weaponSwapTicks = 0;
    }

    private void completeStunSlamFollowup(EntityPlayerSP player) {
        if (player.isNotNull() && this.isValidMaceSlot(player, this.maceSlot)) {
            player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.maceSlot);
            AttackKeyController.releaseAttackKey();
            this.releasePending = this.requestSyntheticAttack(true);
        }
        this.stunSlamFollowupPending = false;
        this.maceSlot = -1;
        this.originalAttackTick = -1;
        this.targetId = -1;
    }

    private boolean isValidMaceSlot(EntityPlayerSP player, int slot) {
        if (slot < 0 || slot >= 9) {
            return false;
        }
        return this.isMace(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().c(slot));
    }

    private void updateAutoAttack(EntityPlayerSP player) {
        // WAITING_FOR_GLIDE_END：鞘翅已脱、等待滑翔真正结束。此窗口内 MC 可能
        // 因玩家仍按住跳跃键每 tick 把 fallFlying 重新置 true（旧版日志实证），
        // 若照常以 fallFlying 拦截，自动攻击永远进不去 → "必须攻击后才脱"。
        // 脱鞘翅停飞后的自由落体是重锤流程的开始，此状态不再拦截攻击。
        boolean fallFlying = player.Y$src$Z$154rldp()
                && this.elytraFlightState != ElytraFlightState.WAITING_FOR_GLIDE_END;
        if (!this.attack.getEffectiveValue().booleanValue() || this.weaponSwapActive || this.releasePending
                || player.l$src$Z$1io4duf() || fallFlying || !this.isFalling(player)
                || !this.inputTimer.hasTimeElapsed(50L) || !this.canUseAttackInput()) {
            return;
        }

        EntityLivingBase target = this.getCrosshairTarget();
        if (target == null) {
            if (this.swappedItemWasElytra
                    || this.elytraFlightState == ElytraFlightState.WAITING_FOR_GLIDE_END) {
                Vape.debugLog("[AutoMace] attack blocked crosshair=null fallD=" + player.getFallDistance()
                        + " state=" + this.elytraFlightState + " yaw=" + player.J());
            }
            return;
        }
        if (!this.isAutoAttackReady(player, target, (float)(-this.extraDelay.getRandomValue()))) {
            if (this.swappedItemWasElytra
                    || this.elytraFlightState == ElytraFlightState.WAITING_FOR_GLIDE_END) {
                Vape.debugLog("[AutoMace] attack blocked cooldown fallD=" + player.getFallDistance()
                        + " smash=" + RotationUtil.u(player));
            }
            return;
        }

        boolean heldMaceReady = this.isHeldMaceReady(player, target);
        boolean alternateMaceReady = this.findBestMaceSlot(target, true) >= 0;
        boolean stunSlamReady = this.stunSlam.getEffectiveValue().booleanValue()
                && this.findBestMaceSlot(target, false) >= 0
                && this.findAxeSlot(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6()) >= 0;
        if (!heldMaceReady && !alternateMaceReady && !stunSlamReady) {
            return;
        }

        final boolean[] cancelled = new boolean[1];
        this.handleAttack(value -> cancelled[0] = value);
        if (!cancelled[0] && (heldMaceReady || alternateMaceReady || this.stunSlamFollowupPending)) {
            AttackKeyController.releaseAttackKey();
            this.releasePending = this.requestSyntheticAttack(false);
            // 重锤判定：攻击发出时"服务端将看到的"摔落距离 > 1.5（mace smash 门槛，
            // 补偿窗口点击 + 容器同步约 2~3 tick 滞后——客户端立即停飞先累积，
            // 服务端稍后才开始）。触发 re-unequip——重锤打出后装回鞘翅（普通攻击
            // 不重装，避免在蓄力期穿回打断自由落体）。
            if (this.willSmashOnServer(player)) {
                this.smashAttackPerformed = true;
            }
            Vape.debugLog("[AutoMace] attack fired fallD=" + player.getFallDistance()
                    + " serverFallD=" + this.estimatedServerFallDistance(player)
                    + " smash=" + this.smashAttackPerformed + " state=" + this.elytraFlightState
                    + " heldMace=" + heldMaceReady + " swapMace=" + alternateMaceReady);
        }
    }

    private boolean isAutoAttackReady(EntityPlayerSP player, EntityLivingBase target, float cooldownOffset) {
        if (RotationUtil.u(player)
                && (this.findBestMaceSlot(target, true) >= 0 || this.isHeldMaceReady(player, target))) {
            return true;
        }
        return AttackCooldownUtil.isAttackReady(cooldownOffset);
    }

    private void updateAim(EntityPlayerSP player, WorldClient world) {
        // WAITING_FOR_GLIDE_END：鞘翅已脱、MC 可能因按住跳跃键每 tick 重置
        // fallFlying（旧版日志实证），照常拦截会让脱鞘翅后的整个自由落体失去
        // 瞄准——重锤命中的前提就是瞄准，此状态不再以 fallFlying 拦截 aim。
        // 滑翔中（未脱，gliding=true）也不再拦截：滑翔中 aim 用滑翔停飞预测
        // 判定"停飞下落可打"，可打即锁定转头并请求脱鞘翅（unequipRequested），
        // 实现"触发静默转头的那一刻脱鞘翅"；判定失败则走下方 release。
        boolean gliding = player.Y$src$Z$154rldp();
        // 拦截只保留与"瞄准转头"真正相关的状态：不检查 canOperate（limitToItems
        // 白名单针对手持物品）与 hasMaceInHotbar——瞄准转头与脱鞘翅请求是纯
        // 视角/容器操作，手持白名单/热栏 mace 不该拦住它们。旧版这里被
        // canOperate 提前 return，unequipRequested 从未被设置——玩家看到转头
        // （其它模块或残留 controller）但 AutoMace 的脱鞘翅请求根本不存在
        // （用户反馈"转头了没脱"的最直接根因）。攻击闸门在 updateAutoAttack
        // 的 maceReady 检查把关，不受影响。
        if (!this.aim.getEffectiveValue().booleanValue() || world.isNull() || this.movementSnapshot == null
                || player.b$src$Z$fqlxe4()
                || player.f$src$Z$fst3rk() || player.h$src$Z$ftwoya() || player.S$src$Z$151gttj()
                || player.C$src$Lgg_vape_wrapper_impl_ModelPlayer_$19uhx86().isFlying()) {
            this.releaseRotation();
            return;
        }

        // 优先锁定脱鞘翅预测选中的目标：unequip 已用滑翔满速 40 tick 预测
        // 保证落点可达，aim 在脱鞘翅后用衰减后的速度重算可能判"够不着"
        // （日志实证 dist=18~25 时 aim 持续 valid=false → crosshair=null →
        // 打不出）。目标失效（死亡/无效/离开过滤）则回退常规最近目标。
        EntityLivingBase target = this.unequipAimTarget;
        if (target == null || target.isNull() || target.equals(player)
                || target.w$src$F$15l9epb() <= 0.0f || target.C$src$Z$f9kazx()
                || !this.targetFilter.isValidTarget(target)) {
            this.unequipAimTarget = null;
            target = this.findNearestTarget(player, world);
            if (target == null) {
                this.releaseRotation();
                return;
            }
        }

        double reach = this.getMaceReach();
        boolean fallingForSmash = RotationUtil.u(player);
        boolean directlyReachable = fallingForSmash
                && distanceToTarget(player.z(), player.N() + player.X(), player.h(), target, 0.0, 0.0, 0.0) <= reach;
        // 脱鞘翅窗口（已脱未重装 / 等待滑翔结束 / 滑翔中未脱）内，aim 判定与
        // unequip 预测对齐：① margin 用 UNEQUIP_REACH_MARGIN（unequip 放宽到
        // 2.0 才触发脱鞘翅，aim 若仍用 0.9 会出现"脱了但 aim 判定够不着"→
        // 不转视角 → 准星对不上 → 打不出）；② 不要求 fallD>1.5（脱鞘翅刚发生
        // fallD 从 0 起累积，日志实证目标 0.57 格近都因 fallD=0 被 requireSmash
        // 卡成 valid=false → 前 5~7 tick aim 必然不锁定，错过命中窗口）；③ tick
        // 上限放宽到 30（高空落点常在 16~30 tick 外）。普通自由落体保持原判定。
        boolean inElytraAimWindow = this.swappedItemWasElytra
                || this.elytraFlightState == ElytraFlightState.WAITING_FOR_GLIDE_END
                || gliding;
        // 滑翔中模拟"现在停飞下落"（startsFallFlying=true、glideTicks=0），与
        // unequip 预测同口径；自由落体/脱鞘翅后用真实下落轨迹。
        ImpactPrediction prediction = this.findImpact(this.simulateTrajectory(player, world, gliding, 0), target, reach,
                inElytraAimWindow ? UNEQUIP_REACH_MARGIN : REACH_MARGIN, !inElytraAimWindow);
        int maxImpactTick = inElytraAimWindow ? ELYTRA_AIM_IMPACT_TICK : MAX_AIM_IMPACT_TICK;
        if (!(prediction.valid && prediction.tick <= maxImpactTick || directlyReachable)) {
            if (target == this.unequipAimTarget) {
                // 脱鞘翅目标：unequip 预测（滑翔满速）已保证落点可达，aim 重算
                // 失败（衰减后速度够不着）不阻断转头——按真实物理估算命中 tick：
                // 垂直自由落体（重力 0.08 格/tick²，t = sqrt(2h/g)）与水平位移
                // （当前水平速度）两者取大，上限 ELYTRA_AIM_IMPACT_TICK，让
                // rotationController 立即锁定匀速转。
                AxisAlignedBB bounds = target.u$src$Lgg_vape_wrapper_impl_AxisAlignedBB_$kogbsu();
                double dx = target.z() - player.z();
                double dz = target.h() - player.h();
                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                double dy = player.N() - (bounds.getMinY() + bounds.getMaxY()) * 0.5;
                int fallTicks = (int) Math.ceil(Math.sqrt(Math.max(0.0, dy) / 0.04));
                double horizontalSpeed = Math.max(0.5, Math.hypot(player.t(), player.T()));
                int horizTicks = (int) Math.ceil(horizontalDist / horizontalSpeed);
                int estTick = Math.max(1, Math.min(ELYTRA_AIM_IMPACT_TICK, Math.max(fallTicks, horizTicks)));
                prediction.valid = true;
                prediction.tick = estTick;
                prediction.sourceX = player.z();
                prediction.sourceY = player.N() + player.X();
                prediction.sourceZ = player.h();
                prediction.aimX = (bounds.getMinX() + bounds.getMaxX()) * 0.5;
                prediction.aimY = bounds.getMinY() + (bounds.getMaxY() - bounds.getMinY()) * 0.75;
                prediction.aimZ = (bounds.getMinZ() + bounds.getMaxZ()) * 0.5;
                prediction.impactDistance = horizontalDist;
                prediction.fallDistanceAtImpact = player.getFallDistance() + (float) Math.max(0.0, dy);
            } else {
                if (this.swappedItemWasElytra
                        || this.elytraFlightState == ElytraFlightState.WAITING_FOR_GLIDE_END) {
                    Vape.debugLog("[AutoMace] aim blocked valid=" + prediction.valid
                            + " tick=" + prediction.tick + " reachable=" + directlyReachable
                            + " fallD=" + player.getFallDistance() + " dist="
                            + player.getDistanceToEntity(target));
                }
                this.releaseRotation();
                return;
            }
        }

        // 目标判定通过（滑翔中预测可打）即请求脱鞘翅——不依赖下方 rotationClaim
        // acquire 成功与否：转头可能被其它模块占用（acquire 失败、setPrediction
        // 不执行），但脱鞘翅是纯容器点击，不应被瞄准占用绑架。用户反馈
        // "silent aim 转头了却没脱"的根源之一正是请求被 acquire 提前 return 吞掉。
        // 条件只信任容器（胸甲槽确实有鞘翅才请求），swappedItemWasElytra 残留
        // 不再拦请求——容器对账每 tick 已纠正该标志，这里再兜底同步一次。
        if (gliding && this.elytraFlightState != ElytraFlightState.WAITING_FOR_GLIDE_END) {
            ItemStack chestForRequest = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(6).getStack();
            if (!chestForRequest.isNull() && this.isElytra(chestForRequest)) {
                this.swappedItemWasElytra = false;
                boolean firstRequest = !this.unequipRequested;
                this.unequipAimTarget = target;
                this.unequipRequested = true;
                this.unequipRequestRetries = 10;
                if (firstRequest) {
                    // 节流：仅"从未请求→首次请求"打日志（滑翔中每 tick 都锁定会刷屏）
                    Vape.debugLog("[AutoMace] aim unequip request dist="
                            + player.getDistanceToEntity(target));
                }
            }
        }

        if (this.rotationClaim.isBlockedFor(this)) {
            this.releaseRotation();
            return;
        }
        boolean silent = this.silentAim.getEffectiveValue().booleanValue();
        if (!this.rotationClaim.isOwnedBy(this) && !this.rotationClaim.acquire(this, silent)) {
            return;
        }
        if (this.rotationController == null
                || RotationManager.INSTANCE.getActiveController() != this.rotationController) {
            this.rotationController = new AutoMaceRotationController(silent);
            this.rotationController.setRetainAfterCompletion(true);
            RotationManager.INSTANCE.setController(this.rotationController);
        }
        this.rotationController.setTargetEntity(target);
        this.rotationController.setRenderLineWidth(reach);
        this.rotationController.setPrediction(prediction.valid, prediction.tick,
                prediction.sourceX, prediction.sourceY, prediction.sourceZ,
                prediction.aimX, prediction.aimY, prediction.aimZ, directlyReachable);
    }

    private List<FallSample> simulateTrajectory(EntityPlayerSP player, WorldClient world,
                                                boolean startsFallFlying, int glideTicks) {
        List<FallSample> samples = new ArrayList<FallSample>();
        if (this.movementSnapshot == null) {
            return samples;
        }
        BlockPathPlanner simulation = new BlockPathPlanner(player, player, world, this.movementSnapshot);
        simulation.applySnapshot(this.movementSnapshot);
        simulation.restoreSnapshotInput();
        EntityPlayer simulatedPlayer = simulation.getSimulatedPlayer();
        // 显式继承真实玩家的当前速度矢量（elytra 滑翔速度）：模拟玩家从"现在
        // 脱鞘翅停飞"那一刻起应携带与真实玩家一致的 motionX/motionY/motionZ
        // 进入自由落体，而不是依赖快照可能过期的值或从零速度开始——高速掠过
        // 目标时，初始速度矢量直接决定预测落点是否够得着（高速掠过真实轨迹能
        // 命中，若模拟从低速起步则会误判"够不着"而不脱鞘翅）。
        simulatedPlayer.h(Vec3.create(player.t(), player.q(), player.T()));
        float fallDistance = player.getFallDistance();
        double previousY = simulatedPlayer.N();
        boolean glideEnded = !startsFallFlying;
        for (int tick = 1; tick <= PREDICTION_TICKS; ++tick) {
            if (!glideEnded && simulatedPlayer.Y$src$Z$154rldp()) {
                if (simulatedPlayer.q() > -0.5 && fallDistance > 1.0f) {
                    fallDistance = 1.0f;
                }
                if (tick > glideTicks) {
                    simulatedPlayer.k(7, false);
                    glideEnded = true;
                }
            }
            simulation.simulateTick();
            double currentY = simulatedPlayer.N();
            double verticalDelta = currentY - previousY;
            if (verticalDelta < 0.0) {
                fallDistance += (float)(-verticalDelta);
            }
            previousY = currentY;
            FallSample sample = new FallSample(tick, simulatedPlayer.z(), currentY + simulatedPlayer.X(),
                    simulatedPlayer.h(), fallDistance, simulatedPlayer.b$src$Z$fqlxe4(),
                    simulatedPlayer.Y$src$Z$154rldp());
            samples.add(sample);
            if (sample.onGround) {
                break;
            }
        }
        return samples;
    }

    private ImpactPrediction findImpact(List<FallSample> samples, EntityLivingBase target, double reach,
                                        double reachMargin, boolean requireSmashFallDistance) {
        ImpactPrediction result = new ImpactPrediction();
        AxisAlignedBB bounds = target.u$src$Lgg_vape_wrapper_impl_AxisAlignedBB_$kogbsu();
        double motionX = target.t();
        double motionY = target.q();
        double motionZ = target.T();
        boolean targetOnGround = target.b$src$Z$fqlxe4();
        for (FallSample sample : samples) {
            double horizontalTicks = Math.min(sample.tick, 5);
            double offsetX = motionX * horizontalTicks;
            double offsetZ = motionZ * horizontalTicks;
            double offsetY = targetOnGround ? 0.0 : motionY * Math.min(sample.tick, 3);
            double distance = distanceToTarget(sample.x, sample.y, sample.z, target, offsetX, offsetY, offsetZ);
            if (sample.onGround || sample.fallFlying || distance > reach * reachMargin
                    || requireSmashFallDistance && sample.fallDistance <= 1.5f) {
                // fallDistance > 1.5 门槛（mace smash）只对"已在自由落体"的瞄准
                // 预测保留；脱鞘翅预测（requireSmashFallDistance=false）放宽——
                // 脱鞘翅的目的正是开始累积 fallDistance，滑翔钳制后前几 tick 往往
                // 还不到 1.5（且 MAX_AIM_IMPACT_TICK=6 很短），按旧门槛会直接跳过
                // 有效采样点 → prediction.valid=false → 永不脱鞘翅。重锤门槛由
                // 攻击判定（updateAutoAttack 的 getFallDistance() > 1.5f）把关。
                continue;
            }
            result.valid = true;
            result.tick = sample.tick;
            result.sourceX = sample.x;
            result.sourceY = sample.y;
            result.sourceZ = sample.z;
            result.impactDistance = distance;
            result.fallDistanceAtImpact = sample.fallDistance;
            double minY = bounds.getMinY() + offsetY;
            double maxY = bounds.getMaxY() + offsetY;
            result.aimX = (bounds.getMinX() + bounds.getMaxX()) * 0.5 + offsetX;
            result.aimY = minY + (maxY - minY) * 0.75;
            result.aimZ = (bounds.getMinZ() + bounds.getMaxZ()) * 0.5 + offsetZ;
            break;
        }
        return result;
    }

    private boolean updateElytraState(EntityPlayerSP player, WorldClient world) {
        boolean enabled = this.autoUnequipElytra.getEffectiveValue().booleanValue();
        boolean fallFlying = player.Y$src$Z$154rldp();
        // 新滑翔飞行判定：fallFlying 上升沿。旧版带 !swappedItemWasElytra 条件，
        // 若上次飞行脱鞘翅后重装被卡（canOperate/屏幕遮挡等）导致残留 true，
        // 本次起飞不会重置状态机，之后被 swappedItemWasElytra 分支拦截 →
        // 本次飞行永不脱鞘翅（实测间歇性"一半不触发"的机制之一）。
        // 唯一不重置的例外：正处于脱鞘翅后的有效重装窗口（WAITING_FOR_GLIDE_END
        // 压 flag 期间 MC 短暂重置 flag、或已触发重装等待执行）——此时上升沿
        // 是状态机自身的 flag 抖动/重装窗口，不是用户新起飞。
        if (fallFlying && !this.wasFallFlying
                && this.elytraFlightState != ElytraFlightState.WAITING_FOR_GLIDE_END
                && !this.shouldReequipElytra) {
            // 新一次滑翔飞行开始：清除上一飞行的脱鞘翅/重装状态，重置状态机——
            // 否则上次 ABORTED 或未重装的残留会让本次滑翔也永不尝试。
            this.swappedItemWasElytra = false;
            this.shouldReequipElytra = false;
            this.reequipDelay = 0;
            this.unequipArmorSlot = -1;
            this.smashAttackPerformed = false;
            this.elytraFlightState = ElytraFlightState.IDLE;
            this.elytraFlightStateTicks = 0;
            this.unequipAimTarget = null;
            this.unequipRequested = false;
        }
        this.wasFallFlying = fallFlying;
        if (!enabled) {
            this.restoreArmorSlot(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6());
            this.resetElytraFlightState(true);
            return fallFlying;
        }

        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        if (this.isArmorSwapActive(false)) {
            return this.updateElytraUnequipSwap(player, inventory);
        }

        // === 容器对账：以胸甲槽真实内容为唯一权威 ===
        // 脱鞘翅状态不应依赖 swappedItemWasElytra/shouldReequipElytra 布尔标志的
        // 时序：连续滑翔没有 fallFlying 上升沿，上一轮重装被中断的残留标志会
        // 死锁整次飞行（风弹场景能脱正是因为被炸制造了上升沿顺带清了标志）。
        // 每 tick 用容器真实内容纠正标志——等价于给持续滑翔补上"上升沿"。
        ItemStack chestSlot = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(6).getStack();
        boolean chestIsElytra = !chestSlot.isNull() && this.isElytra(chestSlot);
        if (chestIsElytra) {
            // 胸甲槽是鞘翅 = 物理上"穿着鞘翅"（能滑翔必然如此）。任何"已脱/待
            // 重装"标志都是残留：重装实际已完成但流程被中断（标志未清）。
            if (this.swappedItemWasElytra) {
                this.swappedItemWasElytra = false;
                this.smashAttackPerformed = false;
            }
            if (this.shouldReequipElytra) {
                // 玩家已穿回鞘翅：重装窗口结束（无论重装是否按流程执行完），
                // 允许本轮重新脱。
                this.shouldReequipElytra = false;
                this.reequipDelay = 0;
            }
            if (this.elytraFlightState == ElytraFlightState.WAITING_FOR_GLIDE_END) {
                // 脱鞘翅后胸甲槽仍是鞘翅：窗口点击没被服务端接受（脱失败）。
                // 短窗口内可能是容器缓存滞后（点击刚发出回执未到）；超过正常
                // 窗口仍未变，说明确实脱失败——复位 IDLE、恢复滑翔、允许重试，
                // 避免"WAITING 永久压制 + 胸甲还是鞘翅"的第二个死锁。
                if (++this.elytraFlightStateTicks > 8) {
                    this.elytraFlightState = ElytraFlightState.IDLE;
                    this.elytraFlightStateTicks = 0;
                    this.unequipAimTarget = null;
                    Vape.debugLog("[AutoMace] reconcile WAITING timeout chest=elytra reset IDLE");
                }
            }
        } else {
            // 胸甲槽不是鞘翅 = 已脱下。清除"还没脱"的残留。
            if (!this.swappedItemWasElytra) {
                this.swappedItemWasElytra = true;
            }
        }

        // 脱鞘翅前提 skip 检查（只保留与"脱下鞘翅"真正相关的必要检查）。
        // 不检查 canOperate/isInputEnabled——纯容器点击与手持/输入无关。
        String skip = null;
        if (world.isNull()) {
            skip = "worldNull";
        } else if (this.movementSnapshot == null) {
            skip = "snapshotNull";
        } else if (!this.hasMaceInHotbar()) {
            skip = "noMaceInHotbar";
        }
        if (skip != null) {
            Vape.debugLog("[AutoMace] elytra skip=" + skip);
            return true;
        }
        if (this.reequipGraceTicks > 0) {
            // 重装鞘翅后的缓存同步冷却：窗口点击后客户端容器可能短暂显示
            // 旧状态（实测重装后立即起飞会误判"胸甲槽不是鞘翅"），等几 tick
            // 再允许触发脱鞘翅，避免误判与重复点击。
            --this.reequipGraceTicks;
            return true;
        }

        // 消费脱鞘翅请求：aim 在滑翔中锁定目标即置请求（"触发转头的那一刻
        // 脱鞘翅"）。玩家松开跳跃停飞（fallFlying=false 但仍在空中）也应继续
        // 脱鞘翅流程——旧版 !fallFlying 分支在消费前 return 并 reset 清请求，
        // 滑翔被 MC 重置 flag 或玩家停飞的一瞬请求被吞（用户反馈"转头了没脱"）。
        // 真落地（onGround）站立脱鞘翅无意义：请求作废，下次起飞重新请求。
        if (this.unequipRequested) {
            if (player.b$src$Z$fqlxe4()) {
                this.unequipRequested = false;
            } else {
                // 低空不脱：模拟"现在停飞下落"，不足 9 tick 就落地 → 蓄力不足
                // （攻击门槛已补偿服务端滞后，客户端 fallD > 2.5 才发攻击，约
                // 8 tick 下落；9 tick 内落地意味着落地前达不到门槛，脱了也打不出、
                // 白摔——日志实证贴脸低空 dist=1~3 滑翔 request→unequip 后
                // fallD 从 0.02 起跳、2~3 tick 即 landed）。aim 每 tick 都重新
                // 请求，玩家升高（样本数足够）后自然重试。
                List<FallSample> lowAltSamples = this.simulateTrajectory(player, world, true, 0);
                if (lowAltSamples.size() < 9) {
                    this.unequipRequested = false;
                } else {
                    ItemStack chestCache = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(6).getStack();
                    if (!chestCache.isNull() && this.isElytra(chestCache)) {
                        this.unequipRequested = false;
                        this.unequipElytra(player, inventory);
                        return true;
                    }
                    // 容器缓存滞后（胸甲槽看着不是鞘翅）：保留请求，下 tick 重试，
                    // 一次性消费失败会把请求吞掉、本次飞行转头却永不脱。
                    if (--this.unequipRequestRetries <= 0) {
                        // 连续多 tick 缓存都看不到鞘翅：可能玩家实际已手动换装/脱出
                        // 滑翔状态，放弃请求避免无限重试。
                        this.unequipRequested = false;
                    }
                }
            }
        }

        if (!fallFlying) {
            this.updateBounceReequip(player);
            if (this.unequipRequested) {
                // 请求未消费（空中停飞/flag 抖动但未落地，缓存滞后或消费点在
                // 上一 tick 刚请求）：保留请求至下一 tick 消费或玩家重飞。
                // 真落地时消费点已清请求，不会带请求走到这里。
                return false;
            }
            this.resetElytraFlightState(false);
            return false;
        }
        if (this.elytraFlightState == ElytraFlightState.WAITING_FOR_GLIDE_END) {
            // 鞘翅已脱下，等待滑翔真正结束：玩家若仍按住跳跃键，MC 每 tick
            // 会把 fallFlying 重新置 true，导致（旧版）反复重复发窗口点击、
            // 服务端来回交换、永远脱不掉。这里持续压制停飞标志，直到真正落地
            // （fallFlying=false 走上方 !fallFlying 分支）。
            // 只在进入 WAITING 的最初几 tick 强制清 flag（等窗口点击生效、容器
            // 同步到位——此期间 MC 仍可能因"胸甲槽还是鞘翅 + 按住空格"重新
            // 滑翔）；之后不再每 tick 清：脱成功后胸甲槽已无鞘翅，MC 物理上
            // 不会重新滑翔，持续清 flag 只是多余的本地移动包干扰（服务端据此
            // 推断停飞与 fallD 累积）。
            if (this.elytraExitFlagClearTicks > 0) {
                player.k(7, false);
                --this.elytraExitFlagClearTicks;
            }
            return true;
        }
        if (this.swappedItemWasElytra) {
            // 本次飞行已脱过鞘翅、等待攻击后重装：不再重复脱，避免
            // 每 tick 重发窗口点击导致鞘翅/盔甲在服务端来回交换。
            // 残留自愈：若重装实际已完成（胸甲槽是鞘翅、重装流程未在跑——
            // 玩家手动穿回/上一轮重装成功但标志未清），清标志允许本轮重新脱，
            // 否则残留 true 会让整次飞行永不脱（连续滑翔不触发上升沿时无解）。
            ItemStack chest = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(6).getStack();
            if (!chest.isNull() && this.isElytra(chest) && !this.shouldReequipElytra) {
                this.swappedItemWasElytra = false;
                this.smashAttackPerformed = false;
            } else {
                return true;
            }
        }

        // 攻击前脱下（预测兜底路径）：预测下落轨迹能在 mace reach 内到达某个
        // 有效目标（下落距离超过 smash 门槛 1.5，即"predicted fall can reach a
        // mace target"）时立即脱鞘翅。独立于瞄准/攻击的目标判定——不要求进入
        // Aim range、不要求准星命中。此路径作为 aim 触发（aimRange 内）的兜底：
        // 目标在 aimRange 外但滑翔预测可达时仍能触发脱鞘翅。
        EntityLivingBase unequipTarget = this.findUnequipTargetByPrediction(player, world);
        if (unequipTarget == null) {
            return true;
        }

        // 脱鞘翅（窗口点击，不经过右键交互、不依赖准星）：
        // 背包里有盔甲 → 交换（鞘翅到盔甲原槽位，盔甲穿到胸甲槽）；
        // 背包里没有盔甲 → 只把鞘翅移到背包/物品栏空槽。
        this.unequipElytra(player, inventory);
        return true;
    }

    /**
     * 脱下鞘翅。窗口点击路径：
     * - 背包有盔甲：三步交换——拾起胸甲槽(6)鞘翅 → 放到盔甲原槽位(同时拾起盔甲)
     *   → 放到胸甲槽(6)。盔甲立即穿上，鞘翅留在盔甲原槽位，re-unequip 时反向换回。
     * - 背包无盔甲：两步——拾起鞘翅 → 放到主背包/热栏空槽（全满才用手持槽交换）。
     * 完成后强制客户端停飞（flag 7），自由落体立即开始累积 fallDistance。
     */
    private void unequipElytra(EntityPlayerSP player, InventoryPlayer inventory) {
        if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().isNull()) {
            return;
        }
        ItemStack chestArmor = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(6).getStack();
        if (chestArmor.isNull() || !this.isElytra(chestArmor)) {
            // 胸甲槽当前不是鞘翅：可能是（a）鞘翅已脱（本方法不会在此路径被
            // 调用——swappedItemWasElytra 拦截）或（b）窗口点击后客户端容器
            // 缓存滞后，服务端回执未到仍显示旧状态（重装后立即起飞实测会
            // 误判 ABORTED 废掉整个飞行）。一律不设 ABORTED、直接返回，
            // 下一 tick 缓存同步后再尝试；开销仅一次容器读。
            return;
        }
        int windowId = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getWindowId();
        int armorSlot = this.findArmorSlotInContainer(player);
        if (armorSlot >= 0) {
            // 有盔甲：鞘翅(6) ↔ 盔甲(armorSlot) 交换
            Minecraft.playerController().O(windowId, 6, 0, 0, player);
            Minecraft.playerController().O(windowId, armorSlot, 0, 0, player);
            Minecraft.playerController().O(windowId, 6, 0, 0, player);
            this.unequipArmorSlot = armorSlot;
        } else {
            // 无盔甲：只脱鞘翅，放到主背包(9-35) → 热栏(36-44) 空槽 → 全满才手持槽
            int destSlot = -1;
            for (int s = 9; s <= 35 && destSlot < 0; ++s) {
                if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(s).getStack().isNull()) {
                    destSlot = s;
                }
            }
            for (int s = 36; s <= 44 && destSlot < 0; ++s) {
                if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(s).getStack().isNull()) {
                    destSlot = s;
                }
            }
            if (destSlot < 0) {
                destSlot = 36 + inventory.v();
            }
            Minecraft.playerController().O(windowId, 6, 0, 0, player);
            Minecraft.playerController().O(windowId, destSlot, 0, 0, player);
            this.unequipArmorSlot = -1;
        }
        this.swappedItemWasElytra = true;
        this.shouldReequipElytra = false;
        this.elytraFlightState = ElytraFlightState.WAITING_FOR_GLIDE_END;
        this.elytraFlightStateTicks = 0;
        this.elytraExitFlagClearTicks = 3;
        // 强制客户端停止 fallFlying：窗口点击后 MC 需 1 tick 检测胸甲槽不再是
        // 鞘翅才会停飞，期间自动攻击被 fallFlying 拦截（日志实证脱了也打不出
        // 重锤）。直接清 flag 7 立即停飞，自由落体开始累积 fallDistance。
        player.k(7, false);
        Vape.debugLog("[AutoMace] unequip elytra armorSlot=" + armorSlot);
    }

    /**
     * 热栏没有胸甲时：通过窗口点击把胸甲槽(6)的鞘翅移到当前手持热栏槽，
     * 使滑翔状态终止（MC 检测到胸甲槽不再是鞘翅即停飞）。
     * 仅在胸甲槽确实穿着鞘翅时执行。
     */
    private boolean unequipElytraDirect(EntityPlayerSP player, InventoryPlayer inventory) {
        if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().isNull()) {
            return false;
        }
        ItemStack chestArmor = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(6).getStack();
        if (chestArmor.isNull() || !this.isElytra(chestArmor)) {
            // 与 unequipElytra 相同：缓存滞后时误判"胸甲槽不是鞘翅"会设
            // ABORTED 废掉整个飞行。不设状态、直接返回，下 tick 再试。
            return false;
        }
        int windowId = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getWindowId();
        // 鞘翅放置优先级：① 主背包（容器槽 9-35）→ ② 热栏/物品栏（36-44）→
        // ③ 全满才与手持槽交换。优先空槽放入可避免光标残留。
        int destSlot = -1;
        for (int s = 9; s <= 35 && destSlot < 0; ++s) {
            if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(s).getStack().isNull()) {
                destSlot = s;
            }
        }
        for (int s = 36; s <= 44 && destSlot < 0; ++s) {
            if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(s).getStack().isNull()) {
                destSlot = s;
            }
        }
        if (destSlot < 0) {
            destSlot = 36 + inventory.v();
        }
        Minecraft.playerController().O(windowId, 6, 0, 0, player);
        Minecraft.playerController().O(windowId, destSlot, 0, 0, player);
        this.armorSwapTicks = 0;
        this.swappedItemWasElytra = true;
        this.shouldReequipElytra = false;
        this.elytraFlightState = ElytraFlightState.WAITING_FOR_GLIDE_END;
        this.elytraFlightStateTicks = 0;
        this.elytraExitFlagClearTicks = 3;
        this.armorOriginalSlot = -1;
        this.armorTargetSlot = -1;
        this.armorSwapToElytra = false;
        // 强制客户端停止 fallFlying：窗口点击后 MC 需 1 tick 检测胸甲槽不再是
        // 鞘翅才会停飞，期间自动攻击被 fallFlying 拦截（日志实证脱了也打不出
        // 重锤）。直接清 flag 7 立即停飞，自由落体开始累积 fallDistance。
        player.k(7, false);
        return true;
    }

    private boolean updateElytraUnequipSwap(EntityPlayerSP player, InventoryPlayer inventory) {
        if (inventory.v() != this.armorTargetSlot) {
            this.clearArmorSwapState();
            this.elytraFlightState = this.swappedItemWasElytra
                    ? ElytraFlightState.WAITING_FOR_GLIDE_END
                    : ElytraFlightState.ABORTED_THIS_FLIGHT;
            this.elytraFlightStateTicks = 0;
            Vape.debugLog("[AutoMace] swap slotLost expected=" + this.armorTargetSlot);
            return player.Y$src$Z$154rldp();
        }
        if (!player.Y$src$Z$154rldp() && this.armorSwapTicks < 2) {
            this.restoreArmorSlot(inventory);
            this.resetElytraFlightState(false);
            Vape.debugLog("[AutoMace] swap glideEnded restore");
            return false;
        }
        int ticks = this.armorSwapTicks + 1;
        if (ticks == 2) {
            if (player.l$src$Z$1io4duf() || SharedModuleControlClaims.rightClickUse.isClaimed()) {
                Vape.debugLog("[AutoMace] swap rclickBlocked usingItem=" + player.l$src$Z$1io4duf());
                return true;
            }
            this.useSelectedItem();
            this.swappedItemWasElytra = this.isElytra(inventory.c(this.armorTargetSlot));
            // 重装时机由攻击成功（onPostAttack）决定，脱鞘翅本身不触发重装
            this.shouldReequipElytra = false;
            Vape.debugLog("[AutoMace] swap rclick ticks=" + ticks + " swappedElytra=" + this.swappedItemWasElytra);
        }
        this.armorSwapTicks = ticks;
        if (this.armorSwapTicks < 4) {
            return true;
        }
        this.restoreArmorSlot(inventory);
        this.elytraFlightState = this.swappedItemWasElytra
                ? ElytraFlightState.WAITING_FOR_GLIDE_END
                : ElytraFlightState.ABORTED_THIS_FLIGHT;
        this.elytraFlightStateTicks = 0;
        Vape.debugLog("[AutoMace] swap complete swappedElytra=" + this.swappedItemWasElytra
                + " state=" + this.elytraFlightState);
        if (!player.Y$src$Z$154rldp()) {
            this.resetElytraFlightState(false);
            return false;
        }
        return true;
    }

    private void updateBounceReequip(EntityPlayerSP player) {
        if (!this.swappedItemWasElytra) {
            return;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        if (!this.reEquipElytra.getEffectiveValue().booleanValue()) {
            this.swappedItemWasElytra = false;
            this.shouldReequipElytra = false;
            return;
        }
        // 触发条件：① 重锤攻击已发出（自动攻击时摔落距离 > 1.5，即 mace smash）
        // 且检测到 mace bounce 的向上运动（重锤把目标击飞、玩家向上反弹）——
        // 官方语义 "Puts the Elytra back on after AutoMace detects the upward
        // movement from a mace bounce"，反弹即可空中恢复滑翔，不必等落地；
        // ② 本次滑翔已落地（没打中也恢复，避免一直空着）。
        // 普通攻击命中（onPostAttack）不触发重装——攻击与重装不同时发生，
        // 否则会在自由落体蓄力期就把鞘翅穿回，重锤永不发生（旧版循环根因）。
        // 向上运动仅在已被重锤攻击后认作 bounce，防止脱鞘翅后风弹等
        // 其它上升也误触发。
        if (!this.shouldReequipElytra) {
            boolean smashAttacked = this.smashAttackPerformed;
            boolean upwardBounce = smashAttacked && player.q() > 0.0;
            boolean landed = player.b$src$Z$fqlxe4();
            if (upwardBounce || landed) {
                this.shouldReequipElytra = true;
                this.reequipDelay = REEQUIP_DELAY_TICKS;
                this.reequipRetries = 0;
                Vape.debugLog("[AutoMace] reequip trigger " + (upwardBounce ? "bounce" : "landed")
                        + " fallD=" + player.getFallDistance() + " smash=" + smashAttacked
                        + " vy=" + player.q());
            }
        }
        if (!this.shouldReequipElytra) {
            return;
        }
        // 只挡打开的界面（窗口点击会打到界面容器）。不检查 canOperate——
        // limitToItems 白名单针对手持物品，攻击后玩家切换手持（风弹/别的武器）
        // 会让 canOperate=false → 重装永远卡住 → 一直穿盔甲、无法装回鞘翅
        // （用户反馈"攻击后恢复视角期间一直保持盔甲，无法装备鞘翅"）。重装是
        // 纯容器点击，与手持物品/输入状态无关。
        if (Minecraft.currentScreen().isNotNull()) {
            return;
        }
        if (this.reequipDelay > 0) {
            --this.reequipDelay;
            return;
        }
        int windowId = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getWindowId();
        if (this.unequipArmorSlot >= 0) {
            // 有盔甲：反向交换——拾起胸甲槽(6)盔甲 → 放回原槽位(同时拾起鞘翅)
            // → 放到胸甲槽(6)。盔甲回原槽位，鞘翅穿回。
            Minecraft.playerController().O(windowId, 6, 0, 0, player);
            Minecraft.playerController().O(windowId, this.unequipArmorSlot, 0, 0, player);
            Minecraft.playerController().O(windowId, 6, 0, 0, player);
            Vape.debugLog("[AutoMace] re-equip elytra swap back armorSlot=" + this.unequipArmorSlot);
        } else {
            int elytraSlot = this.findElytraSlot(player, inventory);
            if (elytraSlot < 0) {
                // 窗口点击的鞘翅可能因服务端同步延迟尚未就位：稍后重试几次
                if (++this.reequipRetries > 10) {
                    this.shouldReequipElytra = false;
                    this.swappedItemWasElytra = false;
                    this.unequipAimTarget = null;
                    this.reequipRetries = 0;
                } else {
                    this.reequipDelay = 2;
                }
                return;
            }
            // Silent equip：参考 AutoTotem 的 silent open，直接对玩家容器发送
            // 拾取/放置窗口点击（不打开物品栏、不切换手持槽），把容器中的鞘翅
            // 装备到胸甲槽（容器槽 6）。
            Minecraft.playerController().O(windowId, elytraSlot, 0, 0, player);
            Minecraft.playerController().O(windowId, 6, 0, 0, player);
            Vape.debugLog("[AutoMace] re-equip elytra from container slot=" + elytraSlot);
        }
        this.shouldReequipElytra = false;
        this.swappedItemWasElytra = false;
        this.smashAttackPerformed = false;
        this.unequipArmorSlot = -1;
        this.reequipRetries = 0;
        // 重装完成，脱鞘翅目标使命结束，aim 回退常规最近目标
        this.unequipAimTarget = null;
        // 重装完成后的缓存同步冷却：窗口点击后客户端容器短暂显示旧状态，
        // 玩家若立即起飞，unequip 会误判"胸甲槽不是鞘翅"。冷却几 tick
        // 等回执同步，再允许下一轮脱鞘翅。
        this.reequipGraceTicks = 6;
    }

    private void useSelectedItem() {
        SharedModuleControlClaims.rightClickUse.blockUse();
        try {
            Minecraft.F$src$V$aoypvc();
        } finally {
            SharedModuleControlClaims.rightClickUse.clearClaimed();
        }
    }

    private boolean isArmorSwapActive(boolean toElytra) {
        return this.armorTargetSlot >= 0 && this.armorSwapToElytra == toElytra;
    }

    private void restoreArmorSlot(InventoryPlayer inventory) {
        if (this.armorTargetSlot >= 0 && this.armorOriginalSlot >= 0 && inventory.v() == this.armorTargetSlot) {
            inventory.g(this.armorOriginalSlot);
        }
        this.clearArmorSwapState();
    }

    private void clearArmorSwapState() {
        this.armorSwapTicks = 0;
        this.armorSwapToElytra = false;
        this.armorTargetSlot = -1;
        this.armorOriginalSlot = -1;
    }

    private void resetElytraFlightState(boolean clearReequip) {
        this.elytraFlightState = ElytraFlightState.IDLE;
        this.elytraFlightStateTicks = 0;
        this.elytraExitFlagClearTicks = 0;
        this.unequipAimTarget = null;
        this.unequipRequested = false;
        if (clearReequip) {
            this.swappedItemWasElytra = false;
            this.shouldReequipElytra = false;
            this.reequipDelay = 0;
            this.reequipRetries = 0;
            this.reequipGraceTicks = 0;
            this.unequipArmorSlot = -1;
            this.smashAttackPerformed = false;
            this.clearArmorSwapState();
        }
    }

    /**
     * 在玩家容器（主背包 9-35 + 热栏 36-44）中查找胸甲，返回容器槽位。
     * 用物品描述 ID 判定（item.minecraft.xxx_chestplate），鞘翅不视为盔甲。
     * 与 unequipElytra 配合：鞘翅放到该槽位，re-unequip 时从该槽位换回。
     */
    private int findArmorSlotInContainer(EntityPlayerSP player) {
        if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().isNull()) {
            return -1;
        }
        int bestSlot = -1;
        double bestScore = -1.0;
        for (int slot = 9; slot <= 44; ++slot) {
            ItemStack stack = player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(slot).getStack();
            if (stack.isNull() || stack.r() || stack.getItem().isNull() || this.isElytra(stack)) {
                continue;
            }
            String name = stack.getItem().A();
            if (name == null || !name.contains("chestplate")) {
                continue;
            }
            double score = ItemStackScoreUtil.L(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private int findBestChestplateSlot(InventoryPlayer inventory) {
        int bestSlot = -1;
        double bestScore = -1.0;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inventory.c(slot);
            if (stack.isNull() || stack.r() || stack.getItem().isNull() || this.isElytra(stack)) {
                continue;
            }
            // 用物品描述 ID 判定胸甲（item.minecraft.xxx_chestplate），
            // 不依赖 Equippable/DataComponents 槽位映射链路。
            String name = stack.getItem().A();
            if (name == null || !name.contains("chestplate")) {
                continue;
            }
            double score = ItemStackScoreUtil.L(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * 在玩家容器（主背包 9-35 + 热栏 36-44）中查找鞘翅，返回容器槽位。
     * 脱鞘翅时鞘翅优先放入主背包空槽（见 unequipElytraDirect 的放置优先级），
     * 因此必须扫描整个容器而不是只扫热栏——旧版只扫热栏导致重装永远找不到鞘翅。
     */
    private int findElytraSlot(EntityPlayerSP player, InventoryPlayer inventory) {
        if (player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().isNull()) {
            return -1;
        }
        for (int slot = 9; slot <= 44; ++slot) {
            if (this.isElytra(player.F$src$Lgg_vape_wrapper_impl_Container_$152y6lm().getSlot(slot).getStack())) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isElytra(ItemStack stack) {
        if (stack == null || stack.isNull() || stack.getItem().isNull()) {
            return false;
        }
        String name = stack.getItem().A();
        return name != null && name.contains("elytra");
    }

    private EntityLivingBase findNearestTarget(EntityPlayerSP player, WorldClient world) {
        return this.findNearestTarget(player, world, this.aimRange.getValue());
    }

    private EntityLivingBase findNearestTarget(EntityPlayerSP player, WorldClient world, double range) {
        EntityLivingBase best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Object entityObject : new ArrayList<Object>(world.z())) {
            Entity entity = new Entity(entityObject);
            if (!entity.isInstance(MappedClasses.zm)) {
                continue;
            }
            EntityLivingBase candidate = new EntityLivingBase(entityObject);
            double deltaX = candidate.z() - player.z();
            double deltaZ = candidate.h() - player.h();
            double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            double distance = player.getDistanceToEntity(candidate);
            if (!this.isValidTarget(player, candidate) || horizontalDistance > range || distance >= bestDistance) {
                continue;
            }
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }

    /**
     * 预测判定：模拟"现在脱鞘翅停飞"后的下落轨迹（startsFallFlying=true 表示
     * 当前在滑翔，glideTicks=0 表示第 1 tick 即停飞自由落体），若轨迹在预测
     * 窗口内存在某个采样点能命中（距离 ≤ mace reach；不要求采样点 fallDistance
     * 已超 smash 门槛 1.5——脱鞘翅的目的正是开始累积 fallDistance，滑翔钳制
     * 后前几 tick 往往不足 1.5，门槛由攻击判定把关）任一有效目标，则预测下落
     * 可到达该目标——符合官方语义
     * "Equips a chestplate when your predicted fall can reach a mace target"。
     * 独立于瞄准/攻击的目标判定（不要求准星命中、不受 Aim range 限制），
     * 从可到达的目标中选取 3D 距离最近的一个。轨迹模拟只跑一次，各目标
     * 只做轻量的 findImpact 判定。
     */
    private EntityLivingBase findUnequipTargetByPrediction(EntityPlayerSP player, WorldClient world) {
        if (world.isNull() || this.movementSnapshot == null) {
            return null;
        }
        List<FallSample> samples = this.simulateTrajectory(player, world, true, 0);
        if (samples.isEmpty()) {
            return null;
        }
        // 低空不脱：模拟不足 9 tick 就落地，脱鞘翅后蓄力时间不够——攻击门槛
        // 已补偿服务端滞后（客户端 fallD > 2.5 才发攻击，约 8 tick 下落），
        // 9 tick 内落地意味着落地前达不到攻击门槛，脱了也打不出、白摔。
        if (samples.size() < 9) {
            return null;
        }
        double reach = this.getMaceReach();
        EntityLivingBase best = null;
        // 按"命中质量"打分，而不是"当前距离最近"——最近的目标可能是正下方
        // （水平速度会让玩家飞过去根本打不到），稍远但轨迹正好穿过的目标
        // 才是真正能打中的：
        //   + reach 余量（离 reach 上限越远越安全）
        //   + fallDistance 裕度（封顶 5，避免高空目标无限加分）
        //   - tick（越早命中越不容易被目标走位/风弹干扰）
        double reachMargin = reach * UNEQUIP_REACH_MARGIN;
        double bestScore = -Double.MAX_VALUE;
        for (Object entityObject : new ArrayList<Object>(world.z())) {
            Entity entity = new Entity(entityObject);
            if (!entity.isInstance(MappedClasses.zm)) {
                continue;
            }
            EntityLivingBase candidate = new EntityLivingBase(entityObject);
            if (candidate.isNull() || candidate.equals(player)
                    || candidate.w$src$F$15l9epb() <= 0.0f || candidate.C$src$Z$f9kazx()
                    || !this.targetFilter.isValidTarget(candidate)) {
                continue;
            }
            ImpactPrediction prediction = this.findImpact(samples, candidate, reach, UNEQUIP_REACH_MARGIN, false);
            if (!prediction.valid) {
                continue;
            }
            double score = (reachMargin - prediction.impactDistance)
                    + Math.min(prediction.fallDistanceAtImpact - SMASH_FALL_DISTANCE_THRESHOLD, 5.0f) * 0.5
                    - prediction.tick * 0.1;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null && --this.unequipNoTargetLogCooldown <= 0) {
            // 节流日志：确认"滑翔中预测够不着目标 → 不脱鞘翅"的量级。
            // 正常滑翔每 tick 都会走到这里，若每 tick 打日志会刷屏。
            Vape.debugLog("[AutoMace] unequip skip noPredTarget samples=" + samples.size()
                    + " y=" + player.N() + " fallD=" + player.getFallDistance());
            this.unequipNoTargetLogCooldown = 20;
        }
        // 预测选中的目标交给 updateAim 锁定（转头），脱鞘翅与瞄准绑成一体；
        // 预测不过时清掉旧目标，避免残留导致 aim 锁定过期目标。
        this.unequipAimTarget = best;
        return best;
    }

    private boolean isValidTarget(EntityPlayerSP player, EntityLivingBase target) {
        if (target == null || target.isNull() || target.equals(player) || target.C$src$Z$f9kazx()
                || target.w$src$F$15l9epb() <= 0.0f) {
            return false;
        }
        Entity vehicle = player.S$src$Lgg_vape_wrapper_impl_Entity_$dgzs12();
        if (vehicle.isNotNull() && target.equals(vehicle)) {
            return false;
        }
        return this.targetFilter.isValidTarget(target) && target.N() <= player.N() + 1.0;
    }

    private EntityLivingBase getCrosshairTarget() {
        RayTraceResult rayTrace = RotationManager.INSTANCE.getExtendedReachRayTrace();
        if (rayTrace == null || rayTrace.isNull()
                || !rayTrace.getTypeOfHit().equals(RayTraceResult_type.entity())) {
            return null;
        }
        Entity entity = rayTrace.getEntity();
        return this.targetFilter.isValidTarget(entity) ? new EntityLivingBase(entity.getObject()) : null;
    }

    private boolean isCrosshairTarget(int entityId) {
        RayTraceResult rayTrace = RotationManager.INSTANCE.getExtendedReachRayTrace();
        return rayTrace != null && rayTrace.isNotNull() && rayTrace.getEntity().isNotNull()
                && rayTrace.getEntity().S() == entityId;
    }

    private boolean canOperate() {
        if (Minecraft.currentScreen().isNotNull()) {
            return false;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        return player.isNotNull() && (!this.limitToItems.getEffectiveValue().booleanValue()
                || this.allowedItems.isValid(player.getHeldItemHand(), false));
    }

    private boolean canUseAttackInput() {
        return this.canOperate() && ClientSettings.INSTANCE.isInputEnabled();
    }

    private boolean isFalling(EntityPlayerSP player) {
        return !player.b$src$Z$fqlxe4() && player.N() - player.W() < 0.0;
    }

    /**
     * 预估"攻击包到达服务端时服务端看到的 fallDistance 是否已过 1.5"。
     * 客户端 fallDistance 因 k(7,false) 立即停飞而先累积，服务端要等窗口
     * 点击包 + 容器同步才开始累积——客户端 fallD > 1.5 时服务端可能还没到。
     * 加 SERVER_LAG_FALL_MARGIN 格余量补偿这段滞后。
     */
    private boolean willSmashOnServer(EntityPlayerSP player) {
        return this.isFalling(player)
                && player.getFallDistance() > SMASH_FALL_DISTANCE_THRESHOLD + SERVER_LAG_FALL_MARGIN;
    }

    private float estimatedServerFallDistance(EntityPlayerSP player) {
        return player.getFallDistance() - SERVER_LAG_FALL_MARGIN;
    }

    private boolean shouldStunSlam(EntityLivingBase target) {
        double chance = this.stunSlamChance.getValue();
        if (!this.stunSlam.getEffectiveValue().booleanValue() || chance <= 0.0
                || chance < 100.0 && chance <= Math.random() * 100.0) {
            return false;
        }
        Entity entity = new Entity(target.getObject());
        if (!entity.isInstance(MappedClasses.lG)) {
            return false;
        }
        EntityOtherPlayerMP targetPlayer = new EntityOtherPlayerMP(entity.getObject());
        EnumHand shieldHand = RotationUtil.q(targetPlayer);
        return shieldHand != null && RotationUtil.f(targetPlayer, shieldHand) >= 5.0f;
    }

    private boolean isHeldMaceReady(EntityPlayerSP player, EntityLivingBase target) {
        return this.scoreMace(player.getHeldItemHand(), this.hasConfiguredMaceInHotbar(), target) >= 0.0;
    }

    private int findBestMaceSlot(EntityLivingBase target, boolean excludeSelected) {
        EntityPlayerSP player = Minecraft.thePlayer();
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        int selectedSlot = inventory.v();
        boolean filterByEnchantment = this.hasConfiguredMaceInHotbar();
        int bestSlot = -1;
        double bestScore = -1.0;
        for (int slot = 0; slot < 9; ++slot) {
            if (excludeSelected && slot == selectedSlot) {
                continue;
            }
            double score = this.scoreMace(inventory.c(slot), filterByEnchantment, target);
            if (score < 0.0 || this.isAutomaticSelection() && score <= bestScore) {
                continue;
            }
            bestSlot = slot;
            bestScore = score;
            if (!this.isAutomaticSelection()) {
                return slot;
            }
        }
        return bestSlot;
    }

    private double scoreMace(ItemStack stack, boolean filterByEnchantment, EntityLivingBase target) {
        // smashOnly=true 时只有"服务端也会认账"的时刻才允许选中重锤并攻击：
        // 原版用客户端 fallD（RotationUtil.u），大量"客户端认为能 smash、服务端
        // 没到 1.5"的空锤。isAutoAttackReady 里仍保留 RotationUtil.u 做冷却旁路
        // 无妨——即使它返回 true，这里仍把 mace 判 -1，攻击不会发出。
        if (this.smashOnly.getEffectiveValue().booleanValue()
                && !this.willSmashOnServer(Minecraft.thePlayer())
                || !this.isMace(stack)) {
            return -1.0;
        }
        if (!filterByEnchantment) {
            return 0.0;
        }
        int density = EnchantmentHelper.e("density", stack);
        int breach = EnchantmentHelper.e("breach", stack);
        if (this.isAutomaticSelection()) {
            return this.scoreAutomaticMace(density, breach, target);
        }
        if (this.isDensityType() && density > 0) {
            return 0.0;
        }
        if (this.isBreachType() && breach > 0) {
            return 0.0;
        }
        return -1.0;
    }

    private double scoreAutomaticMace(int density, int breach, EntityLivingBase target) {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.b$src$Z$fqlxe4()) {
            return breach > 0 ? 0.0 : -1.0;
        }
        double score = -1.0;
        if (density > 0) {
            score = this.estimateMaceDamage(player.getFallDistance(), target, density, 0);
        }
        if (breach > 0) {
            score = Math.max(score, this.estimateMaceDamage(player.getFallDistance(), target, 0, breach));
        }
        return score;
    }

    private double estimateMaceDamage(float fallDistance, EntityLivingBase target, int density, int breach) {
        double damage = 6.0 + this.smashBonusDamage(fallDistance) + 0.5 * density * fallDistance;
        if (target == null || target.isNull()) {
            return damage;
        }
        double armor = target.o(MonsterAttributesBridge.L());
        double toughness = target.o(MonsterAttributesBridge.m$src$Lgg_vape_wrapper_impl_Holder_$1lgjxui());
        double effectiveArmor = clamp(armor - damage / (2.0 + toughness / 4.0), armor * 0.2, 20.0);
        double reduction = clamp(effectiveArmor / 25.0 - 0.15 * breach, 0.0, 1.0);
        return damage * (1.0 - reduction);
    }

    private double smashBonusDamage(float fallDistance) {
        if (fallDistance <= 3.0f) {
            return 4.0 * fallDistance;
        }
        if (fallDistance <= 8.0f) {
            return 12.0 + 2.0 * (fallDistance - 3.0f);
        }
        return 22.0 + fallDistance - 8.0f;
    }

    private boolean hasConfiguredMaceInHotbar() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNull()) {
            return false;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        for (int slot = 0; slot < 9; ++slot) {
            if (this.isConfiguredMace(inventory.c(slot))) {
                return true;
            }
        }
        return false;
    }

    private boolean isConfiguredMace(ItemStack stack) {
        if (!this.isMace(stack)) {
            return false;
        }
        if (this.isAutomaticSelection()) {
            return EnchantmentHelper.e("density", stack) > 0 || EnchantmentHelper.e("breach", stack) > 0;
        }
        if (this.isDensityType()) {
            return EnchantmentHelper.e("density", stack) > 0;
        }
        return this.isBreachType() && EnchantmentHelper.e("breach", stack) > 0;
    }

    private boolean hasMaceInHotbar() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNull()) {
            return false;
        }
        InventoryPlayer inventory = player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        for (int slot = 0; slot < 9; ++slot) {
            if (this.isMace(inventory.c(slot))) {
                return true;
            }
        }
        return false;
    }

    private int findAxeSlot(InventoryPlayer inventory) {
        int selectedSlot = inventory.v();
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = inventory.c(slot);
            if (slot != selectedSlot && this.isAxe(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isHoldingAxe(EntityPlayerSP player) {
        return player.isNotNull() && this.isAxe(player.getHeldItemHand());
    }

    private boolean isAxe(ItemStack stack) {
        return stack != null && stack.isNotNull() && !stack.r()
                && stack.getItem().isNotNull() && ItemStackScoreUtil.T(stack.getItem());
    }

    private boolean isMace(ItemStack stack) {
        return stack != null && stack.isNotNull() && stack.getItem().isNotNull()
                && stack.getItem().isInstance(MappedClasses.zx);
    }

    private boolean isAutomaticSelection() {
        return this.maceSelection.getValue().toString().equals(SELECTION_AUTO.toString());
    }

    private boolean isDensityType() {
        return this.maceType.getValue().toString().equals(TYPE_DENSITY.toString());
    }

    private boolean isBreachType() {
        return this.maceType.getValue().toString().equals(TYPE_BREACH.toString());
    }

    private double getMaceReach() {
        Reach reach = Vape.INSTANCE.getModManager().getMod(Reach.class);
        return reach == null ? 3.0 : reach.getReachDistance();
    }

    private static double distanceToTarget(double x, double y, double z, EntityLivingBase target,
                                           double offsetX, double offsetY, double offsetZ) {
        AxisAlignedBB bounds = target.u$src$Lgg_vape_wrapper_impl_AxisAlignedBB_$kogbsu();
        double closestX = clamp(x, bounds.getMinX() + offsetX, bounds.getMaxX() + offsetX);
        double closestY = clamp(y, bounds.getMinY() + offsetY, bounds.getMaxY() + offsetY);
        double closestZ = clamp(z, bounds.getMinZ() + offsetZ, bounds.getMaxZ() + offsetZ);
        double deltaX = x - closestX;
        double deltaY = y - closestY;
        double deltaZ = z - closestZ;
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean requestSyntheticAttack(boolean markInProgress) {
        if (!markInProgress) {
            return AttackKeyController.requestSyntheticAttack(this);
        }
        this.dispatchingSyntheticAttack = true;
        try {
            return AttackKeyController.requestSyntheticAttack(this);
        } finally {
            this.dispatchingSyntheticAttack = false;
        }
    }

    public boolean canHandleMaceAttack() {
        if (!this.stunSlam.getEffectiveValue().booleanValue()) {
            return false;
        }
        EntityPlayerSP player = Minecraft.thePlayer();
        return this.isHoldingAxe(player)
                || player.isNotNull()
                && this.findAxeSlot(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6()) >= 0;
    }

    public boolean isSyntheticAttackInProgress() {
        return this.dispatchingSyntheticAttack;
    }

    public boolean hasReadyMace() {
        return this.findBestMaceSlot(this.getCrosshairTarget(), true) >= 0;
    }

    private void abortWeaponSwap(EntityPlayerSP player) {
        if (player.isNotNull() && this.originalSlot != -1) {
            player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.originalSlot);
        }
        this.originalSlot = -1;
        this.clearWeaponSwapState();
    }

    private void clearWeaponSwapState() {
        this.weaponSwapActive = false;
        this.weaponSwapTicks = 0;
        this.stunSlamFollowupPending = false;
        this.maceSlot = -1;
        this.originalAttackTick = -1;
        this.targetId = -1;
    }

    private void releaseRotation() {
        if (this.rotationController != null) {
            this.rotationController.clearTarget();
            if (RotationManager.INSTANCE.getActiveController() == this.rotationController) {
                RotationManager.INSTANCE.releaseController(this.rotationController);
            }
            this.rotationController = null;
        }
        this.rotationClaim.release(this);
    }

    @Override
    public void onDisable() {
        EntityPlayerSP player = Minecraft.thePlayer();
        if (player.isNotNull()) {
            this.restoreArmorSlot(player.V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6());
        }
        this.releaseRotation();
        this.movementSnapshot = null;
        if (this.releasePending) {
            AttackKeyController.releaseAttackKey();
        }
        this.releasePending = false;
        this.weaponSwapActive = false;
        this.originalSlot = -1;
        this.weaponSwapTicks = 0;
        this.stunSlamFollowupPending = false;
        this.maceSlot = -1;
        this.originalAttackTick = -1;
        this.targetId = -1;
        this.dispatchingSyntheticAttack = false;
        this.wasFallFlying = false;
        this.resetElytraFlightState(true);
    }

    private enum ElytraFlightState {
        IDLE,
        WAITING_FOR_GLIDE_END,
        ABORTED_THIS_FLIGHT
    }

    private static final class FallSample {
        private final int tick;
        private final double x;
        private final double y;
        private final double z;
        private final float fallDistance;
        private final boolean onGround;
        private final boolean fallFlying;

        private FallSample(int tick, double x, double y, double z, float fallDistance,
                           boolean onGround, boolean fallFlying) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.fallDistance = fallDistance;
            this.onGround = onGround;
            this.fallFlying = fallFlying;
        }
    }

    private static final class ImpactPrediction {
        private boolean valid;
        private int tick;
        private double sourceX;
        private double sourceY;
        private double sourceZ;
        private double aimX;
        private double aimY;
        private double aimZ;
        private double impactDistance;           // 命中采样点到目标包围盒的距离
        private float fallDistanceAtImpact;      // 命中采样点的 fallDistance
    }
}