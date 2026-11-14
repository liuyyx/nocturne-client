package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.TextValue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 替换收到的标题、副标题及动作栏中的指定文本。
 * 跨版本组件样式不能可靠构造，替换结果为单色纯文本；不提供渐变及颜色设置。
 * 缺少包映射、组件构造或字段写入能力时保留原包。
 */
public final class TitleReplaceModule extends GameModule {
    private static final Pattern TARGET = Pattern.compile("(祝你好运\\s*)[^!！\\n]*");
    private final TextValue value = add(new TextValue("Value", "跨圈皇帝萌萌刻", 256));

    public TitleReplaceModule() { super("Title Replace", Category.RENDER); }

    @Override protected void onStart() { listen(PacketEvent.class, this::onPacket); }

    private void onPacket(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE || event.packet == null) return;
        GameBridge bridge = bridge();
        if (bridge == null) return;
        ClassType type;
        if (bridge.isInstance(event.packet, ClassType.CLIENTBOUND_SET_TITLE_TEXT)) {
            type = ClassType.CLIENTBOUND_SET_TITLE_TEXT;
        } else if (bridge.isInstance(event.packet, ClassType.CLIENTBOUND_SET_SUBTITLE_TEXT)) {
            type = ClassType.CLIENTBOUND_SET_SUBTITLE_TEXT;
        } else if (bridge.isInstance(event.packet, ClassType.CLIENTBOUND_SET_ACTION_BAR_TEXT)) {
            type = ClassType.CLIENTBOUND_SET_ACTION_BAR_TEXT;
        } else {
            return;
        }
        String text = bridge.componentText(bridge.readField(event.packet, type, "text"));
        if (text == null) return;
        Matcher matcher = TARGET.matcher(text);
        if (!matcher.find()) return;
        String replaced = matcher.replaceAll(Matcher.quoteReplacement(value.get()));
        Object component = bridge.callStaticMapped(ClassType.COMPONENT, "literal", replaced);
        if (component == null) component = bridge.construct(ClassType.TEXT_COMPONENT, replaced);
        if (component == null) return;
        if (!bridge.writeField(event.packet, type, "text", component)) {
            // record 的 final 字段不可写；仅在单组件构造器存在时替换整个包。
            Object replacement = bridge.construct(type, component);
            if (replacement != null) event.setPacket(replacement);
        }
    }
}
