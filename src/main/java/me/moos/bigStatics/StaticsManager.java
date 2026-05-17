package me.moos.bigStatics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.HashSet;

public class StaticsManager implements Listener {

    @EventHandler
    public void onBlockPlaced(BlockPlaceEvent event) {
        if (event.isCancelled()) return;

        //int y = OriginalTerrainHeightMapManager.getY(event.getBlock().getX(), event.getBlock().getZ(), event.getBlock().getWorld());
        //event.getPlayer().sendActionBar(Component.text("Heightmap: " + y));

        int complexity = computeStructureComplexityAtLocation(event.getBlock().getLocation(), new HashSet<Location>(), 0, 0);
        event.getPlayer().sendActionBar(Component.text("Complexity: " + complexity));
        //event.getPlayer().sendMessage(Component.text("Heightmap: " + event.getBlock().getWorld().getHighestBlockYAt(event.getBlock().getLocation(), HeightMap.OCEAN_FLOOR_WG)));

        if (complexity >= 500)
        {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("This location can not support a block here!").appendNewline().append(Component.text("You could build additional support pillars.")));
        }
    }

    public int computeStructureComplexityAtLocation(Location loc, HashSet<Location> checkedLocations, int existingComplexity, int currentSearchDepth) {
        // Avoid StackOverflowError
        if (currentSearchDepth > 300) {
            Bukkit.getLogger().warning("computeStructureComplecityAtLocation has reached a recursion depth of 500!");
            return Integer.MAX_VALUE;
        }

        if (existingComplexity > 500) {
            Bukkit.getLogger().warning("complexity limit reached for recursion search!");
            return Integer.MAX_VALUE;
        }

        currentSearchDepth++;

        // If this location already has been visited, it should not be included again
        if (checkedLocations.contains(loc)) return Integer.MAX_VALUE;//existingComplexity;
        checkedLocations.add(loc);

        int terrainY = OriginalTerrainHeightMapManager.getY(loc.getBlockX(), loc.getBlockZ(), loc.getWorld());//loc.getWorld().getHighestBlockYAt(loc, HeightMap.OCEAN_FLOOR);

        // Is this on the world surface? (World surface is always treated as "anchored" no matter what)
        if (terrainY >= loc.getBlockY() && loc.getWorld().getBlockAt(loc.getBlockX(), terrainY - 1, loc.getBlockZ()).isSolid()) {
            loc.getBlock().setType(Material.NETHERITE_BLOCK);
            return existingComplexity;
        }

        int computedComplexityDown = Integer.MAX_VALUE;
        int computedComplexityForwards = Integer.MAX_VALUE;
        int computedComplexityBackwards = Integer.MAX_VALUE;
        int computedComplexityLeft = Integer.MAX_VALUE;
        int computedComplexityRight = Integer.MAX_VALUE;

        World world = loc.getWorld();
        if (world.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ()).isSolid()) {
            computedComplexityDown = computeStructureComplexityAtLocation(loc.clone().add(0, -1, 0), checkedLocations, existingComplexity, currentSearchDepth);
        }

        if (computedComplexityDown > 500 && world.getBlockAt(loc.getBlockX() + 1, loc.getBlockY(), loc.getBlockZ()).isSolid()) {
            computedComplexityRight = computeStructureComplexityAtLocation(loc.clone().add(1, 0, 0), checkedLocations, existingComplexity + 1, currentSearchDepth);
        }

        if (computedComplexityRight > 500 && world.getBlockAt(loc.getBlockX() - 1, loc.getBlockY(), loc.getBlockZ()).isSolid()) {
            computedComplexityLeft = computeStructureComplexityAtLocation(loc.clone().add(-1, 0, 0), checkedLocations, existingComplexity + 1, currentSearchDepth);
        }

        if (computedComplexityLeft > 500 && world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ() + 1).isSolid()) {
            computedComplexityForwards = computeStructureComplexityAtLocation(loc.clone().add(0, 0, 1), checkedLocations, existingComplexity + 1, currentSearchDepth);
        }

        if (computedComplexityForwards > 500 && world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ() - 1).isSolid()) {
            computedComplexityBackwards = computeStructureComplexityAtLocation(loc.clone().add(0, 0, -1), checkedLocations, existingComplexity + 1, currentSearchDepth);
        }

        // return the least complex route we have found
        int leastComplexRoute = Math.min(computedComplexityDown, Math.min(Math.min(computedComplexityRight, computedComplexityLeft), Math.min(computedComplexityBackwards, computedComplexityForwards)));
        if (leastComplexRoute != Integer.MAX_VALUE) {
            return leastComplexRoute + existingComplexity;
        }

        return leastComplexRoute;
    }
}
