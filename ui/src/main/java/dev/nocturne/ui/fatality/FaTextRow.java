package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.TextValue;

/** 文本设置行：点击字段后编辑，输入即时归一化写回，Esc 放弃本次编辑。 */
final class FaTextRow extends FaRow implements FaKeySink {

    private final TextValue text;
    private final FaTextInput entry;
    private boolean editing;

    FaTextRow(final TextValue text) {
        super(text, text.name());
        this.text = text;
        entry = new FaTextInput(text.maxLength());
        entry.set(text.get());
        entry.onChange(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String value) {
                text.set(value);
            }
        });
        entry.onSubmit(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String value) {
                editing = false;
            }
        });
        entry.onCancel(new Runnable() {
            @Override
            public void run() {
                entry.set(text.get());
                editing = false;
            }
        });
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float fw = FaTheme.FIELD_W;
        float fh = FaTheme.FIELD_H;
        float fx = fieldX(fw);
        float fy = fieldY();
        drawLabel(ctx, x, centerY, fx - x - FaTheme.ROW_PAD - 6.0f, alpha);
        boolean hot = editing || ctx.hovered(fx, fy, fw, fh);
        ctx.draw.rect(fx, fy, fw, fh, FaTheme.FIELD_RADIUS,
                FaTheme.alpha(hot ? FaTheme.FIELD_HOVER : FaTheme.FIELD, alpha));
        entry.render(ctx.draw, fx + 6.0f, centerY, fw - 12.0f, FaTheme.field(),
                FaTheme.alpha(FaTheme.FIELD_TEXT_HOVER, alpha), editing, "...", FaTheme.FIELD_TEXT);
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (!shown || !FaContext.inside(mx, my, fieldX(FaTheme.FIELD_W), fieldY(),
                FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        editing = true;
        entry.set(text.get());
        ctx.gui.focus(this);
        entry.focused();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (!editing) {
            return false;
        }
        boolean used = entry.keyPressed(key, modifiers);
        return used || key == java.awt.event.KeyEvent.VK_TAB;
    }

    @Override
    public boolean charTyped(char character) {
        return editing && entry.charTyped(character);
    }

    @Override
    public void focusLost() {
        editing = false;
        entry.set(text.get());
    }

    @Override
    boolean matches(String query) {
        return super.matches(query) || text.get().toLowerCase(java.util.Locale.ROOT).contains(query);
    }
}
