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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class OriginalTerrainHeightMapManager implements Listener {
    static ConcurrentHashMap<NamespacedKey, ConcurrentHashMap<Long, short[]>> LoadedChunkHeightMaps = new ConcurrentHashMap<>();
    static ConcurrentHashMap<NamespacedKey, List<Long>> NewGeneratedChunks= new ConcurrentHashMap<>();

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event)
    {
        long chunkKey = event.getChunk().getChunkKey();
        NamespacedKey worldKey = event.getWorld().getKey();
        if (LoadedChunkHeightMaps.containsKey(worldKey)) {
            if (!LoadedChunkHeightMaps.get(worldKey).containsKey(chunkKey)) {
                // We only need to load or generate the data when it isn't loaded already
                short[] loadedData = loadChunkHeightMap(chunkKey, worldKey);

                if (loadedData.length == 0) {
                    // Data could not be loaded from disk -> generate it
                    LoadedChunkHeightMaps.get(worldKey).put(chunkKey, generateChunkHeightMap(event.getChunk()));
                    NewGeneratedChunks.get(worldKey).add(chunkKey);
                }
                else {
                    // Data was loaded from disk
                    LoadedChunkHeightMaps.get(worldKey).put(chunkKey, loadedData);
                }
            }
        }
        /*else {
            Bukkit.getLogger().info("Ignoring chunk load as world " + worldKey.asString() + " is not in the cache");
        }*/
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
        int chunkX = (int)(chunkKey);
        int chunkZ = (int)(chunkKey >> 32);
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        String dataFolder = JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class).getDataFolder().getPath();
        File heightMapFile = new File(dataFolder + "/OriginalHeightMaps/" + worldKey.asString() + "/r." + regionX + "." + regionZ + ".bhm");

        if (!heightMapFile.exists()) {
            return new short[0];
        }

        try {
            FileChannel fileReader = FileChannel.open(heightMapFile.toPath(), StandardOpenOption.READ);
            byte[] rawChunksInFile = new byte[2];
            fileReader.read(ByteBuffer.wrap(rawChunksInFile));
            short chunksInFile = (short)((rawChunksInFile[1] & 0xFF) | ((rawChunksInFile[0] & 0xFF) << 8));
            byte[] rawChunkKey = new byte[8];
            ByteBuffer keyBuffer = ByteBuffer.wrap(rawChunkKey);
            ByteBuffer heightMapBuffer = ByteBuffer.allocate(512);
            boolean keyFound = false;
            for (int i = 0; i < chunksInFile; i++) {
                fileReader.read(keyBuffer, 2 + (i * 8));
                keyBuffer.flip();
                if (keyBuffer.getLong() == chunkKey) {
                    keyFound = true;
                    fileReader.read(heightMapBuffer, 2 + 8192 + (i * 512));
                    heightMapBuffer.flip();

                    break;
                }
                keyBuffer.flip();
            }
            fileReader.close();
            if (!keyFound) {
                // The requested chunk does not exist on disk
                return new short[0];
            }
            short[] output = new short[16 * 16];
            for (int i = 0; i < 256; i += 1) {
                try {
                    output[i] = heightMapBuffer.getShort();
                } catch (BufferUnderflowException e) {
                    Bukkit.getLogger().severe("Underflow when attempting to load the " + (i + 1) + "th short!");
                    break;
                }
            }
            return output;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static int getY(int blockX, int blockZ, World world)
    {
        if (LoadedChunkHeightMaps.containsKey(world.getKey())) {
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long chunkKey = ((long)chunkZ << 32) | chunkX;
            if (LoadedChunkHeightMaps.get(world.getKey()).containsKey(chunkKey)) {
                //Bukkit.getLogger().info("ChunkKey found!");
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
        if (!LoadedChunkHeightMaps.containsKey(event.getWorld().getKey())) {
            LoadedChunkHeightMaps.put(event.getWorld().getKey(), new ConcurrentHashMap<>());
            NewGeneratedChunks.put(event.getWorld().getKey(), Collections.synchronizedList(new ArrayList<Long>()));
            Bukkit.getLogger().info("Added world '" + event.getWorld().getKey().asString() + "' to the heightmap cache.");
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        Bukkit.getLogger().info("Saving heightmap data!");

        Bukkit.getAsyncScheduler().runNow(JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class), (Task) -> {
            saveAllHeightMapData();
        });

        if (LoadedChunkHeightMaps.containsKey(event.getWorld().getKey())) {
            LoadedChunkHeightMaps.remove(event.getWorld().getKey());
            NewGeneratedChunks.remove(event.getWorld().getKey());
            Bukkit.getLogger().info("Removed world '" + event.getWorld().getKey().asString() + "' from the heightmap cache.");
        }
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
        if (!NewGeneratedChunks.containsKey(worldKey)) {
            throw new IllegalArgumentException("Can not save heightmap data of uncached world '" + worldKey.asString() + "'!");
        }
        if (!NewGeneratedChunks.get(worldKey).contains(chunkKey)) {
            // There is no point in saving a heightmap to disk that was loaded from disk in the first place.
            return;
        }
        NewGeneratedChunks.get(worldKey).remove(chunkKey);

        int chunkX = (int)(chunkKey);
        int chunkZ = (int)(chunkKey >> 32);
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        String dataFolder = JavaPlugin.getProvidingPlugin(OriginalTerrainHeightMapManager.class).getDataFolder().getPath();
        File heightMapFile = new File(dataFolder + "/OriginalHeightMaps/" + worldKey.asString() + "/r." + regionX + "." + regionZ + ".bhm");
        if (heightMapFile.exists() && heightMapFile.length() != 2 + 8192 + 524288) {
            Bukkit.getLogger().warning("File " + heightMapFile.getPath() + " is unexpected size! Expected: " + (2 + 8192 + 524288) + ", Actual: " + heightMapFile.length());
        }

        if (!heightMapFile.exists()) {
            try {
                heightMapFile.getParentFile().mkdirs();
                heightMapFile.createNewFile();

                FileOutputStream fileWriter = new FileOutputStream(heightMapFile);
                fileWriter.write(new byte[2 + 8192 + 524288]);
                fileWriter.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        heightMapFile = new File(dataFolder + "/OriginalHeightMaps/" + worldKey.asString() + "/r." + regionX + "." + regionZ + ".bhm");

        try {
            FileInputStream fileReader = new FileInputStream(heightMapFile);
            byte[] rawChunksInFile = new byte[2];
            fileReader.read(rawChunksInFile);
            fileReader.close();

            short[] heightmapData = LoadedChunkHeightMaps.get(worldKey).get(chunkKey);
            ByteBuffer buffer = ByteBuffer.allocate(heightmapData.length * 2);

            for (short s : heightmapData) {
                buffer.putShort(s);
            }

            buffer.flip();

            short chunksInFile = (short)((rawChunksInFile[1] & 0xFF) | ((rawChunksInFile[0] & 0xFF) << 8));
            if (chunksInFile > 32 * 32) {
                Bukkit.getLogger().severe("Chunk count in file is corrupt! Actual value: " + chunksInFile + ", Max allowed value: " + 32 * 32);
            }

            FileChannel fileWriter = FileChannel.open(heightMapFile.toPath(), StandardOpenOption.WRITE);
            writeFully(fileWriter, buffer, 2 + 8192 + (chunksInFile * 512));
            buffer = ByteBuffer.allocate(8);
            buffer.putLong(chunkKey);
            buffer.flip();
            writeFully(fileWriter, buffer, 2 + (chunksInFile * 8));
            chunksInFile++;
            buffer = ByteBuffer.allocate(2);
            buffer.putShort(chunksInFile);
            buffer.flip();
            writeFully(fileWriter, buffer, 0);
            fileWriter.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
    }
}