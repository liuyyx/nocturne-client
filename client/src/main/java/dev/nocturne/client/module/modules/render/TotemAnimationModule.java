package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Totem Animation：FadeOut/Size/Otkisuli/Insert/Fall/Rocket/Roll 手持图腾动画。 */
public final class TotemAnimationModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "FadeOut", "FadeOut", "Size", "Otkisuli", "Insert", "Fall", "Rocket", "Roll"));
    private final NumberValue speed = add(new NumberValue("Speed", 40, 1, 100, 1));
    private long started = -1L;

    public TotemAnimationModule() {
        super("Totem Animation", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            int[] data = bridge().entityEventData(event.packet);
            if (event.direction == PacketEvent.Direction.RECEIVE && data != null && data[0] == 35
                    && data[1] == bridge().entityId(bridge().player())) {
                started = System.currentTimeMillis();
            }
        });
    }

    public void apply(Object pose) {
        if (started < 0L) {
            return;
        }
        float progress = (System.currentTimeMillis() - started) / Math.max(10.0f, speed.asFloat() * 20.0f);
        if (progress >= 1.0f) {
            started = -1L;
            return;
        }
        double y = 0.0;
        double z = 0.0;
        double scale = 1.0;
        double rotX = 0.0;
        double rotZ = 0.0;
        if (mode.is("FadeOut") || mode.is("Size")) {
            scale = 1.0 - progress;
        } else if (mode.is("Otkisuli")) {
            z = Math.sin(progress * Math.PI * 4.0) * 0.25;
            rotZ = progress * 180.0;
        } else if (mode.is("Insert")) {
            y = Math.sin(progress * Math.PI) * 0.4;
            rotX = progress * 90.0;
        } else if (mode.is("Fall")) {
            y = -progress * progress * 1.5;
            rotX = progress * 180.0;
        } else if (mode.is("Rocket")) {
            y = progress * 2.0;
            scale = 1.0 - progress * 0.5;
        } else if (mode.is("Roll")) {
            rotZ = progress * 720.0;
            y = -progress * 0.5;
        }
        bridge().transformPose(pose, 0.0, y, z, scale, scale, scale, rotX, 0.0, rotZ);
    }

    @Override
    protected void onStop() {
        started = -1L;
    }
}
