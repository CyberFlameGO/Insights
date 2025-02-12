package dev.frankheijden.insights.commands;

import cloud.commandframework.annotations.Argument;
import cloud.commandframework.annotations.CommandMethod;
import cloud.commandframework.annotations.CommandPermission;
import cloud.commandframework.annotations.Flag;
import cloud.commandframework.annotations.specifier.Range;
import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.commands.InsightsCommand;
import dev.frankheijden.insights.api.concurrent.ScanOptions;
import dev.frankheijden.insights.api.objects.chunk.ChunkLocation;
import dev.frankheijden.insights.api.objects.chunk.ChunkPart;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.reflection.RTileEntityTypes;
import dev.frankheijden.insights.api.tasks.ScanTask;
import dev.frankheijden.insights.api.utils.Constants;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CommandScan extends InsightsCommand {

    public CommandScan(InsightsPlugin plugin) {
        super(plugin);
    }

    @CommandMethod("scan <radius> tile")
    @CommandPermission("insights.scan.tile")
    private void handleTileScan(
            Player player,
            @Argument("radius") @Range(min = "0", max = "50") int radius,
            @Flag(value = "group-by-chunk", aliases = { "c" }) boolean groupByChunk
    ) {
        handleScan(
                player,
                radius,
                RTileEntityTypes.getTileEntities(),
                ScanOptions.materialsOnly(),
                false,
                groupByChunk
        );
    }

    @CommandMethod("scan <radius> entity")
    @CommandPermission("insights.scan.entity")
    private void handleEntityScan(
            Player player,
            @Argument("radius") @Range(min = "0", max = "50") int radius,
            @Flag(value = "group-by-chunk", aliases = { "c" }) boolean groupByChunk
    ) {
        handleScan(player, radius, Constants.SCAN_ENTITIES, ScanOptions.entitiesOnly(), false, groupByChunk);
    }

    @CommandMethod("scan <radius> all")
    @CommandPermission("insights.scan.all")
    private void handleAllScan(
            Player player,
            @Argument("radius") @Range(min = "0", max = "50") int radius,
            @Flag(value = "group-by-chunk", aliases = { "c" }) boolean groupByChunk
    ) {
        handleScan(player, radius, null, ScanOptions.scanOnly(), false, groupByChunk);
    }

    @CommandMethod("scan <radius> custom <items>")
    @CommandPermission("insights.scan.custom")
    private void handleCustomScan(
            Player player,
            @Argument("radius") @Range(min = "0", max = "50") int radius,
            @Flag(value = "group-by-chunk", aliases = { "c" }) boolean groupByChunk,
            @Argument("items") ScanObject<?>[] items
    ) {
        List<ScanObject<?>> scanObjects = Arrays.asList(items);
        boolean hasOnlyEntities = scanObjects.stream()
                .allMatch(s -> s.getType() == ScanObject.Type.ENTITY);
        boolean hasOnlyMaterials = scanObjects.stream()
                .allMatch(s -> s.getType() == ScanObject.Type.MATERIAL);

        ScanOptions options;
        if (hasOnlyEntities) {
            options = ScanOptions.entitiesOnly();
        } else if (hasOnlyMaterials) {
            options = ScanOptions.materialsOnly();
        } else {
            options = ScanOptions.scanOnly();
        }

        handleScan(player, radius, new HashSet<>(scanObjects), options, true, groupByChunk);
    }

    /**
     * Scans chunks in a radius around a player.
     */
    public void handleScan(
            Player player,
            int radius,
            Set<? extends ScanObject<?>> items,
            ScanOptions options,
            boolean displayZeros,
            boolean groupByChunk
    ) {
        Chunk chunk = player.getLocation().getChunk();
        World world = chunk.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        // Generate chunk parts
        int edge = (2 * radius) + 1;
        int chunkCount = edge * edge;
        List<ChunkPart> chunkParts = new ArrayList<>(chunkCount);
        for (int x = chunkX - radius; x <= chunkX + radius; x++) {
            for (int z = chunkZ - radius; z <= chunkZ + radius; z++) {
                chunkParts.add(new ChunkLocation(world, x, z).toPart());
            }
        }

        if (groupByChunk) {
            ScanTask.scanAndDisplayGroupedByChunk(plugin, player, chunkParts, options, items, false);
        } else {
            ScanTask.scanAndDisplay(plugin, player, chunkParts, options, items, displayZeros);
        }
    }
}
