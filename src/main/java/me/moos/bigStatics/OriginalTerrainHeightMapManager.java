package me.moos.bigStatics;

import com.destroystokyo.paper.MaterialSetTag;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OriginalTerrainHeightMapManager implements Listener {
    /*
    If USE_EXPENSIVE_GENERATION is set to true, the heightmap generation will iterate
    over at least 16 * 16 * EXPENSIVE_GENERATION_MAX_DEPTH blocks per chunk in the best case!!!
    The benefit of that is that many things like trees and most structures will no longer be detected as terrain.
    It also allows to set a minimum thickness of anchor blocks (EXPENSIVE_GENERATION_MAX_DEPTH) before a position
    is recognized as the anchor height.
    If USE_EXPENSIVE_GENERATION is set to false, the heightmap generation will never
    iterate over actual blocks and uses the output of Bukkit's OCEAN_FLOOR heightmap directly.
     */
    static final boolean USE_EXPENSIVE_GENERATION = false;
    /*
    If USE_EXPENSIVE_GENERATION is set to true, the heightmap generation will only
    detect the highest anchor block with at least EXPENSIVE_GENERATION_MAX_DEPTH amount
    of other anchor blocks directly below it as the terrain height.
    Increasing this value could impact generation performance drastically depending
    on how often the world generation generates the top-most anchor block with
    non-anchor blocks below it.
     */
    static final int EXPENSIVE_GENERATION_MAX_DEPTH = 3;
    /*
    If USE_EXPENSIVE_GENERATION is set to true, the heightmap generation will only allow blocks
    with a material listed here to be detected as terrain.
    By default, this is optimized for Big World's world generation.
    */
    static final Material[] EXPENSIVE_GENERATION_BLOCK_WHITELIST = new Material[] {
            Material.STONE,
            Material.ANDESITE,
            Material.DIORITE,
            Material.GRANITE,
            Material.SANDSTONE,
            Material.RED_SANDSTONE,
            Material.TUFF,
            Material.DEEPSLATE,
            Material.SMOOTH_BASALT,
            Material.BEDROCK,
            Material.INFESTED_STONE,
            Material.BASALT,
            Material.NETHERRACK,
            Material.END_STONE,
            Material.COAL_ORE,
            Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE,
            Material.DEEPSLATE_COPPER_ORE,
            Material.RAW_COPPER_BLOCK,
            Material.IRON_ORE,
            Material.DEEPSLATE_IRON_ORE,
            Material.RAW_IRON_BLOCK,
            Material.GOLD_ORE,
            Material.DEEPSLATE_GOLD_ORE,
            Material.RAW_GOLD_BLOCK,
            Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE,
            Material.REDSTONE_ORE,
            Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE,
            Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_GOLD_ORE,
            Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS,
            Material.BLACKSTONE,
            Material.CALCITE,
            Material.TERRACOTTA,
            Material.BLACK_TERRACOTTA,
            Material.BLUE_TERRACOTTA,
            Material.BROWN_TERRACOTTA,
            Material.CYAN_TERRACOTTA,
            Material.GRAY_TERRACOTTA,
            Material.GREEN_TERRACOTTA,
            Material.LIGHT_BLUE_TERRACOTTA,
            Material.LIGHT_GRAY_TERRACOTTA,
            Material.LIME_TERRACOTTA,
            Material.MAGENTA_TERRACOTTA,
            Material.ORANGE_TERRACOTTA,
            Material.PINK_TERRACOTTA,
            Material.PURPLE_TERRACOTTA,
            Material.RED_TERRACOTTA,
            Material.WHITE_TERRACOTTA,
            Material.YELLOW_TERRACOTTA
    };
    static final String[] BLACKLISTED_WORLDS = {
            "minecraft:the_nether"
    };

    static final ConcurrentHashMap<NamespacedKey, ConcurrentHashMap<Long, short[]>> LoadedChunkHeightMaps = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<NamespacedKey, Set<Long>> NewGeneratedChunks = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<NamespacedKey, Set<Long>> PendingLoads = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Object> FileLocks = new ConcurrentHashMap<>();

    private static BigStatics PluginInstance;

    private static final int DATA_SIZE_PER_CHUNK = 512; // 16*16 * 2 bytes
    private static final int BITSET_SIZE = 128; // 1024 bits / 8
    private static MaterialSetTag ExpensiveGenerationBlockWhitelistTag;

    public OriginalTerrainHeightMapManager(BigStatics pluginInstance) {
        PluginInstance = pluginInstance;
        ExpensiveGenerationBlockWhitelistTag = new MaterialSetTag(new NamespacedKey(PluginInstance, "expensive_generation_block_whitelist"), EXPENSIVE_GENERATION_BLOCK_WHITELIST);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        NamespacedKey worldKey = event.getWorld().getKey();
        long chunkKey = event.getChunk().getChunkKey();
        ConcurrentHashMap<Long, short[]> worldMap = LoadedChunkHeightMaps.get(worldKey);

        if (worldMap == null) {
            return;
        }

        if (worldMap.containsKey(chunkKey)) {
            return;
        }

        Set<Long> pending = PendingLoads.computeIfAbsent(worldKey, k -> ConcurrentHashMap.newKeySet());
        if (!pending.add(chunkKey)) {
            return; // Already loading this chunk
        }

        Bukkit.getAsyncScheduler().runNow(JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class), task -> {
            short[] loaded = loadChunkHeightMap(chunkKey, worldKey);

            if (loaded.length != 0) {
                if (pending.contains(chunkKey)) {
                    worldMap.put(chunkKey, loaded);
                    pending.remove(chunkKey);
                }
            } else {
                int chunkX = event.getChunk().getX();
                int chunkZ = event.getChunk().getZ();
                Bukkit.getRegionScheduler().run(JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class), event.getWorld(), chunkX, chunkZ, syncTask -> {
                    if (pending.contains(chunkKey)) {
                        Chunk chunk = event.getChunk();
                        short[] generated = generateChunkHeightMap(chunk);
                        worldMap.put(chunkKey, generated);
                        NewGeneratedChunks.computeIfAbsent(event.getWorld().getKey(), k -> ConcurrentHashMap.newKeySet()).add(chunkKey);
                    }
                    pending.remove(chunkKey);
                });
            }
        });
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        NamespacedKey worldKey = event.getWorld().getKey();
        long chunkKey = event.getChunk().getChunkKey();

        Set<Long> pending = PendingLoads.get(worldKey);
        if (pending != null) {
            pending.remove(chunkKey);
        }

        ConcurrentHashMap<Long, short[]> worldMap = LoadedChunkHeightMaps.get(worldKey);
        if (worldMap == null || !worldMap.containsKey(chunkKey)) {
            return;
        }

        // Save before removing from cache to ensure data persistence
        Bukkit.getAsyncScheduler().runNow(JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class), task -> {
            saveChunkHeightMapData(worldKey, chunkKey);
            worldMap.remove(chunkKey);
        });
    }

    private static short[] loadChunkHeightMap(long chunkKey, NamespacedKey worldKey) {
        int chunkX = (int) chunkKey;
        int chunkZ = (int) (chunkKey >> 32);

        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;

        String dataFolder = JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class).getDataFolder().getPath();
        File heightMapFile = new File(dataFolder + "/OriginalHeightMaps/" + worldKey.asString().replace(":", "_") + "/r." + regionX + "." + regionZ + ".bhm");

        if (!heightMapFile.exists()) {
            return new short[0];
        }

        int localIndex = (chunkX & 31) + ((chunkZ & 31) * 32);
        String regionKey = worldKey.asString() + ":" + regionX + ":" + regionZ;

        synchronized (FileLocks.computeIfAbsent(regionKey, k -> new Object())) {
            try (FileChannel fileReader = FileChannel.open(heightMapFile.toPath(), StandardOpenOption.READ)) {
                // Check if the chunk is present using the bitset header
                ByteBuffer bitsetBuffer = ByteBuffer.allocate(BITSET_SIZE);
                fileReader.read(bitsetBuffer, 0);
                bitsetBuffer.flip();

                byte[] bitset = bitsetBuffer.array();
                int byteIdx = localIndex / 8;
                int bitIdx = localIndex % 8;

                if ((bitset[byteIdx] & (1 << bitIdx)) == 0) {
                    return new short[0]; // Chunk data not present in this region file
                }

                // Read fixed-offset heightmap data
                ByteBuffer heightMapBuffer = ByteBuffer.allocate(DATA_SIZE_PER_CHUNK);
                long dataPosition = (long) BITSET_SIZE + ((long) localIndex * DATA_SIZE_PER_CHUNK);
                fileReader.read(heightMapBuffer, dataPosition);
                heightMapBuffer.flip();

                short[] output = new short[256];
                for (int i = 0; i < 256; i++) {
                    output[i] = heightMapBuffer.getShort();
                }

                return output;

            } catch (IOException e) {
                PluginInstance.getLogger().severe("Failed to load heightmap: " + e.getMessage());
                return new short[0];
            }
        }
    }

    public static int getY(int blockX, int blockZ, World world) {
        if (LoadedChunkHeightMaps.containsKey(world.getKey())) {
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long chunkKey = (((long) chunkZ) << 32) | (chunkX & 0xffffffffL);
            ConcurrentHashMap<Long, short[]> worldMap = LoadedChunkHeightMaps.get(world.getKey());
            if (worldMap != null && worldMap.containsKey(chunkKey)) {
                short[] heightMap = worldMap.get(chunkKey);
                if (heightMap == null) {
                    PluginInstance.getLogger().severe("heightMap for location " + blockX + ", " + blockZ + " in world " + world.getKey().asString() + " is null!");
                    return -999;
                }
                int localX = blockX & 15;
                int localZ = blockZ & 15;
                int index = localX + (localZ * 16);
                return heightMap[index];
            }

            PluginInstance.getLogger().severe("Tried to use heightmap of unloaded chunk");
            return 999;
        }


        String worldKeyString = world.getKey().asString();
        if (Arrays.asList(BLACKLISTED_WORLDS).contains(worldKeyString))
            return world.getMaxHeight();

        PluginInstance.getLogger().severe("Tried to use heightmap of uncached world " + world.getKey().asString());
        return 999;
    }

    private short[] generateChunkHeightMap(Chunk chunk) {
        short[] heightmap = new short[16 * 16];
        World world = chunk.getWorld();
        int heightmapIndex = 0;
        for (int localBlockX = 0; localBlockX < 16; localBlockX++) {
            for (int localBlockZ = 0; localBlockZ < 16; localBlockZ++) {
                int globalBlockY = world.getHighestBlockYAt((chunk.getX() * 16) + localBlockX, (chunk.getZ() * 16) + localBlockZ, HeightMap.OCEAN_FLOOR);

                if (USE_EXPENSIVE_GENERATION) {
                    boolean validBlockFound = false;
                    while (globalBlockY > chunk.getWorld().getMinHeight() && !validBlockFound) {
                        Block block = world.getBlockAt((chunk.getX() * 16) + localBlockX, globalBlockY, (chunk.getZ() * 16) + localBlockZ);
                        validBlockFound = isBlockValidForExpensiveGeneration(block, 0);

                        if (!validBlockFound) {
                            globalBlockY--;
                        }
                    }
                }

                heightmap[heightmapIndex] = (short)globalBlockY;
                heightmapIndex++;
            }
        }

        return heightmap;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        NamespacedKey worldKey = event.getWorld().getKey();
        String worldKeyString = worldKey.asString();
        if (Arrays.asList(BLACKLISTED_WORLDS).contains(worldKeyString))
            return;

        LoadedChunkHeightMaps.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>());
        NewGeneratedChunks.computeIfAbsent(worldKey, k -> ConcurrentHashMap.newKeySet());

        PluginInstance.getLogger().info("Added world '" + worldKey.asString() + "' to the heightmap cache.");
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        NamespacedKey worldKey = event.getWorld().getKey();
        PluginInstance.getLogger().info("Saving heightmap data of " + worldKey.asString());

        ConcurrentHashMap<Long, short[]> worldMap = LoadedChunkHeightMaps.get(worldKey);
        if (worldMap == null) {
            return;
        }

        Bukkit.getAsyncScheduler().runNow(JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class), task -> {
            for (Long chunkKey : worldMap.keySet()) {
                saveChunkHeightMapData(worldKey, chunkKey);
            }

            LoadedChunkHeightMaps.remove(worldKey);
            NewGeneratedChunks.remove(worldKey);

            PluginInstance.getLogger().info("Removed world '" + worldKey.asString() + "' from cache.");
        });
    }

    public void onServerStart() {
        /*
            Since BigStatics used to rely on a directory structure like "./plugins/BigStatics/OriginalHeightMaps/minecraft:overworld/" the plugin was not functional on Windows.
            Windows can not work with colons (':') in file paths!
            To fix this, we run this logic on server startup that will replace every colon within a directory name within the OriginalHeightMaps directory with an underscore ('_').
         */
        Path parent = JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class).getDataPath().resolve("OriginalHeightMaps");
        try {
            if (parent.toFile().exists()) {
                var entries = Files.list(parent);
                entries.filter(Files::isDirectory).forEach(dir -> {
                    String name = dir.getFileName().toString();

                    if (name.contains(":")) {
                        Path renamed = dir.resolveSibling(name.replace(':', '_'));
                        try {
                            PluginInstance.getLogger().info("Migrating directory: " + dir.toString() + " -> " + renamed.toString());
                            Files.move(dir, renamed);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
        }
        catch (Exception e) {
            PluginInstance.getLogger().severe("Exception thrown while trying to run file path migration logic: " + e);
        }

        if (USE_EXPENSIVE_GENERATION) {
            PluginInstance.getLogger().warning("""
                    USE_EXPENSIVE_GENERATION is enabled (compile-time)!
                    While it is not game-breaking, it can cause major TPS drops during chunk generation right now.
                    You can ignore this, if you pregenerate your world completely.
                    A fix is already being worked on, but it may take some time until it releases.
                    """);
        }
    }

    public void onServerStop() {
        PluginInstance.getLogger().info("Saving heightmap data!");
        saveAllHeightMapData();
    }

    // Recursion! Scary!
    private boolean isBlockValidForExpensiveGeneration(Block block, int currentDepth) {
        boolean valid = ExpensiveGenerationBlockWhitelistTag.isTagged(block);
        if (valid && currentDepth < EXPENSIVE_GENERATION_MAX_DEPTH && block.getY() > block.getWorld().getMinHeight()) {
            valid = isBlockValidForExpensiveGeneration(block.getRelative(BlockFace.DOWN), currentDepth + 1);
        }

        return valid;
    }

    private static void saveAllHeightMapData() {
        for (NamespacedKey worldKey : LoadedChunkHeightMaps.keySet()) {
            ConcurrentHashMap<Long, short[]> worldMap = LoadedChunkHeightMaps.get(worldKey);
            if (worldMap != null) {
                for (Long chunkKey : worldMap.keySet()) {
                    saveChunkHeightMapData(worldKey, chunkKey);
                }
            }
        }
    }

    private static void saveChunkHeightMapData(NamespacedKey worldKey, long chunkKey) {
        Set<Long> newChunks = NewGeneratedChunks.get(worldKey);
        if (newChunks == null || !newChunks.contains(chunkKey)) {
            return;
        }

        ConcurrentHashMap<Long, short[]> worldMap = LoadedChunkHeightMaps.get(worldKey);
        if (worldMap == null) {
            return;
        }

        short[] heightmapData = worldMap.get(chunkKey);
        if (heightmapData == null) {
            return;
        }

        int chunkX = (int) chunkKey;
        int chunkZ = (int) (chunkKey >> 32);
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;

        String dataFolder = JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class).getDataFolder().getPath();
        File heightMapFile = new File(dataFolder + "/OriginalHeightMaps/" + worldKey.asString().replace(':', '_') + "/r." + regionX + "." + regionZ + ".bhm");
        String regionKey = worldKey.asString() + ":" + regionX + ":" + regionZ;

        int localIndex = (chunkX & 31) + ((chunkZ & 31) * 32);

        synchronized (FileLocks.computeIfAbsent(regionKey, k -> new Object())) {
            try {
                if (!heightMapFile.exists()) {
                    heightMapFile.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(heightMapFile)) {
                        // Pre-allocate: Bitset + Total Chunk Data
                        out.write(new byte[BITSET_SIZE + (1024 * DATA_SIZE_PER_CHUNK)]);
                    }
                }

                try (FileChannel channel = FileChannel.open(heightMapFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    // 1. Write Heightmap Data at fixed offset
                    ByteBuffer dataBuffer = ByteBuffer.allocate(DATA_SIZE_PER_CHUNK);
                    for (short s : heightmapData) {
                        dataBuffer.putShort(s);
                    }
                    dataBuffer.flip();
                    long dataPosition = (long) BITSET_SIZE + ((long) localIndex * DATA_SIZE_PER_CHUNK);
                    writeFully(channel, dataBuffer, dataPosition);

                    // 2. Update Bitset Header to mark chunk as present
                    ByteBuffer bitsetBuffer = ByteBuffer.allocate(BITSET_SIZE);
                    channel.read(bitsetBuffer, 0);
                    bitsetBuffer.flip();
                    byte[] bitset = bitsetBuffer.array();

                    int byteIdx = localIndex / 8;
                    int bitIdx = localIndex % 8;
                    bitset[byteIdx] |= (1 << bitIdx);

                    ByteBuffer updatedBitset = ByteBuffer.wrap(bitset);
                    writeFully(channel, updatedBitset, 0);
                }

                // Mark as no longer "new" since it's now saved
                Set<Long> chunks = NewGeneratedChunks.get(worldKey);
                if (chunks != null) {
                    chunks.remove(chunkKey);
                }

            } catch (IOException e) {
                PluginInstance.getLogger().severe("Failed to save heightmap: " + e.getMessage());
            }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }
}