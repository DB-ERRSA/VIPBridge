package com.errsa.vipbridge.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class YamlFile {

    private final JavaPlugin plugin;
    private final File file;
    private FileConfiguration config;

    public YamlFile(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), fileName);
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save " + file.getName() + ": " + ex.getMessage());
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }
}