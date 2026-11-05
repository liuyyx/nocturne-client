package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.game.GameBridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** ElytraCombat 的体素 A*：只在可通行空气格间搜索，并受节点/半径硬上限约束。 */
final class ElytraPathfinder {
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private ElytraPathfinder() {
    }

    static double[] next(GameBridge bridge, double[] from, double[] to, int radius, int maxNodes) {
        Node start = node(from);
        Node goal = node(to);
        PriorityQueue<Node> open = new PriorityQueue<Node>(Comparator.comparingDouble(n -> n.score));
        Map<Long, Node> nodes = new HashMap<Long, Node>();
        Set<Long> closed = new HashSet<Long>();
        start.cost = 0.0;
        start.score = distance(start, goal);
        open.add(start);
        nodes.put(key(start), start);
        int visited = 0;
        while (!open.isEmpty() && visited++ < maxNodes) {
            Node current = open.poll();
            if (!closed.add(key(current))) {
                continue;
            }
            if (same(current, goal)) {
                return firstStep(start, current, from);
            }
            for (int[] offset : NEIGHBORS) {
                Node next = new Node(current.x + offset[0], current.y + offset[1], current.z + offset[2]);
                if (Math.abs(next.x - start.x) > radius || Math.abs(next.y - start.y) > radius
                        || Math.abs(next.z - start.z) > radius || closed.contains(key(next))
                        || !passable(bridge, next)) {
                    continue;
                }
                double cost = current.cost + 1.0;
                Node known = nodes.get(key(next));
                if (known != null && known.cost <= cost) {
                    continue;
                }
                next.cost = cost;
                next.score = cost + distance(next, goal);
                next.parent = current;
                nodes.put(key(next), next);
                open.add(next);
            }
        }
        return null;
    }

    private static double[] firstStep(Node start, Node goal, double[] from) {
        Node step = goal;
        while (step.parent != null && !same(step.parent, start)) {
            step = step.parent;
        }
        return new double[]{step.x + 0.5, step.y + 0.5, step.z + 0.5};
    }

    private static boolean passable(GameBridge bridge, Node node) {
        return bridge.isAir(bridge.blockStateAt(node.x, node.y, node.z));
    }

    private static Node node(double[] position) {
        return new Node((int) Math.floor(position[0]), (int) Math.floor(position[1]),
                (int) Math.floor(position[2]));
    }

    private static long key(Node node) {
        return ((long) node.x & 0x3FFFFFFL) << 38 | ((long) node.y & 0xFFFL) << 26
                | (long) node.z & 0x3FFFFFFL;
    }

    private static boolean same(Node a, Node b) {
        return a.x == b.x && a.y == b.y && a.z == b.z;
    }

    private static double distance(Node a, Node b) {
        return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2) + Math.pow(a.z - b.z, 2));
    }

    private static final class Node {
        private final int x;
        private final int y;
        private final int z;
        private double cost;
        private double score;
        private Node parent;

        private Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
