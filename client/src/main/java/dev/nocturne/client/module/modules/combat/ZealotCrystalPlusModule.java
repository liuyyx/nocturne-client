package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.RenderSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ZealotCrystalPlus：后台计算多目标水晶放置/破坏计划，游戏线程只消费快照。 */
public final class ZealotCrystalPlusModule extends GameModule implements WorldOverlay {
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", false));
    private final BooleanValue animals = add(new BooleanValue("Animals", false));
    private final NumberValue maxTargets = add(new NumberValue("Max Targets", 4, 1, 10, 1));
    private final NumberValue targetRange = add(new NumberValue("Target Range", 16, 0, 32, 1));
    private final NumberValue yawSpeed = add(new NumberValue("Yaw Speed", 45, 5, 180, 1));
    private final NumberValue placeRotationRange = add(new NumberValue("Place Rotation Range", 0, 0, 180, 1));
    private final NumberValue breakRotationRange = add(new NumberValue("Break Rotation Range", 90, 0, 180, 1));
    private final BooleanValue eatingPause = add(new BooleanValue("Eating Pause", false));
    private final NumberValue updateDelay = add(new NumberValue("Update Delay", 5, 0, 250, 5));
    private final NumberValue globalDelay = add(new NumberValue("Global Delay", 1000000, 1000, 10000000, 1000));
    private final NumberValue forceHealth = add(new NumberValue("Force Place Health", 8, 0, 20, 0.5));
    private final NumberValue forceArmor = add(new NumberValue("Force Place Armor Rate", 3, 0, 25, 1));
    private final NumberValue forceMinDamage = add(new NumberValue("Force Place Min Damage", 1.5, 0, 10, 0.1));
    private final NumberValue forceMotion = add(new NumberValue("Force Place Motion", 4, 0, 10, 0.1));
    private final NumberValue forceBalance = add(new NumberValue("Force Place Balance", -1, -10, 10, 0.1));
    private final BooleanValue forceSwording = add(new BooleanValue("Force Place While Swording", false));
    private final BooleanValue assumeInstantMine = add(new BooleanValue("Assume Instant Mine", true));
    private final NumberValue noSuicide = add(new NumberValue("No Suicide", 2, 0, 20, 0.1));
    private final NumberValue wallRange = add(new NumberValue("Wall Range", 3, 0, 8, 0.1));
    private final BooleanValue motionPredict = add(new BooleanValue("Motion Predict", true));
    private final NumberValue predictTicks = add(new NumberValue("Predict Ticks", 8, 0, 20, 1));
    private final ModeValue damagePriority = add(new ModeValue("Damage Priority", "Efficient", "Efficient", "Aggressive"));
    private final ModeValue armorMode = add(new ModeValue("Armor Mode", "None", "None", "General", "Full"));
    private final BooleanValue lethalOverride = add(new BooleanValue("Lethal Override", true));
    private final NumberValue lethalThreshold = add(new NumberValue("Lethal Threshold Addition", 0.5, -5, 5, 0.1));
    private final NumberValue lethalMaxSelf = add(new NumberValue("Lethal Max Self Damage", 16, 0, 20, 0.1));
    private final NumberValue safeTargetReduction = add(new NumberValue("Safe Max Target Damage Reduction", 1, 0, 10, 0.1));
    private final NumberValue safeSelfReduction = add(new NumberValue("Safe Min Self Damage Reduction", 2, 0, 10, 0.1));
    private final NumberValue collidingSelfThreshold = add(new NumberValue("Colliding Crystal Extra Self Damage Threshold", 4, 0, 10, 0.1));
    private final ModeValue placeMode = add(new ModeValue("Place Mode", "Single", "Off", "Single", "Multi"));
    private final ModeValue packetPlace = add(new ModeValue("Packet Place", "Weak", "Off", "Weak", "Strong"));
    private final BooleanValue spamPlace = add(new BooleanValue("Spam Place", false));
    private final ModeValue placeSwitch = add(new ModeValue("Place Switch Mode", "Off", "Off", "Legit", "Ghost"));
    private final BooleanValue placeSwing = add(new BooleanValue("Place Swing", false));
    private final ModeValue placeBypass = add(new ModeValue("Place Side Bypass", "Up", "Up", "Down", "Closest"));
    private final NumberValue placeMinDamage = add(new NumberValue("Place Min Damage", 5, 0, 20, 0.1));
    private final NumberValue placeMaxSelf = add(new NumberValue("Place Max Self Damage", 6, 0, 20, 0.1));
    private final NumberValue placeBalance = add(new NumberValue("Place Balance", -3, -10, 10, 0.1));
    private final NumberValue placeDelay = add(new NumberValue("Place Delay", 50, 0, 500, 5));
    private final NumberValue placeRange = add(new NumberValue("Place Range", 5, 0, 8, 0.1));
    private final ModeValue placeRangeMode = add(new ModeValue("Place Range Mode", "Feet", "Feet", "Eyes"));
    private final ModeValue breakMode = add(new ModeValue("Break Mode", "Smart", "Off", "Target", "Own", "Smart", "All"));
    private final BooleanValue twoBtwoT = add(new BooleanValue("2B2T", false));
    private final NumberValue twoBtwoTFactor = add(new NumberValue("2B2T Factor", 200, 0, 1000, 25));
    private final ModeValue packetBreak = add(new ModeValue("Packet Break", "Target", "Off", "Target", "Own", "Smart", "All"));
    private final NumberValue ownTimeout = add(new NumberValue("Own Timeout", 100, 0, 2000, 10));
    private final ModeValue antiWeakness = add(new ModeValue("Anti Weakness", "Off", "Off", "Legit", "Ghost"));
    private final NumberValue swapDelay = add(new NumberValue("Swap Delay", 0, 0, 20, 1));
    private final NumberValue breakMinDamage = add(new NumberValue("Break Min Damage", 4, 0, 20, 0.1));
    private final NumberValue breakMaxSelf = add(new NumberValue("Break Max Self Damage", 8, 0, 20, 0.1));
    private final NumberValue breakBalance = add(new NumberValue("Break Balance", -4, -10, 10, 0.1));
    private final NumberValue breakDelay = add(new NumberValue("Break Delay", 100, 0, 500, 5));
    private final NumberValue breakRange = add(new NumberValue("Break Range", 5, 0, 8, 0.1));
    private final ModeValue breakRangeMode = add(new ModeValue("Break Range Mode", "Feet", "Feet", "Eyes"));
    private final ModeValue swingMode = add(new ModeValue("Swing Mode", "Client", "None", "Client", "Packet"));
    private final ModeValue swingHand = add(new ModeValue("Swing Hand", "Auto", "Auto", "OffHand", "MainHand"));
    private final ModeValue renderPredict = add(new ModeValue("Render Predict", "Off", "Off", "Single", "Multi"));
    private final ModeValue hudInfo = add(new ModeValue("Hud Info", "Speed", "Off", "Speed", "Target", "Damage", "CalculationTime"));
    private final NumberValue filledAlpha = add(new NumberValue("Filled Alpha", 63, 0, 255, 1));
    private final NumberValue outlineAlpha = add(new NumberValue("Outline Alpha", 200, 0, 255, 1));
    private final BooleanValue targetDamage = add(new BooleanValue("Target Damage", true));
    private final BooleanValue selfDamage = add(new BooleanValue("Self Damage", true));
    private final ColorValue renderColor = add(new ColorValue("Render Color", 0xFFFF9678));
    private final NumberValue outlineWidth = add(new NumberValue("Outline Width", 3, 1, 10, 0.5));
    private final NumberValue movingLength = add(new NumberValue("Moving Length", 400, 0, 1000, 10));
    private final NumberValue fadeLength = add(new NumberValue("Fade Length", 200, 0, 1000, 10));
    private final Map<String, Long> placed = new HashMap<String, Long>();
    private volatile Plan plan = Plan.EMPTY;
    private volatile Snapshot snapshot;
    private volatile long calculationNanos;
    private Thread worker;
    private volatile boolean running;
    private long nextAction;
    private long nextPlace;
    private long nextBreak;
    private int original = -1;
    private int restoreTicks;

