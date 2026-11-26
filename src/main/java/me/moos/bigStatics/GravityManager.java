package me.moos.bigStatics;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public class GravityManager implements Listener {
    /*
    If ENABLE_BLOCK_GRAVITY is set to true, breaking a block will trigger a chain reaction of every neighbouring block
    being checked with StaticsManager.
    If a block is deemed unsupported, it will be moved down until it is no longer floating.
    */
    static final boolean ENABLE_BLOCK_GRAVITY = false;
    /*
    If ENABLE_BLOCK_GRAVITY is set to true, the queue will be processed at a maximum rate
    of GRAVITY_BLOCK_CHECKS_PER_TICK blocks per tick.
    Increasing this value may impact server TPS noticeably but will
    also reduce the time a big structure takes to collapse.
    Decrease this value first, if gravity is causing performance issues.
     */
    static final int GRAVITY_BLOCK_CHECKS_PER_TICK = 30;
    /*
    If ENABLE_BLOCK_GRAVITY is set to true, blocks of the type listed here will be treated
    differently than regular blocks to avoid ugly edge-cases.
     */
    static final Material[] BLOCKS_WITH_SPECIAL_CARE_TO_GRAVITY = new Material[] {
            Material.CHEST,
            Material.TRAPPED_CHEST,
            Material.FURNACE,
            Material.BLAST_FURNACE,
            Material.BARREL,
            Material.SMOKER,
            Material.BREWING_STAND,
            Material.COPPER_CHEST,
            Material.EXPOSED_COPPER_CHEST,
            Material.OXIDIZED_COPPER_CHEST,
            Material.WEATHERED_COPPER_CHEST,
            Material.WAXED_COPPER_CHEST,
            Material.WAXED_EXPOSED_COPPER_CHEST,
            Material.WAXED_OXIDIZED_COPPER_CHEST,
            Material.WAXED_WEATHERED_COPPER_CHEST,
            Material.DROPPER,
            Material.DISPENSER,
            Material.HOPPER,
            Material.LECTERN,
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX,
            Material.OAK_SHELF,
            Material.SPRUCE_SHELF,
            Material.BIRCH_SHELF,
            Material.JUNGLE_SHELF,
            Material.ACACIA_SHELF,
            Material.DARK_OAK_SHELF,
            Material.MANGROVE_SHELF,
            Material.CHERRY_SHELF,
            Material.PALE_OAK_SHELF,
            Material.BAMBOO_SHELF,
            Material.CRIMSON_SHELF,
            Material.WARPED_SHELF
    };
    /*
    If HANDLE_SPECIAL_CARE_BLOCKS_BY_BREAKING_THEM is set to true, blocks of a type listed in
    BLOCKS_WITH_SPECIAL_CARE_TO_GRAVITY will be broken instead of moved so they (and potential
    inventory content) drop as items.
    Otherwise, they will just be left floating and not manipulated at all.
     */
    static final boolean HANDLE_SPECIAL_CARE_BLOCKS_BY_BREAKING_THEM = true;

    ConcurrentLinkedDeque<Location> BlocksToCheckQueue = new ConcurrentLinkedDeque<>();
    private BigStatics PluginInstance;
    private StaticsManager StaticsManager;
    private ScheduledTask BlockCheckerTask = null;
    private List<Material> BlockTypesWithSpecialHandling = List.of(BLOCKS_WITH_SPECIAL_CARE_TO_GRAVITY);

    public GravityManager(BigStatics pluginInstance, StaticsManager staticsManager) {
        PluginInstance = pluginInstance;
        StaticsManager = staticsManager;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!ENABLE_BLOCK_GRAVITY || event.isCancelled() || !event.getBlock().isSolid()) return;

        Block blockAbove = event.getBlock();
        if (blockAbove.isSolid() && !StaticsManager.isBlockSupportedByWater(blockAbove)) {
            BlocksToCheckQueue.addFirst(blockAbove.getRelative(BlockFace.UP).getLocation());

            if (BlockCheckerTask == null) {
                BlockCheckerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(PluginInstance, scheduledTask -> {
                    //PluginInstance.getLogger().info("Running checkBlocks()");
                    checkBlocks();
                }, 1, 1);
            }
        }
    }

    void checkBlocks() {
        for (int i = 0; i < GRAVITY_BLOCK_CHECKS_PER_TICK; i++) {
            if (BlocksToCheckQueue.isEmpty()) return;
            Location nextLoc = BlocksToCheckQueue.removeFirst();
            while (!BlocksToCheckQueue.isEmpty() && nextLoc.getBlock().getType().isAir()) {
                nextLoc = BlocksToCheckQueue.removeFirst();
            }
            Location loc = nextLoc;
            Bukkit.getRegionScheduler().run(PluginInstance, loc, scheduledTask -> {
                if (StaticsManager.computeStructureComplexity(loc) == Integer.MAX_VALUE) {
                    Block b = loc.getBlock();
                    if (!b.getRelative(BlockFace.DOWN).getType().isAir())
                        BlocksToCheckQueue.addFirst(b.getRelative(BlockFace.DOWN).getLocation());
                    if (!b.getRelative(BlockFace.EAST).getType().isAir())
                        BlocksToCheckQueue.addLast(b.getRelative(BlockFace.EAST).getLocation());
                    if (!b.getRelative(BlockFace.SOUTH).getType().isAir())
                        BlocksToCheckQueue.addLast(b.getRelative(BlockFace.SOUTH).getLocation());
                    if (!b.getRelative(BlockFace.NORTH).getType().isAir())
                        BlocksToCheckQueue.addLast(b.getRelative(BlockFace.NORTH).getLocation());
                    if (!b.getRelative(BlockFace.WEST).getType().isAir())
                        BlocksToCheckQueue.addLast(b.getRelative(BlockFace.WEST).getLocation());
                    if (!b.getRelative(BlockFace.UP).getType().isAir())
                        BlocksToCheckQueue.addLast(b.getRelative(BlockFace.UP).getLocation());

                    if (BlockTypesWithSpecialHandling.contains(b.getType())) {
                        if (HANDLE_SPECIAL_CARE_BLOCKS_BY_BREAKING_THEM) {
                            b.breakNaturally();
                        }
                    }
                    else {
                        if (b.getY() > loc.getWorld().getMinHeight()) {
                            b = b.getRelative(BlockFace.DOWN);
                        }
                        while (!b.getType().isSolid() && b.getY() > loc.getWorld().getMinHeight()) {
                            b = b.getRelative(BlockFace.DOWN);
                        }

                        if (b.isSolid() && !b.equals(loc.getBlock())) {
                            b.getRelative(BlockFace.UP).setBlockData(loc.getBlock().getBlockData());
                            loc.getBlock().setType(Material.AIR);
                        }
                    }
                }
            });
        }
    }
}
