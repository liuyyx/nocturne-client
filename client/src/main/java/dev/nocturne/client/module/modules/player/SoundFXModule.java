package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** SoundFX：攻击实体后按延迟播放选中的命中音效。 */
public final class SoundFXModule extends GameModule {
    private final NumberValue volume = add(new NumberValue("Volume", 100, 1, 100, 5));
    private final NumberValue delay = add(new NumberValue("Delay", 0, 0, 20, 1));
    private final ModeValue hitSound = add(new ModeValue("Hit Sound", "OFF", "UwU", "Nya", "Moan", "OFF"));
    private int pending = -1;

    public SoundFXModule() {
        super("SoundFX", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (!event.post && !hitSound.is("OFF")) {
                pending = delay.asInt() + 1;
            }
        });
    }

    @Override
    public void onTick() {
        if (pending < 0 || --pending > 0) {
            return;
        }
        String sound;
        if (hitSound.is("UwU")) {
            sound = "ENTITY_CAT_AMBIENT";
        } else if (hitSound.is("Nya")) {
            sound = "ENTITY_CAT_PURR";
        } else {
            String[] sounds = {"ENTITY_VILLAGER_YES", "ENTITY_VILLAGER_NO",
                    "ENTITY_WITCH_AMBIENT", "ENTITY_ALLAY_AMBIENT_WITH_ITEM"};
            sound = sounds[EpsilonSupport.RANDOM.nextInt(sounds.length)];
        }
        bridge().playSound(sound, volume.asFloat() / 100.0f, 1.0f);
        pending = -1;
    }

    @Override
    protected void onStop() {
        pending = -1;
    }
}
