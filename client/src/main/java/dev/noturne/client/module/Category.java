package dev.noturne.client.module;

/**
 * ClickGUI 中展示的功能分组。
 *
 * <p>契约：本枚举只列出真正被使用的分组，具体归类由 {@link Module#category()} 决定。GUI 渲染
 * 必须<b>跳过在 {@link ModuleRegistry#byCategory(Category)} 中返回空列表的分组</b>——不得只按
 * {@code values()} 无条件建列，否则空列既占位又会把靠右的列挤出屏幕。新增分组时必须同时有至少
 * 一个模块归入其中。
 */
public enum Category {
    /** 移动相关。 */
    MOVEMENT,
    /** 渲染相关。 */
    RENDER,
    /** 玩家自身相关。 */
    PLAYER,
    /** 杂项；当前仅测试模块使用。 */
    MISC
}
