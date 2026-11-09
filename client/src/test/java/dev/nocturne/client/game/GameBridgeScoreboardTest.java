package dev.nocturne.client.game;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计分板桥的跨版本回归：int 槽位、Objective 重载、排序/15 行上限、owner 类型与抑制。
 */
class GameBridgeScoreboardTest {

    private NocturneClient client;
    private GameBridge bridge;

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
        client = NocturneClient.boot(null);
        bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));
        client.setGameBridge(bridge);
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @Test
    void rowsAreSortedByScoreAndCappedAtFifteen() {
        List<FakeScoreboard.Entry> entries = new ArrayList<FakeScoreboard.Entry>();
        for (int i = 0; i < 20; i++) {
            entries.add(new FakeScoreboard.Entry("p" + i, i));
        }
        FakeMinecraft.instance.level = new FakeScoreboard.Level(
                new FakeScoreboard.Board(new FakeScoreboard.Objective("Obj"), entries));

        List<String> lines = bridge.scoreboardLines();

        assertEquals(16, lines.size(), "标题 + 最多 15 行分数");
        assertEquals("Obj", lines.get(0));
        assertTrue(lines.get(1).endsWith(" 19"), "分值必须降序");
        assertTrue(lines.get(15).endsWith(" 5"), "第 15 行是 19-14=5");
    }

    @Test
    void equalScoresFallBackToOwnerOrder() {
        FakeMinecraft.instance.level = new FakeScoreboard.Level(
                new FakeScoreboard.OverloadBoard(new FakeScoreboard.Objective("Obj"),
                        Arrays.asList(new FakeScoreboard.LegacyScore("beta", 5),
                                new FakeScoreboard.LegacyScore("alpha", 5),
                                new FakeScoreboard.LegacyScore("gamma", 10))));

        List<String> lines = bridge.scoreboardLines();

        assertEquals(4, lines.size());
        assertTrue(lines.get(1).endsWith("gamma 10"));
        assertTrue(lines.get(2).endsWith("alpha 5"), "同分按 owner 升序");
        assertTrue(lines.get(3).endsWith("beta 5"));
    }

    @Test
    void legacyBoardsReadSortedScores() {
        FakeMinecraft.instance.level = new FakeScoreboard.Level(
                new FakeScoreboard.SortedBoard(new FakeScoreboard.Objective("Obj"),
                        Collections.singletonList(new FakeScoreboard.LegacyScore("Steve", 3))));

        List<String> lines = bridge.scoreboardLines();

        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith("Steve 3"));
    }

    @Test
    void ownerObjectsUseTheirName() {
        FakeMinecraft.instance.level = new FakeScoreboard.Level(new NamedBoard(
                new FakeScoreboard.Objective("Obj"),
                Collections.singletonList(new FakeScoreboard.NamedOwnerEntry("Steve", 42))));

        List<String> lines = bridge.scoreboardLines();

        assertEquals(2, lines.size());
        assertTrue(lines.get(1).endsWith("Steve 42"));
    }

    @Test
    void objectiveWithoutScoresDoesNotDrawAnEmptyPanel() {
        FakeMinecraft.instance.level = new FakeScoreboard.Level(
                new FakeScoreboard.Board(new FakeScoreboard.Objective("Empty"),
                        Collections.<FakeScoreboard.Entry>emptyList()));

        assertTrue(bridge.scoreboardLines().isEmpty());
    }

    @Test
    void hidingVanillaSidebarKeepsRowsAndRestoresObjective() {
        HideBoard board = new HideBoard(new FakeScoreboard.Objective("Obj"),
                Collections.singletonList(new FakeScoreboard.Entry("Steve", 1)));
        FakeMinecraft.instance.level = new FakeScoreboard.Level(board);

        assertTrue(bridge.scoreboardLines().contains("Obj"));
        bridge.setVanillaScoreboardHidden(true);

        assertNull(board.shown, "原版侧边栏的显示槽必须清空");
        List<String> lines = bridge.scoreboardLines();
        assertEquals(2, lines.size(), "抑制后仍要能用缓存的 objective 取分数");
        assertTrue(lines.get(1).endsWith("Steve 1"));

        bridge.setVanillaScoreboardHidden(false);
        assertSame(board.objective, board.shown, "关闭抑制后显示槽恢复原 objective");
    }

    /** 1.8.9–1.20.2 的 int 槽位：0 是 tab 列表，1 才是侧边栏。 */
    @Test
    void legacyIntSlotIsSidebar() {
        assertEquals(Integer.valueOf(1), GameBridge.sidebarSlot(int.class));
        assertEquals(Integer.valueOf(1), GameBridge.sidebarSlot(Integer.class));
    }

    /** 26.x 的 owner() 返回对象（NameAndId 形态）的替身计分板。 */
    public static final class NamedBoard {
        private final FakeScoreboard.Objective objective;
        private final List<FakeScoreboard.NamedOwnerEntry> entries;

        NamedBoard(FakeScoreboard.Objective objective, List<FakeScoreboard.NamedOwnerEntry> entries) {
            this.objective = objective;
            this.entries = entries;
        }

        public Object getDisplayObjective(FakeScoreboard.DisplaySlot slot) {
            return slot == FakeScoreboard.DisplaySlot.SIDEBAR ? objective : null;
        }

        public Collection<FakeScoreboard.NamedOwnerEntry> listPlayerScores(Object objective) {
            return entries;
        }
    }

    /** 带可写显示槽的计分板，验证抑制/恢复。 */
    public static final class HideBoard {
        private final FakeScoreboard.Objective objective;
        private final List<FakeScoreboard.Entry> entries;
        private Object shown;

        HideBoard(FakeScoreboard.Objective objective, List<FakeScoreboard.Entry> entries) {
            this.objective = objective;
            this.entries = entries;
            this.shown = objective;
        }

        public Object getDisplayObjective(FakeScoreboard.DisplaySlot slot) {
            return shown;
        }

        public void setDisplayObjective(FakeScoreboard.DisplaySlot slot, Object objective) {
            this.shown = objective;
        }

        public Collection<FakeScoreboard.Entry> listPlayerScores(Object objective) {
            return entries;
        }
    }
}
