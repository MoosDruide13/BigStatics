package me.moos.bigStatics;

import org.bukkit.*;
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
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OriginalTerrainHeightMapManager implements Listener {
    static final String[] BLACKLISTED_WORLDS = {
        "minecraft:the_nether"
    };

    static final ConcurrentHashMap<NamespacedKey, ConcurrentHashMap<Long, short[]>> LoadedChunkHeightMaps = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<NamespacedKey, Set<Long>> NewGeneratedChunks = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<NamespacedKey, Set<Long>> PendingLoads = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Object> FileLocks = new ConcurrentHashMap<>();

    private static final int DATA_SIZE_PER_CHUNK = 512; // 16*16 * 2 bytes
    private static final int BITSET_SIZE = 128; // 1024 bits / 8

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
        File heightMapFile = new File(dataFolder + "/OriginalHeightMaps/" + worldKey.asString() + "/r." + regionX + "." + regionZ + ".bhm");

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
                Bukkit.getLogger().severe("Failed to load heightmap: " + e.getMessage());
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
                    Bukkit.getLogger().severe("heightMap for location " + blockX + ", " + blockZ + " in world " + world.getKey().asString() + " is null!");
                    return -999;
                }
                int localX = blockX & 15;
                int localZ = blockZ & 15;
                int index = localX + (localZ * 16);
                return heightMap[index];
            }

            Bukkit.getLogger().severe("Tried to use heightmap of unloaded chunk");
            return 999;
        }


        String worldKeyString = world.getKey().asString();
        if (Arrays.asList(BLACKLISTED_WORLDS).contains(worldKeyString))
            return 0;

        Bukkit.getLogger().severe("Tried to use heightmap of uncached world " + world.getKey().asString());
        return 999;
    }

    private static short[] generateChunkHeightMap(Chunk chunk) {
        short[] heightmap = new short[16 * 16];
        World world = chunk.getWorld();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                heightmap[x + (z * 16)] = (short)world.getHighestBlockYAt((chunk.getX() * 16) + x, (chunk.getZ() * 16) + z, HeightMap.OCEAN_FLOOR);
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

        Bukkit.getLogger().info("Added world '" + worldKey.asString() + "' to the heightmap cache.");
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        NamespacedKey worldKey = event.getWorld().getKey();
        Bukkit.getLogger().info("Saving heightmap data of " + worldKey.asString());

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

            Bukkit.getLogger().info("Removed world '" + worldKey.asString() + "' from cache.");
        });
    }

    public void onServerStop() {
        Bukkit.getLogger().info("Saving heightmap data!");
        saveAllHeightMapData();
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
        File heightMapFile = new File(dataFolder + "/OriginalHeightMaps/" + worldKey.asString() + "/r." + regionX + "." + regionZ + ".bhm");
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
                Bukkit.getLogger().severe("Failed to save heightmap: " + e.getMessage());
            }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }
}