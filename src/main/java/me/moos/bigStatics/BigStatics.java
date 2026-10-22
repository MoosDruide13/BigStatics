package me.moos.bigStatics;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class BigStatics extends JavaPlugin {

    private final OriginalTerrainHeightMapManager hm = new OriginalTerrainHeightMapManager(this);
    private final StaticsManager sm = new StaticsManager(this);


    @Override
    public void onEnable() {
        // Plugin startup logic
        hm.onServerStart();
        Bukkit.getServer().getPluginManager().registerEvents(hm, this);
        Bukkit.getServer().getPluginManager().registerEvents(sm, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        hm.onServerStop();
        this.getLogger().info("Done saving!");
    }
}