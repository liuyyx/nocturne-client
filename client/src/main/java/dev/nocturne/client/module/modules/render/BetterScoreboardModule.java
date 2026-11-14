package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/** 自绘计分板；模糊通道不可用，只有读到有效行才允许接线层隐藏原版。 */
public final class BetterScoreboardModule extends GameModule implements WorldOverlay {
    private final ColorValue background = add(new ColorValue("Background Color", 0x5A101114));
    private final NumberValue radius = add(new NumberValue("Radius", 4.5, 0, 12, .5));
    private final NumberValue xOffset = add(new NumberValue("X Offset", 0, -500, 500, 1));
    private final NumberValue yOffset = add(new NumberValue("Y Offset", 0, -500, 500, 1));
    private final BooleanValue shadow = add(new BooleanValue("Draw Shadow", true));
    private final ColorValue shadowColor = add(new ColorValue("Shadow Color", 0x6EFFFFFF));
    private volatile Line[] rows = new Line[0];
    private volatile boolean hasRows;

    public BetterScoreboardModule() { super("Better Scoreboard", Category.RENDER); }

    @Override protected void onStart() {
        clear();
        listen(WorldChangeEvent.class, event -> clear());
        updateRows();
    }

    @Override protected void onStop() { clear(); }

    private void clear() {
        hasRows = false;
        rows = new Line[0];
    }

    @Override public void onTick() { updateRows(); }

    private void updateRows() {
        GameBridge bridge = bridge();
        List<GameBridge.ScoreboardRow> current = bridge == null ? null : bridge.scoreboardRows();
        if (current == null || current.isEmpty()) {
            rows = new Line[0];
            return;
        }
        Line[] snapshot = new Line[current.size()];
        for (int i = 0; i < snapshot.length; i++) {
            GameBridge.ScoreboardRow row = current.get(i);
            snapshot[i] = new Line(row.label, row.score == null ? null : row.score.toString(), row.color);
        }
        rows = snapshot;
        hasRows = true;
    }

    /** 本次启用且当前世界至少一次读到非空行后允许隐藏原版。 */
    public boolean suppressVanilla() { return hasRows; }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        Line[] snapshot = rows;
        if (snapshot.length == 0 || draw.width() <= 0 || draw.height() <= 0) return;
        float lineHeight = draw.textHeight();
        if (lineHeight <= 0f) return;
        float contentWidth = 0f;
        for (Line row : snapshot) {
            float width = draw.textWidth(row.label);
            if (row.score != null) width += 8f + draw.textWidth(row.score);
            contentWidth = Math.max(contentWidth, width);
        }
        float width = contentWidth + 8f;
        float height = snapshot.length * lineHeight + 8f;
        float x = draw.width() - width - 4f + xOffset.asFloat();
        float y = (draw.height() - height) * .5f + yOffset.asFloat();
        if (shadow.get()) draw.roundedRect(x + 2f, y + 2f, width, height,
                radius.asFloat(), shadowColor.argb());
        draw.roundedRect(x, y, width, height, radius.asFloat(), background.argb());
        float lineY = y + 4f;
        for (Line row : snapshot) {
            if (row.score == null) {
                draw.text(row.label, x + (width - draw.textWidth(row.label)) * .5f, lineY, row.color);
            } else {
                draw.text(row.label, x + 4f, lineY, row.color);
                draw.text(row.score, x + width - 4f - draw.textWidth(row.score), lineY, row.color);
            }
            lineY += lineHeight;
        }
    }

    /** tick 中准备好显示文本，绘制线程只读取不可变快照。 */
    private static final class Line {
        final String label;
        final String score;
        final int color;

        Line(String label, String score, int color) {
            this.label = label;
            this.score = score;
            this.color = color;
        }
    }
}
