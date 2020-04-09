package net.frankheijden.insights.listeners;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.frankheijden.insights.Insights;
import net.frankheijden.insights.api.InsightsAPI;
import net.frankheijden.insights.api.builders.Scanner;
import net.frankheijden.insights.api.entities.ChunkLocation;
import net.frankheijden.insights.api.entities.ScanOptions;
import net.frankheijden.insights.api.enums.ScanType;
import net.frankheijden.insights.api.events.PlayerChunkMoveEvent;
import net.frankheijden.insights.api.events.ScanCompleteEvent;
import net.frankheijden.insights.config.Limit;
import net.frankheijden.insights.tasks.UpdateCheckerTask;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.text.NumberFormat;
import java.util.*;

public class MainListener implements Listener {
    private Insights plugin;
    private InteractListener interactListener;
    private InsightsAPI api;

    private List<Location> blockLocations;

    public MainListener(Insights plugin, InteractListener interactListener) {
        this.plugin = plugin;
        this.interactListener = interactListener;
        this.api = new InsightsAPI();
        this.blockLocations = new ArrayList<>();
    }

    public InteractListener getInteractListener() {
        return interactListener;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String name = event.getBlock().getType().name();

        Limit limit = api.getLimit(player.getWorld(), name);
        if (limit != null) {
            sendBreakMessage(player, event.getBlock().getChunk(), limit);
        } else if (plugin.getUtils().isTile(event.getBlock())) {
            int generalLimit = plugin.getConfiguration().GENERAL_LIMIT;
            if (plugin.getConfiguration().GENERAL_ALWAYS_SHOW_NOTIFICATION || generalLimit > -1) {
                if (player.hasPermission("insights.check.realtime") && plugin.getSqLite().hasRealtimeCheckEnabled(player)) {
                    int current = event.getBlock().getLocation().getChunk().getTileEntities().length - 1;
                    double progress = ((double) current)/((double) generalLimit);
                    if (progress > 1 || progress < 0) progress = 1;

                    if (generalLimit > -1) {
                        plugin.getUtils().sendSpecialMessage(player, "messages.realtime_check", progress,
                                "%tile_count%", NumberFormat.getIntegerInstance().format(current),
                                "%limit%", NumberFormat.getIntegerInstance().format(generalLimit));
                    } else {
                        plugin.getUtils().sendSpecialMessage(player, "messages.realtime_check_no_limit", progress,
                                "%tile_count%", NumberFormat.getIntegerInstance().format(current));
                    }
                }
            }
        }
    }

