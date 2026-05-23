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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OriginalTerrainHeightMapManager implements Listener {
    static final ConcurrentHashMap<NamespacedKey, ConcurrentHashMap<Long, short[]>> LoadedChunkHeightMaps = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<NamespacedKey, Set<Long>> NewGeneratedChunks= new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Object> FileLocks = new ConcurrentHashMap<>();

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        NamespacedKey worldKey = event.getWorld().getKey();
        long chunkKey = event.getChunk().getChunkKey();
        ConcurrentHashMap<Long, short[]> worldMap = LoadedChunkHeightMaps.get(worldKey);

        if (worldMap == null) {
            return;
        }

        worldMap.computeIfAbsent(chunkKey, heightDataArray -> {
            short[] loaded = loadChunkHeightMap(chunkKey, worldKey);

            if (loaded.length != 0) {
                return loaded;
            }

            short[] generated = generateChunkHeightMap(event.getChunk());
            NewGeneratedChunks.computeIfAbsent(worldKey, chunkKeySet -> ConcurrentHashMap.newKeySet()).add(chunkKey);
            return generated;
        });
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        return;/*
        NamespacedKey worldKey = event.getWorld().getKey();
        long chunkKey = event.getChunk().getChunkKey();
        if (!LoadedChunkHeightMaps.containsKey(worldKey) || !LoadedChunkHeightMaps.get(worldKey).containsKey(chunkKey)) {
            return;
        }

        Bukkit.getAsyncScheduler().runNow(JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class), (Task) -> {
            saveChunkHeightMapData(worldKey, chunkKey);
        });*/
    }

    private static short[] loadChunkHeightMap(long chunkKey, NamespacedKey worldKey) {

        int chunkX = (int) chunkKey;
        int chunkZ = (int) (chunkKey >> 32);

        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;

        String dataFolder =
                JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class)
                        .getDataFolder()
                        .getPath();

        File heightMapFile =
                new File(dataFolder + "/OriginalHeightMaps/"
                        + worldKey.asString()
                        + "/r." + regionX + "." + regionZ + ".bhm");

        if (!heightMapFile.exists()) {
            return new short[0];
        }

        String regionKey = worldKey.asString() + ":" + regionX + ":" + regionZ;

        synchronized (FileLocks.computeIfAbsent(regionKey, k -> new Object())) {
            try (FileChannel fileReader = FileChannel.open(heightMapFile.toPath(), StandardOpenOption.READ)) {
                ByteBuffer shortBuffer = ByteBuffer.allocate(2);
                fileReader.read(shortBuffer, 0);
                shortBuffer.flip();

                short chunksInFile = shortBuffer.getShort();

                ByteBuffer keyBuffer = ByteBuffer.allocate(8);
                ByteBuffer heightMapBuffer = ByteBuffer.allocate(512);

                boolean keyFound = false;
                for (int i = 0; i < chunksInFile; i++) {
                    keyBuffer.clear();
                    fileReader.read(keyBuffer, 2L + (i * 8L));
                    keyBuffer.flip();

                    if (keyBuffer.getLong() == chunkKey) {
                        heightMapBuffer.clear();
                        fileReader.read(heightMapBuffer, 2L + 8192L + (i * 512L));
                        heightMapBuffer.flip();

                        keyFound = true;
                        break;
                    }
                }

                if (!keyFound) {
                    return new short[0];
                }

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

    public static int getY(int blockX, int blockZ, World world)
    {
        if (LoadedChunkHeightMaps.containsKey(world.getKey())) {
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long chunkKey = (((long) chunkZ) << 32) | (chunkX & 0xffffffffL);
            if (LoadedChunkHeightMaps.get(world.getKey()).containsKey(chunkKey)) {
                short[] heightMap = LoadedChunkHeightMaps.get(world.getKey()).get(chunkKey);
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

            Bukkit.getLogger().info(
                    "Removed world '" + worldKey.asString() + "' from cache.");
        });
    }

    public void onServerStop()
    {
        Bukkit.getLogger().info("Saving heightmap data!");
        saveAllHeightMapData();
    }

    private static void saveAllHeightMapData() {
        for (NamespacedKey worldKey : LoadedChunkHeightMaps.keySet()) {
            for (Long chunkKey : LoadedChunkHeightMaps.get(worldKey).keySet()) {
                saveChunkHeightMapData(worldKey, chunkKey);
            }
        }
    }

    private static void saveChunkHeightMapData(NamespacedKey worldKey, long chunkKey) {
        Set<Long> newChunks = NewGeneratedChunks.get(worldKey);
        if (newChunks == null || !newChunks.remove(chunkKey)) {
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

        synchronized (FileLocks.computeIfAbsent(regionKey, k -> new Object())) {
            try {
                if (!heightMapFile.exists()) {
                    heightMapFile.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(heightMapFile)) {
                        out.write(new byte[2 + 8192 + 524288]);
                    }
                }

                try (FileChannel channel = FileChannel.open(heightMapFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    ByteBuffer shortBuffer = ByteBuffer.allocate(2);
                    channel.read(shortBuffer, 0);
                    shortBuffer.flip();

                    short chunksInFile = shortBuffer.getShort();
                    if (chunksInFile >= 1024) {
                        Bukkit.getLogger().severe("Region file full!");
                        return;
                    }

                    ByteBuffer dataBuffer = ByteBuffer.allocate(512);
                    for (short s : heightmapData) {
                        dataBuffer.putShort(s);
                    }
                    dataBuffer.flip();

                    long keyPosition = 2L + (chunksInFile * 8L);
                    long dataPosition = 2L + 8192L + (chunksInFile * 512L);

                    writeFully(channel, dataBuffer, dataPosition);

                    ByteBuffer keyBuffer = ByteBuffer.allocate(8);
                    keyBuffer.putLong(chunkKey);
                    keyBuffer.flip();
                    writeFully(channel, keyBuffer, keyPosition);

                    ByteBuffer countBuffer = ByteBuffer.allocate(2);
                    countBuffer.putShort((short) (chunksInFile + 1));
                    countBuffer.flip();
                    writeFully(channel, countBuffer, 0);
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