    public ZealotCrystalPlusModule() {
        super("Zealot Crystal Plus", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        running = true;
        worker = new Thread(this::calculateLoop, "Zealot+");
        worker.setDaemon(true);
        worker.start();
        listen(PacketEvent.class, event -> {
            if (event.direction == PacketEvent.Direction.RECEIVE) {
                snapshot = null;
            }
        });
    }

    @Override
    public void onTick() {
        if (eatingPause.get() && bridge().usingItem(bridge().player())) {
            return;
        }
        snapshot = capture();
        if (original >= 0 && --restoreTicks <= 0) {
            actions().selectSlot(original, placeSwitch.is("Ghost"));
            original = -1;
        }
        long now = System.nanoTime();
        if (now < nextAction) {
            return;
        }
        Plan current = plan;
        if (current.breakCrystal != null && System.currentTimeMillis() >= nextBreak) {
            if (actions().attack(current.breakCrystal)) {
                swing();
                nextBreak = System.currentTimeMillis() + scaledDelay(breakDelay.asInt());
                nextAction = now + globalDelay.asInt();
            }
        }
        if (current.place != null && System.currentTimeMillis() >= nextPlace) {
            int slot = EpsilonSupport.findHotbar(bridge(), "end_crystal");
            if (slot >= 0) {
                select(slot);
                String face = placeBypass.is("Down") ? "down" : "up";
                int[] p = current.place;
                if (actions().placeAt(p[0], p[1], p[2], face)) {
                    if (placeSwing.get()) {
                        swing();
                    }
                    String key = p[0] + ":" + p[1] + ":" + p[2];
                    placed.put(key, System.currentTimeMillis());
                    if (packetPlace.is("Strong")) {
                        actions().placeAt(p[0], p[1], p[2], face);
                    }
                    nextPlace = System.currentTimeMillis() + scaledDelay(placeDelay.asInt());
                    nextAction = now + globalDelay.asInt();
                    if (placeMode.is("Single")) {
                        plan = new Plan(null, current.breakCrystal, current.targetDamage, current.selfDamage);
                    }
                }
            }
        }
        if (spamPlace.get()) {
            nextPlace = 0L;
        }
    }

    private void calculateLoop() {
        while (running) {
            Snapshot input = snapshot;
            if (input != null) {
                long start = System.nanoTime();
                plan = calculate(input);
                calculationNanos = System.nanoTime() - start;
                snapshot = null;
            }
            try {
                Thread.sleep(Math.max(1L, updateDelay.asInt()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable failure) {
                System.out.println("[nocturne] ZealotCrystalPlus calculation failed: " + failure);
            }
        }
    }

    private Plan calculate(Snapshot input) {
        Object breakCrystal = null;
        double bestBreak = Double.NEGATIVE_INFINITY;
        for (Crystal crystal : input.crystals) {
            double self = damage(input.self, crystal.position);
            double enemy = bestTargetDamage(input.targets, crystal.position);
            boolean own = placed.containsKey(crystal.key);
            if (breakMode.is("Own") && !own || breakMode.is("Target") && enemy <= 0.0 || breakMode.is("Off")) {
                continue;
            }
            if (own && System.currentTimeMillis() - placed.get(crystal.key) > ownTimeout.asInt()) {
                continue;
            }
            double score = enemy - self * (damagePriority.is("Aggressive") ? 0.0 : 1.0);
            if (enemy >= breakMinDamage.get() && self <= breakMaxSelf.get()
                    && enemy - self >= breakBalance.get() && score > bestBreak) {
                breakCrystal = crystal.entity;
                bestBreak = score;
            }
        }
        int[] place = null;
        double placeScore = Double.NEGATIVE_INFINITY;
        double chosenTarget = 0.0;
        double chosenSelf = 0.0;
        if (!placeMode.is("Off")) {
            for (TargetData target : input.targets) {
                for (int[] base : input.bases) {
                    if (Math.sqrt(distanceSquared(target.position, center(base))) > targetRange.get()) {
                        continue;
                    }
                    double enemy = damage(target.position, center(base));
                    double self = damage(input.self, center(base));
                    if (!armorMode.is("None")) {
                        self *= armorMode.is("Full") ? 0.55 : 0.75;
                    }
                    boolean lethal = lethalOverride.get() && enemy >= target.health + lethalThreshold.get()
                            && self <= lethalMaxSelf.get();
                    if (self > placeMaxSelf.get() && !lethal || self + noSuicide.get() >= input.health
                            || enemy < placeMinDamage.get() && !lethal || enemy - self < placeBalance.get() && !lethal) {
                        continue;
                    }
                    double score = enemy - self + (target.health <= forceHealth.get() ? forceMinDamage.get() : 0.0);
                    if (score > placeScore) {
                        placeScore = score;
                        place = base;
                        chosenTarget = enemy;
                        chosenSelf = self;
                    }
                }
            }
        }
        return new Plan(place, breakCrystal, chosenTarget, chosenSelf);
    }

    private Snapshot capture() {
        List<TargetManager.Target> found = client().targets().list(client(), EpsilonSupport.targets(
                targetRange.get(), players.get(), mobs.get() || animals.get(), true, TargetManager.Sort.DISTANCE));
        List<TargetData> targets = new ArrayList<TargetData>();
        for (TargetManager.Target target : found) {
            if (targets.size() >= maxTargets.asInt()) {
                break;
            }
            double[] position = {target.x(), target.aimY(), target.z()};
            if (motionPredict.get()) {
                double[] velocity = bridge().entityVelocity(target.entity());
                for (int axis = 0; axis < 3; axis++) {
                    position[axis] += velocity[axis] * predictTicks.get();
                }
            }
            targets.add(new TargetData(position, bridge().health(target.entity())));
        }
        List<int[]> bases = new ArrayList<int[]>();
        int px = (int) Math.floor(state().x());
        int py = (int) Math.floor(state().y());
        int pz = (int) Math.floor(state().z());
        int radius = (int) Math.ceil(placeRange.get());
        for (int x = px - radius; x <= px + radius; x++) {
            for (int y = py - 3; y <= py + 2; y++) {
                for (int z = pz - radius; z <= pz + radius; z++) {
                    String id = bridge().blockDescription(bridge().blockStateAt(x, y, z));
                    if ((id.contains("obsidian") || id.contains("bedrock"))
                            && bridge().isAir(bridge().blockStateAt(x, y + 1, z))
                            && distanceSquared(new double[]{state().x(), state().y(), state().z()}, center(x, y, z))
                            <= placeRange.get() * placeRange.get()) {
                        bases.add(new int[]{x, y, z});
                    }
                }
            }
        }
        List<Crystal> crystals = new ArrayList<Crystal>();
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, ClassType.END_CRYSTAL)) {
                return;
            }
            double[] position = bridge().entityPosition(entity);
            if (position != null && Math.sqrt(distanceSquared(new double[]{state().x(), state().y(), state().z()}, position))
                    <= breakRange.get()) {
                crystals.add(new Crystal(entity, position, (int) Math.floor(position[0]) + ":"
                        + (int) Math.floor(position[1] - 1) + ":" + (int) Math.floor(position[2])));
            }
        });
        return new Snapshot(targets, bases, crystals, new double[]{state().x(), state().y(), state().z()}, state().health());
    }

    private double bestTargetDamage(List<TargetData> targets, double[] position) {
        double best = 0.0;
        for (TargetData target : targets) {
            best = Math.max(best, damage(target.position, position));
        }
        return best;
    }

    private static double damage(double[] target, double[] explosion) {
        double distance = Math.sqrt(distanceSquared(target, explosion));
        return Math.max(0.0, 12.0 * (1.0 - distance / 8.0));
    }

    private static double[] center(int[] pos) {
        return new double[]{pos[0] + 0.5, pos[1] + 1.0, pos[2] + 0.5};
    }

    private static double[] center(int x, int y, int z) {
        return center(new int[]{x, y, z});
    }

    private static double distanceSquared(double[] a, double[] b) {
        return Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2);
    }

    private long scaledDelay(long delay) {
        return twoBtwoT.get() ? delay + twoBtwoTFactor.asInt() : delay;
    }

    private void select(int slot) {
        if (placeSwitch.is("Off")) {
            return;
        }
        original = state().selectedSlot();
        actions().selectSlot(slot, placeSwitch.is("Ghost"));
        restoreTicks = swapDelay.asInt() + 1;
    }

    private void swing() {
        if (!swingMode.is("None")) {
            actions().swing(swingHand.is("OffHand"), swingMode.is("Packet"));
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        Plan current = plan;
        if (current.place != null && !renderPredict.is("Off")) {
            int color = renderColor.argb();
            int fill = color & 0xFFFFFF | filledAlpha.asInt() << 24;
            int line = color & 0xFFFFFF | outlineAlpha.asInt() << 24;
            WorldGeometry.blockFilled(draw, projection, current.place[0], current.place[1] + 1,
                    current.place[2], outlineWidth.asFloat(), fill, line);
            StringBuilder text = new StringBuilder();
            if (targetDamage.get()) {
                text.append(String.format(java.util.Locale.ROOT, "%.1f", current.targetDamage));
            }
            if (selfDamage.get()) {
                text.append(" / ").append(String.format(java.util.Locale.ROOT, "%.1f", current.selfDamage));
            }
            if (hudInfo.is("CalculationTime")) {
                text.append(' ').append(calculationNanos / 1000).append("us");
            } else if (hudInfo.is("Speed")) {
                text.append(' ').append(movingLength.asInt()).append("ms");
            } else if (hudInfo.is("Target")) {
                text.append(" target");
            } else if (hudInfo.is("Damage")) {
                text.append(" dmg");
            }
            if (text.length() > 0) {
                WorldGeometry.label(draw, projection, text.toString(), current.place[0] + 0.5,
                        current.place[1] + 2.2, current.place[2] + 0.5, line);
            }
        }
    }

    @Override
    protected void onStop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        worker = null;
        snapshot = null;
        plan = Plan.EMPTY;
        placed.clear();
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, placeSwitch.is("Ghost"));
        }
        original = -1;
    }

    private static final class Plan {
        private static final Plan EMPTY = new Plan(null, null, 0.0, 0.0);
        private final int[] place;
        private final Object breakCrystal;
        private final double targetDamage;
        private final double selfDamage;

        private Plan(int[] place, Object breakCrystal, double targetDamage, double selfDamage) {
            this.place = place;
            this.breakCrystal = breakCrystal;
            this.targetDamage = targetDamage;
            this.selfDamage = selfDamage;
        }
    }

    private static final class Snapshot {
        private final List<TargetData> targets;
        private final List<int[]> bases;
        private final List<Crystal> crystals;
        private final double[] self;
        private final float health;

        private Snapshot(List<TargetData> targets, List<int[]> bases, List<Crystal> crystals,
                         double[] self, float health) {
            this.targets = targets;
            this.bases = bases;
            this.crystals = crystals;
            this.self = self;
            this.health = health;
        }
    }

    private static final class TargetData {
        private final double[] position;
        private final float health;

        private TargetData(double[] position, float health) {
            this.position = position;
            this.health = health;
        }
    }

    private static final class Crystal {
        private final Object entity;
        private final double[] position;
        private final String key;

        private Crystal(Object entity, double[] position, String key) {
            this.entity = entity;
            this.position = position;
            this.key = key;
        }
    }
}
