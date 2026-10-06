package dev.nocturne.ui.component;

import dev.nocturne.ui.render.Renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 容器控件：持有子控件、按添加顺序绘制，并把输入路由到指针下最上层的子控件。
 *
 * <p>子控件列表顺序即绘制顺序（先添加者先绘制，后添加者覆盖在其上），命中测试与输入派发
 * 则反向遍历（从后添加者开始），从而让视觉上层优先接收事件。
 */
public class Panel extends Component {

    /** 子控件列表，顺序即绘制顺序。 */
    protected final List<Component> children = new ArrayList<Component>();

    /** 追加子控件，新控件位于视觉最上层。 */
    public void add(Component child) {
        children.add(child);
    }

    /** 移除子控件（若存在）。 */
    public void remove(Component child) {
        children.remove(child);
    }

    /** 返回只读的子控件视图。 */
    public List<Component> children() {
        return Collections.unmodifiableList(children);
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        // 顺序绘制，后添加者覆盖先添加者
        for (int i = 0; i < children.size(); i++) {
            children.get(i).render(renderer);
        }
    }

    /** 返回包含该点的最上层可见子控件；无则返回 null。 */
    protected Component hitTest(double mx, double my) {
        // 逆序遍历：列表尾部在视觉上层，优先命中
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
        // 无子控件消费时，若点在自身范围内也算消费，避免事件穿透到更底层
        return contains(mx, my);
    }

    @Override
    public void update(long nowMs) {
        // 时钟注入式转发：整棵子树共享同一时间基准，动画/过渡随帧推进
        for (int i = 0; i < children.size(); i++) {
            children.get(i).update(nowMs);
        }
    }

    /**
     * 递归刷新整棵子树的悬停态（P4）。
     *
     * <p>{@link #update(long)} 没有指针坐标，通用 Panel 下的 Button 等控件 hover 永不可达；
     * 有指针的调用方调本方法一次，悬停高亮即生效。
     */
    public void updateHoverTree(double mx, double my) {
        updateHover(mx, my);
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i);
            if (child instanceof Panel) {
                ((Panel) child).updateHoverTree(mx, my);
            } else {
                child.updateHover(mx, my);
            }
        }
    }

    @Override
    public void cancelInteractions() {
        super.cancelInteractions();
        // 不检查可见性：隐藏子树里残留的拖动态同样需要复位
        for (int i = 0; i < children.size(); i++) {
            children.get(i).cancelInteractions();
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        // 释放事件不按命中测试，而是广播给每个子控件：可能同时有多个手势在进行
        // （如一个滑块拖拽 + 一个取色器拖拽），只喂给首个消费者会让其余控件的
        // dragging 永远为 true。也不检查可见性——容器在拖拽途中被隐藏时，
        // 隐藏的子树同样需要收到释放，否则拖动态残留到下次打开。
        boolean consumed = false;
        for (int i = children.size() - 1; i >= 0; i--) {
            if (children.get(i).mouseReleased(mx, my, button)) {
                consumed = true;
            }
        }
        return consumed;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 同上：拖拽可能移出原控件，需广播给所有子控件
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
        // 仅将滚轮投递给指针覆盖的子控件；否则视为自身消费
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
        if (!visible) {
            return false;
        }
        // 键盘无坐标，逆序广播，首个消费的子控件即终止传播；
        // 不可见的子控件不参与（D24），否则隐藏面板会吞掉按键。
        for (int i = children.size() - 1; i >= 0; i--) {
            Component child = children.get(i);
            if (child.isVisible() && child.keyPressed(keyCode, modifiers)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转发字符输入给可见子控件；首个消费即终止。
     *
     * <p>基类有钩子但本类从未转发（D24），文本编辑类控件永远收不到字符。
     */
    @Override
    public boolean charTyped(char character) {
        if (!visible) {
            return false;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            Component child = children.get(i);
            if (child.isVisible() && child.charTyped(character)) {
                return true;
            }
        }
        return false;
    }
}
