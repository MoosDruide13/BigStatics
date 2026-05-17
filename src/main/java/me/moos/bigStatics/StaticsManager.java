package me.moos.bigStatics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;

public class StaticsManager implements Listener {

    @EventHandler
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (event.isCancelled()) return;

        int complexity = computeStructureComplexity(event.getBlock().getLocation());
        event.getPlayer().sendActionBar(Component.text("Complexity: " + complexity));

        if (complexity == Integer.MAX_VALUE)
        {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("This location can not support a block here!").appendNewline().append(Component.text("You could build additional support pillars.")));
        }
    }

    public int computeStructureComplexity(Location start) {

        World world = start.getWorld();

        record Node(int x, int y, int z, int cost) {}

        ArrayDeque<Node> deque = new ArrayDeque<>();

        // shortest known complexity to each block
        HashMap<Long, Integer> bestCost = new HashMap<>();

        deque.addFirst(new Node(
                start.getBlockX(),
                start.getBlockY(),
                start.getBlockZ(),
                0
        ));

        while (!deque.isEmpty()) {

            Node node = deque.pollFirst();

            int x = node.x();
            int y = node.y();
            int z = node.z();
            int cost = node.cost();

            if (cost > 20) {
                continue;
            }

            long key = pack(x, y, z);
            Integer known = bestCost.get(key);

            // already found better route
            if (known != null && known <= cost) {
                continue;
            }

            bestCost.put(key, cost);

            int terrainY = OriginalTerrainHeightMapManager.getY(x, z, world);

            // reached natural terrain
            if (terrainY >= y && world.getBlockAt(x, terrainY - 1, z).isSolid()) {
                return cost;
            }

            if (world.getBlockAt(x, y - 1, z).isSolid()) {
                deque.addFirst(new Node(x, y - 1, z, cost));
            }

            if (world.getBlockAt(x + 1, y, z).isSolid()) {
                deque.addLast(new Node(x + 1, y, z, cost + 1));
            }

            if (world.getBlockAt(x - 1, y, z).isSolid()) {
                deque.addLast(new Node(x - 1, y, z, cost + 1));
            }

            if (world.getBlockAt(x, y, z + 1).isSolid()) {
                deque.addLast(new Node(x, y, z + 1, cost + 1));
            }

            if (world.getBlockAt(x, y, z - 1).isSolid()) {
                deque.addLast(new Node(x, y, z - 1, cost + 1));
            }
        }

        return Integer.MAX_VALUE;
    }

    private long pack(int x, int y, int z) {
        return (((long)x & 0x3FFFFFF) << 38)
                | (((long)z & 0x3FFFFFF) << 12)
                | ((long)y & 0xFFF);
    }
}
