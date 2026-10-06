package gg.vape.module.utility;

import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventPreTick;
import gg.vape.module.UtilityMod;
import gg.vape.module.control.SharedModuleControlClaims;
import gg.vape.rotation.AdaptiveRotationController;
import gg.vape.rotation.FixedRotationController;
import gg.vape.rotation.RotationControlClaim;
import gg.vape.rotation.RotationManager;
import gg.vape.value.BooleanValue;
import gg.vape.value.NumberValue;
import gg.vape.wrapper.impl.InventoryPlayer;
import gg.vape.wrapper.impl.ItemStack;
import gg.vape.wrapper.impl.KeyBinding;
import gg.vape.wrapper.impl.Minecraft;

public class WindCharge
extends UtilityMod {
    public final NumberValue aimSpeed = NumberValue.createWithDescription(this, "Aim speed", "#.#", "", 1.0, 7.0, 10.0,
            "Rotation speed while aiming straight down for the wind charge jump");
    private FixedRotationController rotationController;
    private int state;
    private final RotationControlClaim rotationClaim;
    private int savedSlot = -1;
    private final BooleanValue silentAim = new BooleanValue((Object)this, "Silent aim", true);

    private int findWindChargeSlot() {
        InventoryPlayer inventory = Minecraft.thePlayer().V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6();
        for (int i = 0; i < 9; ++i) {
            ItemStack itemStack = inventory.c(i);
            if (itemStack.isNull() || itemStack.getItem().isNull()) continue;
            String name = itemStack.getItem().A();
            if (name != null && name.contains("wind_charge")) return i;
        }
        return -1;
    }

    private boolean canClaimRotation() {
        return this.rotationClaim.isOwnedBy(this) || this.rotationClaim.acquire(this, this.silentAim.getEffectiveValue());
    }

    @Override
    public void onDisable() {
        this.releaseRotation();
        if (this.savedSlot != -1) {
            Minecraft.gameSettings().O().e();
            Minecraft.thePlayer().V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(this.savedSlot);
        }
        this.savedSlot = -1;
        this.state = 0;
    }

    private void releaseRotation() {
        if (this.rotationController != null) {
            RotationManager.INSTANCE.releaseController(this.rotationController);
            this.rotationClaim.release(this);
        }
    }

    public WindCharge() {
        super("WindCharge", "Automatically uses a wind charge");
        this.rotationClaim = SharedModuleControlClaims.rotation;
        this.addValue(this.aimSpeed, this.silentAim);
        this.rotationClaim.setPriority(this, 6);
    }


    @EventHandler
    public void onTick(EventPreTick eventPreTick) {
        if (this.state == 0) {
            int slot = this.findWindChargeSlot();
            if (slot != -1) {
                Minecraft.thePlayer().V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().g(slot);
                if (!this.canClaimRotation()) {
                    return;
                }
                this.rotationController = this.silentAim.getEffectiveValue() != false ? new AdaptiveRotationController(-999.0f, 90.0f) : new FixedRotationController(-999.0f, 90.0f);
                this.rotationController.setTargetRotation(-999.0f, 90.0f);
                this.rotationController.setSpeed(((Double)this.aimSpeed.getValue()).intValue());
                this.rotationController.setScaleAxesProportionally(false);
                this.rotationController.setLinearAcceleration(true);
                this.rotationController.setTolerance(5.0f);
                RotationManager.INSTANCE.setController(this.rotationController);
                this.state = 1;
            } else {
                this.setEnabled(false, true);
            }
        } else if (this.state == 1) {
            if (this.rotationController != null) {
                if (this.rotationController.isComplete() || RotationManager.INSTANCE.getManagedPitch() > 80.0f) {
                    // 释放风弹：右键一定执行；跳跃只在地面时触发（空中仅借风弹爆风推进）。
                    KeyBinding useItem = Minecraft.gameSettings().b$src$Lgg_vape_wrapper_impl_KeyBinding_$1yi3362();
                    KeyBinding.setKeyBindState(useItem, true);
                    KeyBinding.onTick(useItem);
                    KeyBinding.setKeyBindState(useItem, false);
                    if (eventPreTick.getThePlayer().b$src$Z$fqlxe4()) {
                        // 地面投掷风弹的同一刻同时跳跃：起跳初速度与风弹爆风向上推进
                        // 叠加，推得更高更远。与 useItem 相同的按下+onTick 模式（旧
                        // I()+e() 在同 tick 立即释放，MC 按键事件可能被消费掉导致不跳）。
                        // 跳跃键保持按下至玩家 tick 消费，模块关闭时 onDisable 释放。
                        KeyBinding jump = Minecraft.gameSettings().O();
                        KeyBinding.setKeyBindState(jump, true);
                        KeyBinding.onTick(jump);
                    }
                    this.state = -1;
                }
            } else {
                this.state = -1;
            }
        } else if (this.state == -1) {
            this.setEnabled(false, true);
        }
    }

    @Override
    public void onEnable() {
        int slot = this.findWindChargeSlot();
        if (slot != -1) {
            this.savedSlot = Minecraft.thePlayer().V$src$Lgg_vape_wrapper_impl_InventoryPlayer_$erqak6().v();
            this.state = 0;
        } else {
            this.setEnabled(false, true);
        }
    }
}
