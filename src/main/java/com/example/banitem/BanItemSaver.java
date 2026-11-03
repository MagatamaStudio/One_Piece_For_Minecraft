package com.example.banitem;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class BanItemSaver extends JavaPlugin implements CommandExecutor {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new BanItemListener(this), this);
        // 命令名改为 onepiece，避免与内置 /reload 冲突
        org.bukkit.command.PluginCommand command = getCommand("onepiece");
        if (command == null) {
            getLogger().severe("[One_Piece] 未在 plugin.yml 中找到命令 onepiece，配置重载命令不可用");
        } else {
            command.setExecutor(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("onepiece")) {
            reloadConfig();
            sender.sendMessage("插件配置已重新加载！");
            return true;
        }
        return false;
    }
}