package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Shaders：Entity/Hands/Chest 三组描边、Glow、Smoke、Gradient、Snow、Fade 配置。 */
public final class ShadersModule extends GameModule {
    private final BooleanValue hands = add(new BooleanValue("Hands", true));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue self = add(new BooleanValue("Self", true));
    private final BooleanValue friends = add(new BooleanValue("Friends", true));
    private final BooleanValue crystals = add(new BooleanValue("Crystals", true));
    private final BooleanValue chests = add(new BooleanValue("Chests", true));
    private final BooleanValue creatures = add(new BooleanValue("Creatures", false));
    private final BooleanValue monsters = add(new BooleanValue("Monsters", false));
    private final BooleanValue ambients = add(new BooleanValue("Ambients", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final NumberValue maxRange = add(new NumberValue("Max Range", 64, 16, 256, 1));
    private final ShaderGroup entity = new ShaderGroup("");
    private final ColorValue friendsColor = add(new ColorValue("Friends Color", 0xFF30FF00));
    private final ShaderGroup handsGroup = new ShaderGroup("Hands ");
    private final ShaderGroup chestGroup = new ShaderGroup("Chest ");

    public ShadersModule() {
        super("Shaders", Category.RENDER);
    }

    public String entityMode() {
        return entity.mode.get();
    }

    public int primaryColor() {
        return entity.glowColor.argb();
    }

    public int secondaryColor() {
        return entity.glowColor2.argb();
    }

    public float glowRadius() {
        return entity.glowRadius.asFloat();
    }

    public float exposure() {
        return entity.glowExposure.asFloat();
    }

    public boolean glowGradient() {
        return entity.glowColorMode.is("Gradient");
    }

    public boolean glow() {
        return entity.outlineMode.is("Glow");
    }

    public boolean targetEnabled() {
        return players.get() || hands.get() || chests.get() || crystals.get() || creatures.get()
                || monsters.get() || ambients.get() || others.get();
    }

    public boolean self() {
        return self.get();
    }

    public boolean friends() {
        return friends.get();
    }

    public int friendsColor() {
        return friendsColor.argb();
    }

    public float maxRange() {
        return maxRange.asFloat();
    }

    public String handsMode() {
        return handsGroup.mode.get();
    }

    public String chestMode() {
        return chestGroup.mode.get();
    }

    /** 每组设置都进入模块 values，GUI 与帧后处理读取同一份。 */
    private final class ShaderGroup {
        private final ModeValue mode = add(new ModeValue("Mode", "Default", "Default", "Smoke", "Gradient", "Snow", "Fade"));
        private final ModeValue outlineMode = add(new ModeValue("Outline Mode", "Outline", "Outline", "Glow"));
        private final ModeValue glowColorMode = add(new ModeValue("Glow Color Mode", "Solid", "Solid", "Gradient"));
        private final NumberValue glowRadius = add(new NumberValue("Glow Radius", 4, 2, 30, 1));
        private final NumberValue glowExposure = add(new NumberValue("Glow Exposure", 2.2, 0.5, 3.5, 0.1));
        private final ColorValue glowColor = add(new ColorValue("Glow Color", 0xFFFFB7C5));
        private final ColorValue glowColor1 = add(new ColorValue("Glow Color 1", 0xFFFFB7C5));
        private final ColorValue glowColor2 = add(new ColorValue("Glow Color 2", 0xFFFF85A1));
        private final NumberValue glowGradientSpeed = add(new NumberValue("Glow Gradient Speed", 2, 0, 10, 0.1));
        private final NumberValue gradientFactor = add(new NumberValue("Gradient Factor", 2, 0, 10, 0.1));
        private final NumberValue gradient = add(new NumberValue("Gradient", 2, 0, 10, 0.1));
        private final NumberValue gradientAlpha = add(new NumberValue("Gradient Alpha", 170, 0, 255, 1));
        private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 0, 10, 0.5));
        private final NumberValue quality = add(new NumberValue("Quality", 3, 0, 6, 1));
        private final NumberValue smokeOctaves = add(new NumberValue("Smoke Octaves", 10, 1, 20, 1));
        private final NumberValue fillAlpha = add(new NumberValue("Fill Alpha", 170, 0, 255, 1));
        private final BooleanValue smokeGlow = add(new BooleanValue("Smoke Glow", true));
        private final ColorValue outline = add(new ColorValue("Outline", 0xFFFFB7C5));
        private final ColorValue smokeOutline = add(new ColorValue("Smoke Outline", 0xFFFFB7C5));
        private final ColorValue smokeOutline2 = add(new ColorValue("Smoke Outline 2", 0xFFFF85A1));
        private final ColorValue fill = add(new ColorValue("Fill", 0xAAFFB7C5));
        private final ColorValue smokeFill = add(new ColorValue("Smoke Fill", 0xAAFFB7C5));
        private final ColorValue smokeFill2 = add(new ColorValue("Smoke Fill 2", 0xAAFF85A1));

        private ShaderGroup(String prefix) {
            // 字段由外围类初始化；此处仅保留前缀语义供设置名构造使用。
        }
    }
}