    private void sendBreakMessage(Player player, Chunk chunk, Limit limit) {
        ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot();
        new BukkitRunnable() {
            @Override
            public void run() {
                int current = plugin.getUtils().getAmountInChunk(chunk, chunkSnapshot, limit) - 1;
                sendMessage(player, limit.getName(), current, limit.getLimit());
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        Entity remover = event.getRemover();
        if (!(remover instanceof Player)) return;
        handleEntityDestroy((Player) remover, event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Entity remover = event.getAttacker();
        if (!(remover instanceof Player)) return;
        handleEntityDestroy((Player) remover, event.getVehicle());
    }

    public void handleEntityDestroy(Player player, Entity entity) {
        String name = entity.getType().name();

        Limit limit = api.getLimit(player, name);
        if (limit == null) return;
        int current = getEntityCount(entity.getLocation().getChunk(), name) - 1;

        sendMessage(player, limit.getName(), current, limit.getLimit());
    }

    private void sendMessage(Player player, String name, int current, int limit) {
        if (player.hasPermission("insights.check.realtime") && plugin.getSqLite().hasRealtimeCheckEnabled(player)) {
            double progress = ((double) current)/((double) limit);
            if (progress > 1 || progress < 0) progress = 1;
            plugin.getUtils().sendSpecialMessage(player, "messages.realtime_check_custom", progress,
                    "%count%", NumberFormat.getIntegerInstance().format(current),
                    "%material%", plugin.getUtils().capitalizeName(name),
                    "%limit%", NumberFormat.getIntegerInstance().format(limit));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        Entity entity = event.getEntity();
        String name = entity.getType().name();
        handleEntityPlace(event, player, entity.getLocation().getChunk(), name);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        Player player = interactListener.getPlayerWithinRadius(entity.getLocation());
        if (player != null) {
            handleEntityPlace(event, player, entity.getLocation().getChunk(), entity.getType().name());
        }
    }

    public void handleEntityPlace(Cancellable cancellable, Player player, Chunk chunk, String name) {
        if (!canPlace(player, name) && !player.hasPermission("insights.regions.bypass." + name)) {
            plugin.getUtils().sendMessage(player, "messages.region_disallowed_block");
            cancellable.setCancelled(true);
            return;
        }

        Limit limit = api.getLimit(player, name);
        if (limit == null) return;
        int l = limit.getLimit();
        if (l < 0) return;
        int current = getEntityCount(chunk, name) + 1;

        if (current > l && !player.hasPermission(limit.getPermission())) {
            cancellable.setCancelled(true);
            plugin.getUtils().sendMessage(player, "messages.limit_reached_custom",
                    "%limit%", NumberFormat.getIntegerInstance().format(l),
                    "%material%", plugin.getUtils().capitalizeName(limit.getName()));
            return;
        }

        sendMessage(player, name, current, l);
    }

    private int getEntityCount(Chunk chunk, String entityType) {
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity.getType().name().equals(entityType)) count++;
        }
        return count;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.getHookManager().shouldCancel(event.getBlock())) return;

        Player player = event.getPlayer();
        String name = event.getBlock().getType().name();

        if (isNextToForbiddenLocation(event.getBlock().getLocation())) {
            event.setCancelled(true);
            plugin.log(Insights.LogType.WARNING, "Player " + player.getPlayerListName() + " placed block '" + name + "' too fast nearby a limited block.");
            return;
        }

        if (!canPlace(player, name) && !player.hasPermission("insights.regions.bypass." + name)) {
            plugin.getUtils().sendMessage(player, "messages.region_disallowed_block");
            event.setCancelled(true);
            return;
        }

        Limit limit = api.getLimit(player.getWorld(), name);
        if (limit != null) {
            handleBlockPlace(event, player, event.getBlock(), event.getItemInHand(), limit);
        } else if (plugin.getUtils().isTile(event.getBlockPlaced())) {
            int current = event.getBlock().getLocation().getChunk().getTileEntities().length + 1;
            int generalLimit = plugin.getConfiguration().GENERAL_LIMIT;
            if (generalLimit > -1 && current >= generalLimit) {
                if (!player.hasPermission("insights.bypass")) {
                    event.setCancelled(true);
                    plugin.getUtils().sendMessage(player, "messages.limit_reached",
                            "%limit%", NumberFormat.getIntegerInstance().format(generalLimit));
                }
            }

            if (plugin.getConfiguration().GENERAL_ALWAYS_SHOW_NOTIFICATION || generalLimit > -1) {
                if (player.hasPermission("insights.check.realtime") && plugin.getSqLite().hasRealtimeCheckEnabled(player)) {
                    double progress = ((double) current)/((double) generalLimit);
                    if (progress > 1 || progress < 0) progress = 1;

                    if (generalLimit > -1) {
                        plugin.getUtils().sendSpecialMessage(player, "messages.realtime_check", progress,
                                "%tile_count%", NumberFormat.getIntegerInstance().format(current),
                                "%limit%", NumberFormat.getIntegerInstance().format(generalLimit));
                    } else {
                        plugin.getUtils().sendSpecialMessage(player, "messages.realtime_check_no_limit", progress,
                                "%tile_count%", NumberFormat.getIntegerInstance().format(current));
                    }
                }
            }
        }
    }

    private boolean isNextToForbiddenLocation(Location location) {
        for (Location loc : blockLocations) {
            if (isEqual(loc, location, -1, 0, 0)
                    || isEqual(loc, location, 1, 0, 0)
                    || isEqual(loc, location, 0, -1, 0)
                    || isEqual(loc, location, 0, 1, 0)
                    || isEqual(loc, location, 0, 0, -1)
                    || isEqual(loc, location, 0, 0, 1)) return true;
        }
        return false;
    }

    private boolean isEqual(Location loc1, Location loc2, int x, int y, int z) {
        return loc1.clone().add(x, y, z).equals(loc2);
    }

    private boolean canPlace(Player player, String itemString) {
        if (plugin.getWorldGuardUtils() != null) {
            ProtectedRegion region = plugin.getWorldGuardUtils().isInRegionBlocks(player.getLocation());
            if (region != null) {
                Boolean whitelist = plugin.getConfiguration().GENERAL_REGION_BLOCKS_WHITELIST.get(region.getId());
                if (whitelist != null) {
                    if (whitelist) {
                        return plugin.getConfiguration().GENERAL_REGION_BLOCKS_LIST.get(region.getId()).contains(itemString);
                    } else {
                        return !plugin.getConfiguration().GENERAL_REGION_BLOCKS_LIST.get(region.getId()).contains(itemString);
                    }
                }
            }
        }
        return true;
    }

    private void handleBlockPlace(Cancellable event, Player player, Block block, ItemStack itemInHand, Limit limit) {
        ChunkSnapshot chunkSnapshot = block.getChunk().getChunkSnapshot();

        boolean async = plugin.getConfiguration().GENERAL_SCAN_ASYNC;
        if (async) {
            ItemStack itemStack = new ItemStack(itemInHand);
            itemStack.setAmount(1);

            if (!player.hasPermission(limit.getPermission())) {
                blockLocations.add(block.getLocation());
            }

            new BukkitRunnable() {
                @Override
                public void run() {
                    handleBlockPlace(event, player, block, chunkSnapshot, itemStack, async, limit);
                }
            }.runTaskAsynchronously(plugin);
        } else {
            handleBlockPlace(event, player, block, chunkSnapshot, null, async, limit);
        }
    }

    private void handleBlockPlace(Cancellable event, Player player, Block block, ChunkSnapshot chunkSnapshot, ItemStack itemStack, boolean async, Limit limit) {
        int current = plugin.getUtils().getAmountInChunk(block.getChunk(), chunkSnapshot, limit);
        int l = limit.getLimit();
        if (current > l) {
            if (!player.hasPermission(limit.getPermission())) {
                plugin.getUtils().sendMessage(player, "messages.limit_reached_custom",
                        "%limit%", NumberFormat.getIntegerInstance().format(l),
                        "%material%", plugin.getUtils().capitalizeName(limit.getName()));
                if (async) {
                    if (player.getGameMode() != GameMode.CREATIVE) {
                        player.getInventory().addItem(itemStack);
                    }
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            block.setType(Material.AIR);
                            blockLocations.remove(block.getLocation());
                        }
                    }.runTask(plugin);
                } else {
                    blockLocations.remove(block.getLocation());
                    event.setCancelled(true);
                }
                return;
            }
        }
        sendMessage(player, limit.getName(), current, l);
        blockLocations.remove(block.getLocation());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (plugin.getBossBarUtils() != null && plugin.getBossBarUtils().scanBossBarPlayers.containsKey(uuid)) {
            plugin.getBossBarUtils().scanBossBarPlayers.get(uuid).removeAll();
            plugin.getBossBarUtils().scanBossBarPlayers.get(uuid).addPlayer(player);
        }

