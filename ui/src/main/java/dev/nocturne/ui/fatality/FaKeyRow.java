package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.KeyValue;

import java.awt.event.KeyEvent;

/** 按键绑定行：点击后捕获下一次 VK 键码，Delete/Backspace 清除，Esc 取消。 */
final class FaKeyRow extends FaRow implements FaKeySink {

    private final KeyValue key;
    private boolean listening;

    FaKeyRow(KeyValue key) {
        super(key, key.name());
        this.key = key;
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float fw = FaTheme.FIELD_W;
        float fh = FaTheme.FIELD_H;
        float fx = fieldX(fw);
        float fy = fieldY();
        drawLabel(ctx, x, centerY, fx - x - FaTheme.ROW_PAD - 6.0f, alpha);
        boolean hot = listening || ctx.hovered(fx, fy, fw, fh);
        FaComboRow.drawField(ctx, fx, fy, fw, fh, listening ? "..." : key.display(), hot, alpha);
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (button != 0 || !shown || !FaContext.inside(mx, my, fieldX(FaTheme.FIELD_W), fieldY(),
                FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        listening = true;
        ctx.gui.focus(this);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (!listening) {
            return false;
        }
        if (keyCode == KeyEvent.VK_ESCAPE) {
            listening = false;
            return true;
        }
        key.set(keyCode == KeyEvent.VK_BACK_SPACE || keyCode == KeyEvent.VK_DELETE
                ? KeyValue.NONE : keyCode);
        listening = false;
        return true;
    }

    @Override
    public boolean charTyped(char character) {
        return listening;
    }

    @Override
    public void focusLost() {
        listening = false;
    }
}
