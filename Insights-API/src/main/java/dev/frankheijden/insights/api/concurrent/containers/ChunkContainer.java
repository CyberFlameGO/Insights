package dev.frankheijden.insights.api.concurrent.containers;

import dev.frankheijden.insights.api.concurrent.ScanOptions;
import dev.frankheijden.insights.api.concurrent.storage.DistributionStorage;
import dev.frankheijden.insights.api.objects.chunk.ChunkCuboid;
import dev.frankheijden.insights.api.objects.chunk.ChunkVector;
import dev.frankheijden.insights.api.reflection.RPersistentEntitySectionManager;
import dev.frankheijden.insights.api.utils.ChunkUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_17_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_17_R1.util.CraftMagicNumbers;
import org.bukkit.entity.EntityType;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;

public abstract class ChunkContainer implements SupplierContainer<DistributionStorage> {

    protected final World world;
    protected final int chunkX;
    protected final int chunkZ;
    protected final ChunkCuboid cuboid;
    protected final ScanOptions options;
    protected final Map<Material, Integer> materialMap;
    protected final Map<EntityType, Integer> entityMap;

    /**
     * Constructs a new ChunkSnapshotContainer, with the area to be scanned as a cuboid.
     */
    protected ChunkContainer(World world, int chunkX, int chunkZ, ChunkCuboid cuboid, ScanOptions options) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.cuboid = cuboid;
        this.options = options;
        this.materialMap = new EnumMap<>(Material.class);
        this.entityMap = new EnumMap<>(EntityType.class);
    }

    public World getWorld() {
        return world;
    }

    public long getChunkKey() {
        return ChunkUtils.getKey(chunkX, chunkZ);
    }

    public int getX() {
        return chunkX;
    }

    public int getZ() {
        return chunkZ;
    }

    public ChunkCuboid getChunkCuboid() {
        return cuboid;
    }

    public abstract LevelChunkSection[] getChunkSections() throws Throwable;

    @Override
    public DistributionStorage get() {
        ChunkVector min = cuboid.getMin();
        ChunkVector max = cuboid.getMax();
        int minX = min.getX();
        int maxX = max.getX();
        int minZ = min.getZ();
        int maxZ = max.getZ();
        int blockMinY = min.getY();
        int blockMaxY = max.getY();

        try {
            ServerLevel serverLevel = ((CraftWorld) world).getHandle();

            if (options.materials()) {
                LevelChunkSection[] chunkSections = getChunkSections();

                int minSectionY = blockMinY >> 4;
                int maxSectionY = blockMaxY >> 4;

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int minY = sectionY == minSectionY ? blockMinY & 15 : 0;
                    int maxY = sectionY == maxSectionY ? blockMaxY & 15 : 15;

                    LevelChunkSection section = chunkSections[sectionY];
                    if (section == null) {
                        // Section is empty, count everything as air
                        int count = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                        materialMap.merge(Material.AIR, count, Integer::sum);
                    } else if (minX == 0 && maxX == 15 && minY == 0 && maxY == 15 && minZ == 0 && maxZ == 15) {
                        // Section can be counted as a whole
                        section.getStates().count((state, count) -> {
                            try {
                                materialMap.merge(
                                        CraftMagicNumbers.getMaterial(state.getBlock()),
                                        count,
                                        Integer::sum
                                );
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                        });
                    } else {
                        // Section must be scanned block by block
                        for (int x = minX; x <= maxX; x++) {
                            for (int y = minY; y <= maxY; y++) {
                                for (int z = minZ; z <= maxZ; z++) {
                                    materialMap.merge(
                                            CraftMagicNumbers.getMaterial(section.getBlockState(x, y, z).getBlock()),
                                            1,
                                            Integer::sum
                                    );
                                }
                            }
                        }
                    }
                }
            }

            if (options.entities()) {
                PersistentEntitySectionManager<Entity> entityManager = serverLevel.entityManager;

                long chunkKey = getChunkKey();
                final Stream<Entity> entityStream;
                if (entityManager.areEntitiesLoaded(chunkKey)) {
                    entityStream = RPersistentEntitySectionManager.getSectionStorage(entityManager)
                            .getExistingSectionsInChunk(chunkKey)
                            .flatMap(EntitySection::getEntities);
                } else {
                    entityStream = RPersistentEntitySectionManager.getPermanentStorage(entityManager)
                            .loadEntities(new ChunkPos(chunkX, chunkZ))
                            .join()
                            .getEntities();
                }

                entityStream.filter(entity -> {
                    int x = entity.getBlockX() & 15;
                    int y = entity.getBlockY();
                    int z = entity.getBlockZ() & 15;
                    return minX <= x && x <= maxX && blockMinY <= y && y <= blockMaxY && minZ <= z && z <= maxZ;
                }).forEach(entity -> entityMap.merge(entity.getBukkitEntity().getType(), 1, Integer::sum));
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }

        return DistributionStorage.of(materialMap, entityMap);
    }
}