        if (plugin.getConfiguration().GENERAL_UPDATES_CHECK) {
            if (player.hasPermission("insights.notification.update")) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, new UpdateCheckerTask(plugin, player));
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Chunk fromChunk = event.getFrom().getChunk();
        Chunk toChunk = event.getTo().getChunk();
        if (fromChunk != toChunk) {
            PlayerChunkMoveEvent chunkEnterEvent = new PlayerChunkMoveEvent(event.getPlayer(), fromChunk, toChunk);
            Bukkit.getPluginManager().callEvent(chunkEnterEvent);
            if (chunkEnterEvent.isCancelled()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler
    public void onPlayerChunkMove(PlayerChunkMoveEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        String string = plugin.getSqLite().getAutoscan(player);
        Integer type = plugin.getSqLite().getAutoscanType(player);
        if (string != null && type != null) {
            List<String> strs = Arrays.asList(string.split(","));

            Chunk chunk = event.getToChunk();

            if (type == 0) {
                ScanOptions scanOptions = new ScanOptions();
                scanOptions.setScanType(ScanType.CUSTOM);
                scanOptions.setEntityTypes(strs);
                scanOptions.setMaterials(strs);
                scanOptions.setWorld(chunk.getWorld());
                scanOptions.setChunkLocations(new LinkedList<>(Collections.singletonList(new ChunkLocation(chunk))));

                Scanner.create(scanOptions)
                        .scan()
                        .whenComplete((ev, err) -> handleAutoScan(player, ev));
            } else {
                ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot();

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Limit limit = plugin.getConfiguration().getLimits().getLimit(string);
                        int count = plugin.getUtils().getAmountInChunk(chunk, chunkSnapshot, limit);

                        double progress = ((double) count)/((double) limit.getLimit());
                        if (progress > 1 || progress < 0) progress = 1;
                        plugin.getUtils().sendSpecialMessage(player, "messages.autoscan.limit_entry", progress,
                                "%key%", plugin.getUtils().capitalizeName(limit.getName()),
                                "%count%", NumberFormat.getInstance().format(count),
                                "%limit%", NumberFormat.getInstance().format(limit.getLimit()));
                    }
                }.runTask(plugin);
            }
        }
    }

    private void handleAutoScan(Player player, ScanCompleteEvent event) {
        TreeMap<String, Integer> counts = event.getScanResult().getCounts();

        if (counts.size() == 1) {
            Map.Entry<String, Integer> entry = counts.firstEntry();
            plugin.getUtils().sendSpecialMessage(player, "messages.autoscan.single_entry", 1.0,
                    "%key%", plugin.getUtils().capitalizeName(entry.getKey()),
                    "%count%", NumberFormat.getInstance().format(entry.getValue()));
        } else {
            plugin.getUtils().sendMessage(player, "messages.autoscan.multiple_entries.header");
            for (String str : counts.keySet()) {
                plugin.getUtils().sendMessage(player, "messages.autoscan.multiple_entries.format",
                        "%entry%", plugin.getUtils().capitalizeName(str),
                        "%count%", NumberFormat.getInstance().format(counts.get(str)));
            }
            plugin.getUtils().sendMessage(player, "messages.autoscan.multiple_entries.footer");
        }
    }
}