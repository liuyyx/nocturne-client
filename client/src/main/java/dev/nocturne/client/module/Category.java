package dev.nocturne.client.module;

/**
 * ClickGUI 中展示的功能分组。
 *
 * <p>具体归类由 {@link Module#category()} 决定。GUI 渲染必须跳过在
 * {@link ModuleRegistry#byCategory(Category)} 中返回空列表的分组；预留分类只有注册模块后才展示。
 */
public enum Category {
    /** 战斗相关。 */
    COMBAT,
    /** 移动相关。 */
    MOVEMENT,
    /** 渲染相关。 */
    RENDER,
    /** 世界交互。 */
    WORLD,
    /** 背包与物品管理。 */
    INVENTORY,
    /** 玩家自身相关。 */
    PLAYER,
    /** 杂项。 */
    MISC
}
