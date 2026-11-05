package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.TextValue;

/** TitleReplace：替换标题/副标题/动作栏指定前缀，可生成逐字符渐变组件。 */
public final class TitleReplaceModule extends GameModule {
    private final TextValue value = add(new TextValue("Value", "跨圈皇帝萌萌刻", 256));
    private final BooleanValue gradient = add(new BooleanValue("Gradient", true));
    private final ColorValue color = add(new ColorValue("Color", 0xFF55FFFF));
    private final ColorValue startColor = add(new ColorValue("Start Color", 0xFFFFB7C5));
    private final ColorValue endColor = add(new ColorValue("End Color", 0xFFFF85A1));

    public TitleReplaceModule() {
        super("TitleReplace", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (event.direction != PacketEvent.Direction.RECEIVE) {
                return;
            }
            ClassType owner = titleType(event.packet);
            if (owner == null) {
                return;
            }
            Object text = bridge().readField(event.packet, owner, "text");
            String plain = bridge().componentText(text);
            if (plain == null) {
                return;
            }
            String replaced = plain.replaceAll("(祝你好运\\s*)[^!！\\n]*", "$1" + value.get());
            if (!replaced.equals(plain)) {
                bridge().writeField(event.packet, owner, "text", component(replaced));
            }
        });
    }

    private ClassType titleType(Object packet) {
        if (bridge().isInstance(packet, ClassType.CLIENTBOUND_SET_TITLE_TEXT)) {
            return ClassType.CLIENTBOUND_SET_TITLE_TEXT;
        }
        if (bridge().isInstance(packet, ClassType.CLIENTBOUND_SET_SUBTITLE_TEXT)) {
            return ClassType.CLIENTBOUND_SET_SUBTITLE_TEXT;
        }
        return bridge().isInstance(packet, ClassType.CLIENTBOUND_SET_ACTION_BAR_TEXT)
                ? ClassType.CLIENTBOUND_SET_ACTION_BAR_TEXT : null;
    }

    private Object component(String text) {
        if (!gradient.get() || text.isEmpty()) {
            return coloredLiteral(text, color.argb() & 0xFFFFFF);
        }
        Object result = bridge().callStaticMapped(ClassType.COMPONENT, "empty");
        for (int i = 0; i < text.length(); i++) {
            float amount = text.length() == 1 ? 0.0f : i / (float) (text.length() - 1);
            int rgb = blend(startColor.argb(), endColor.argb(), amount);
            Object letter = coloredLiteral(String.valueOf(text.charAt(i)), rgb);
            Object next = bridge().callMapped(result, ClassType.MUTABLE_COMPONENT, "append", letter);
            if (next != null) {
                result = next;
            }
        }
        return result != null ? result : coloredLiteral(text, startColor.argb() & 0xFFFFFF);
    }

    private Object coloredLiteral(String text, int rgb) {
        Object literal = bridge().callStaticMapped(ClassType.COMPONENT, "literal", text);
        Object style = bridge().callStaticMapped(ClassType.STYLE, "empty");
        Object colored = bridge().callMapped(style, ClassType.STYLE, "withColor", rgb);
        Object result = bridge().callMapped(literal, ClassType.MUTABLE_COMPONENT, "setStyle",
                colored == null ? style : colored);
        return result == null ? literal : result;
    }

    private static int blend(int from, int to, float amount) {
        int r = (int) ((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * amount);
        int g = (int) ((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * amount);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * amount);
        return r << 16 | g << 8 | b;
    }
}
