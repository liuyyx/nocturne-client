package dev.noturne.ui.component;

import dev.noturne.ui.render.Renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Container component: owns children, draws them in order, and routes input to the topmost
 * child under the pointer.
 */
public class Panel extends Component {

    protected final List<Component> children = new ArrayList<Component>();

    public void add(Component child) {
        children.add(child);
    }

    public void remove(Component child) {
        children.remove(child);
    }

    public List<Component> children() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).render(renderer);
        }
    }

    /** The topmost visible child containing the point, or null. */
    protected Component hitTest(double mx, double my) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Component child = children.get(i);
            if (child.isVisible() && child.contains(mx, my)) {
                return child;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Component target = hitTest(mx, my);
        if (target != null && target.mouseClicked(mx, my, button)) {
            return true;
        }
        return contains(mx, my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean consumed = false;
        for (int i = children.size() - 1; i >= 0; i--) {
            Component child = children.get(i);
            if (child.isVisible() && child.mouseReleased(mx, my, button)) {
                consumed = true;
                break;
            }
        }
        return consumed;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Component child = children.get(i);
            if (child.isVisible() && child.mouseDragged(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Component child = children.get(i);
            if (child.isVisible() && child.contains(mx, my) && child.mouseScrolled(mx, my, amount)) {
                return true;
            }
        }
        return contains(mx, my);
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).keyPressed(keyCode, modifiers)) {
                return true;
            }
        }
        return false;
    }
}
