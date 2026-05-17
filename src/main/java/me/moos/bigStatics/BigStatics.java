package me.moos.bigStatics;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class BigStatics extends JavaPlugin {

    OriginalTerrainHeightMapManager hm = new OriginalTerrainHeightMapManager();
    StaticsManager sm = new StaticsManager();

    @Override
    public void onEnable() {
        // Plugin startup logic
        Bukkit.getServer().getPluginManager().registerEvents(hm, this);
        Bukkit.getServer().getPluginManager().registerEvents(sm, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        hm.onServerStop();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Bukkit.getLogger().info("Done saving!");
    }
}