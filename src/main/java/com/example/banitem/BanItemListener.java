package com.example.banitem;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class BanItemListener implements Listener {
    private final JavaPlugin plugin;
    private final Random random = new Random();

    // 每个木桶的格数
    private static final int BARREL_SIZE = 27;
    // 一次最多放置的木桶数量
    private static final int MAX_BARRELS = 4;

    public BanItemListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerBan(PlayerKickEvent event) {
        String reason = event.getReason();
        if (reason == null || !reason.toLowerCase(Locale.ROOT).contains("banned")) return;

        Player player = event.getPlayer();
        World world = player.getWorld();

        // 先收集所有需要搬迁的物品，等全部入库或掉落之后才允许清空玩家
        List<ItemStack> itemsToStore = new ArrayList<>();
        PlayerInventory playerInv = player.getInventory();
        // getContents() 已包含背包、盔甲与副手槽，不需要再单独处理副手（单独处理会导致副手物品存两份）
        for (ItemStack item : playerInv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                itemsToStore.add(item);
            }
        }
        Inventory enderChest = player.getEnderChest();
        for (ItemStack item : enderChest.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                itemsToStore.add(item);
            }
        }

        // 按 27 格/桶估算所需桶数，最多 4 个
        int barrelCount = Math.min(MAX_BARRELS, Math.max(1, (itemsToStore.size() + BARREL_SIZE - 1) / BARREL_SIZE));

        Location around = player.getLocation();
        Location baseLoc = null;
        List<Block> placedBlocks = new ArrayList<>();
        List<Inventory> barrelInventories = new ArrayList<>();
        List<Location> barrelLocations = new ArrayList<>();

        // 预先放置足够数量的木桶，第 index 个桶在基准点基础上沿 x 轴偏移
        for (int index = 0; index < barrelCount; index++) {
            Location spot = findFreeSpot(world, around, index);
            if (spot == null) {
                Bukkit.getLogger().severe("[BanItem] 找不到可安全放置木桶的位置，本次放弃，玩家物品未做任何改动");
                rollbackBarrels(placedBlocks);
                return;
            }
            Block block = spot.getBlock();
            block.setType(Material.BARREL);
            placedBlocks.add(block);
            BlockState state = block.getState();
            if (!(state instanceof Barrel)) {
                Bukkit.getLogger().severe("[BanItem] 木桶块状态获取失败（可能被保护插件拦截），回滚已放置的木桶");
                rollbackBarrels(placedBlocks);
                return;
            }
            Barrel barrel = (Barrel) state;
            barrelInventories.add(barrel.getInventory());
            barrelLocations.add(block.getLocation());
            if (baseLoc == null) {
                baseLoc = block.getLocation();
            }
        }

        // 依次把物品放入木桶；放不下的绝不删除，改为掉落在木桶旁
        Location dropLoc = barrelLocations.get(0).clone().add(0.5, 1.0, 0.5);
        int storedCount = 0;
        int droppedCount = 0;
        for (ItemStack item : itemsToStore) {
            ItemStack remainingItem = item;
            for (Inventory inv : barrelInventories) {
                Map<Integer, ItemStack> remaining = inv.addItem(remainingItem);
                if (remaining.isEmpty()) {
                    remainingItem = null;
                    break;
                }
                remainingItem = remaining.values().iterator().next();
            }
            if (remainingItem == null) {
                storedCount++;
            } else {
                // 硬性要求：绝不删除玩家物品，溢出部分掉落在木桶旁
                world.dropItemNaturally(dropLoc, remainingItem);
                droppedCount++;
                Bukkit.getLogger().severe("[BanItem] 木桶容量不足，物品已掉落在木桶旁（未删除）: "
                        + remainingItem.getType() + " 数量: " + remainingItem.getAmount());
            }
        }
        Bukkit.getLogger().info("[BanItem] 已入库物品: " + storedCount + " 个，掉落在木桶旁的物品: " + droppedCount + " 个");

        // 保存木桶状态并关闭对应界面
        for (Block block : placedBlocks) {
            BlockState state = block.getState();
            if (state instanceof Barrel) {
                ((Barrel) state).update();
            }
        }

        // 只有全部物品都已入库或已掉落，才清空玩家背包与末影箱
        player.getInventory().clear();
        enderChest.clear();

        // 获取坐标加密开关配置
        boolean encryptCoords = plugin.getConfig().getBoolean("encrypt-coordinates", true);
        // 根据配置决定是否加密坐标
        String coords = encryptCoords ? encryptCoordinates(baseLoc) : getRawCoordinates(baseLoc);
        // 添加调试日志确认加密状态
        Bukkit.getLogger().info("[BanItem] 加密开关状态：" + encryptCoords + "，原始坐标：" + getRawCoordinates(baseLoc));
        String message = plugin.getConfig().getString("broadcast-message", "被封禁玩家 {player} 的物品已保存，位置：{coords}");
        message = message.replace("{player}", player.getName()).replace("{coords}", coords);
        Bukkit.broadcastMessage(message);
    }

    /**
     * 寻找一个可以安全放置木桶的位置。
     * 以 around 为中心 ±50 随机取点，第 index 个桶在基准点基础上沿 x 轴偏移，
     * 要求区块已加载，且从地表上方开始向下最多 5 格内存在空气/空方块。
     *
     * @return 可用位置；找不到时返回 null，调用方必须放弃本次操作（不写方块、不清背包）
     */
    private Location findFreeSpot(World world, Location around, int index) {
        final int radius = 50;
        final int maxAttempts = 200;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int x = around.getBlockX() + random.nextInt(radius * 2 + 1) - radius + index;
            int z = around.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;

            // 区块未加载时跳过，避免触发区块生成/加载
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            int startY = world.getHighestBlockYAt(x, z) + 1;
            // 从地表上方开始向下最多尝试 5 格，避免盖住水面、树叶或别人的建筑
            for (int offset = 0; offset <= 5; offset++) {
                int y = startY - offset;
                Block block = world.getBlockAt(x, y, z);
                if (block.getType().isAir() || block.isEmpty()) {
                    return block.getLocation();
                }
            }
        }
        return null;
    }

    /**
     * 回滚已经放置的木桶（还原为空气），用于放置失败时不留残留方块
     */
    private void rollbackBarrels(List<Block> placedBlocks) {
        for (Block block : placedBlocks) {
            try {
                block.setType(Material.AIR);
            } catch (Exception e) {
                Bukkit.getLogger().severe("[BanItem] 回滚木桶方块失败: " + block.getLocation() + " 原因: " + e.getMessage());
            }
        }
        placedBlocks.clear();
    }

    private String encryptCoordinates(Location loc) {
        int offset = 3;
        String x = String.valueOf(loc.getBlockX());
        String y = String.valueOf(loc.getBlockY());
        String z = String.valueOf(loc.getBlockZ());

        StringBuilder encrypted = new StringBuilder();
        for (char c : (x + "," + y + "," + z).toCharArray()) {
            encrypted.append((char) (c + offset));
        }
        return encrypted.toString();
    }

    private String getRawCoordinates(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}
