package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Epsilon AntiBot：Tab 列表、实体属性和高级条件组合的可共享 NPC 过滤器。 */
public final class AntiBotModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Default", "Default", "Advanced"));
    private final BooleanValue literalNpc = add(new BooleanValue("Literal NPC", true));
    private final BooleanValue notInTab = add(new BooleanValue("Not In Tab List", false));
    private final BooleanValue invalidGround = add(new BooleanValue("Invalid Ground", false));
    private final NumberValue groundViolations = add(new NumberValue("VL To Consider As Bot", 10, 1, 50, 1));
    private final BooleanValue alwaysRadius = add(new BooleanValue("Always In Radius", true));
    private final NumberValue radius = add(new NumberValue("Always In Radius Range", 20, 5, 30, 1));
    private final BooleanValue age = add(new BooleanValue("Age", false));
    private final NumberValue minimumAge = add(new NumberValue("Minimum", 20, 0, 120, 1));
    private final BooleanValue armor = add(new BooleanValue("Armor", false));
    private final TextValue helmet = add(new TextValue("Helmet", "", 256));
    private final TextValue chestplate = add(new TextValue("Chestplate", "", 256));
    private final TextValue leggings = add(new TextValue("Leggings", "", 256));
    private final TextValue boots = add(new TextValue("Boots", "", 256));
    private final BooleanValue name = add(new BooleanValue("Name", true));
    private final NumberValue nameMin = add(new NumberValue("Name Length Min", 3, 0, 32, 1));
    private final NumberValue nameMax = add(new NumberValue("Name Length Max", 16, 1, 64, 1));
    private final BooleanValue vanillaChars = add(new BooleanValue("Validate Vanilla Chars", false));
    private final BooleanValue cyrillicChars = add(new BooleanValue("Validate Cyrillic Chars", false));
    private final BooleanValue cjkChars = add(new BooleanValue("Validate CJK Chars", false));
    private final BooleanValue noGameMode = add(new BooleanValue("No Game Mode", true));
    private final BooleanValue illegalPitch = add(new BooleanValue("Illegal Pitch", true));
    private final BooleanValue fakeEntityId = add(new BooleanValue("Fake Entity ID", true));
    private final BooleanValue duplicate = add(new BooleanValue("Duplicate", false));
    private final BooleanValue needHit = add(new BooleanValue("Need Hit", false));
    private final BooleanValue illegalHealth = add(new BooleanValue("Illegal Health", false));
    private final BooleanValue swung = add(new BooleanValue("Swung", false));
    private final BooleanValue critted = add(new BooleanValue("Critted", false));
    private final BooleanValue attributes = add(new BooleanValue("Attributes", false));
    private final BooleanValue illegalScale = add(new BooleanValue("Illegal Scale", false));
    private final Set<Integer> hit = new HashSet<Integer>();
    private final Set<Integer> swings = new HashSet<Integer>();
    private final Map<Integer, Integer> groundTicks = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> ages = new HashMap<Integer, Integer>();
    private final Set<String> currentNames = new HashSet<String>();
    private final Set<String> duplicateNames = new HashSet<String>();

    public AntiBotModule() {
        super("Anti Bot", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            int id = bridge().entityId(event.target);
            if (id >= 0) {
                hit.add(id);
            }
        });
        listen(WorldChangeEvent.class, event -> clear());
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null || bridge.player() == null) {
            return;
        }
        currentNames.clear();
        duplicateNames.clear();
        Object entities = bridge.entities();
        if (!(entities instanceof Iterable)) {
            return;
        }
        for (Object entity : (Iterable<?>) entities) {
            int id = bridge.entityId(entity);
            if (id < 0) {
                continue;
            }
            String entityName = bridge.entityName(entity).toLowerCase(java.util.Locale.ROOT);
            if (!currentNames.add(entityName)) {
                duplicateNames.add(entityName);
            }
            ages.put(id, Math.min(1000000, ages.containsKey(id) ? ages.get(id) + 1 : 1));
            Object ground = bridge.readField(entity, dev.nocturne.client.mapping.ClassType.ENTITY, "onGround");
            if (Boolean.FALSE.equals(ground)) {
                groundTicks.put(id, Math.min(1000, groundTicks.containsKey(id) ? groundTicks.get(id) + 1 : 1));
            } else {
                groundTicks.remove(id);
            }
            if (groundTicks.size() > 4096) {
                groundTicks.clear();
            }
            if (ages.size() > 4096) {
                ages.clear();
            }
        }
    }

    @Override
    protected void onStop() {
        clear();
    }

    private void clear() {
        hit.clear();
        swings.clear();
        groundTicks.clear();
        ages.clear();
        currentNames.clear();
        duplicateNames.clear();
    }

    public static boolean isBot(GameBridge bridge, Object entity) {
        NocturneClient client = NocturneClient.get();
        Module module = dev.nocturne.client.module.modules.EpsilonSupport.module("Anti Bot");
        if (!(module instanceof AntiBotModule) || !module.isEnabled()
                || entity == null || entity == bridge.player()) {
            return false;
        }
        return ((AntiBotModule) module).bot(bridge, entity);
    }

    private boolean bot(GameBridge bridge, Object entity) {
        int id = bridge.entityId(entity);
        String entityName = bridge.entityName(entity);
        UUID uuid = bridge.entityUuid(entity);
        boolean tabMissing = uuid == null || !bridge.onlinePlayerUuids().contains(uuid);
        if (mode.is("Default")) {
            return tabMissing;
        }
        if (literalNpc.get() && (entityName.contains("[NPC]") || entityName.contains("CIT-"))) {
            return true;
        }
        if (notInTab.get() && tabMissing || noGameMode.get() && tabMissing) {
            return true;
        }
        if (invalidGround.get() && groundTicks.containsKey(id)
                && groundTicks.get(id) >= groundViolations.asInt()) {
            return true;
        }
        if (alwaysRadius.get() && bridge.distance(bridge.player(), entity) <= radius.get()
                && ages.containsKey(id) && ages.get(id) > 20) {
            return true;
        }
        if (age.get() && ages.containsKey(id) && ages.get(id) < minimumAge.asInt()) {
            return true;
        }
        if (armor.get() && (bridge.itemMatches(bridge.equipmentStack(entity, 3), helmet.get())
                || bridge.itemMatches(bridge.equipmentStack(entity, 2), chestplate.get())
                || bridge.itemMatches(bridge.equipmentStack(entity, 1), leggings.get())
                || bridge.itemMatches(bridge.equipmentStack(entity, 0), boots.get()))) {
            return true;
        }
        int length = entityName.length();
        if (name.get() && (length < nameMin.asInt() || length > nameMax.asInt())) {
            return true;
        }
        if (vanillaChars.get() && !entityName.matches("[A-Za-z0-9_]+")) {
            return true;
        }
        if (!cyrillicChars.get() && entityName.matches(".*\\p{IsCyrillic}.*")) {
            return true;
        }
        if (!cjkChars.get() && entityName.matches(".*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}].*")) {
            return true;
        }
        Object pitchValue = bridge.readField(entity, dev.nocturne.client.mapping.ClassType.ENTITY, "xRot");
        Number pitch = pitchValue instanceof Number ? (Number) pitchValue : null;
        if (illegalPitch.get() && pitch != null && Math.abs(pitch.floatValue()) > 90.0f) {
            return true;
        }
        if (fakeEntityId.get() && id < 0 || duplicate.get() && duplicateNames.contains(
                entityName.toLowerCase(java.util.Locale.ROOT))) {
            return true;
        }
        if (needHit.get() && !hit.contains(id) || swung.get() && !swings.contains(id)) {
            return true;
        }
        float health = bridge.health(entity);
        float maxHealth = bridge.maxHealth(entity);
        if (illegalHealth.get() && (health < 0.0f || maxHealth > 0.0f && health > maxHealth)) {
            return true;
        }
        Object scaleValue = bridge.callMapped(entity,
                dev.nocturne.client.mapping.ClassType.ENTITY, "getScale");
        Number scale = scaleValue instanceof Number ? (Number) scaleValue : null;
        return illegalScale.get() && scale != null && (scale.floatValue() <= 0.0f
                || scale.floatValue() > 16.0f);
    }
}
