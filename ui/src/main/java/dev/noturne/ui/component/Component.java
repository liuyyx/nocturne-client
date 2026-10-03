package dev.noturne.ui.component;

import dev.noturne.ui.render.Renderer;

/**
 * A positioned, drawable, interactive rectangle. Components form a tree; input is routed
 * top-down and hit-tested back-to-front.
 */
public abstract class Component {

    protected float x;
    protected float y;
    protected float width;
    protected float height;
    protected boolean visible = true;
    protected boolean hovered;

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }

    public void setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isHovered() {
        return hovered;
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    /** Updates hover state; returns true when the pointer is inside. */
    public boolean updateHover(double mx, double my) {
        hovered = visible && contains(mx, my);
        return hovered;
    }

    public abstract void render(Renderer renderer);

    // -------------------------------------------------------------- input hooks
    // Return true to consume the event.

    public boolean mouseClicked(double mx, double my, int button) {
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        return false;
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        return false;
    }

    public boolean charTyped(char character) {
        return false;
    }
}
