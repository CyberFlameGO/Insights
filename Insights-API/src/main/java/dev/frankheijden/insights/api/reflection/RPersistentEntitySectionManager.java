package dev.frankheijden.insights.api.reflection;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import dev.frankheijden.insights.api.utils.ReflectionUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

public class RPersistentEntitySectionManager {

    private static MethodHandle sectionStorageMethodHandle;
    private static MethodHandle permanentStorageMethodHandle;

    static {
        try {
            sectionStorageMethodHandle = MethodHandles.lookup().unreflectGetter(ReflectionUtils.findDeclaredField(
                    PersistentEntitySectionManager.class,
                    EntitySectionStorage.class,
                    "sectionStorage"
            ));
            permanentStorageMethodHandle = MethodHandles.lookup().unreflectGetter(ReflectionUtils.findDeclaredField(
                    PersistentEntitySectionManager.class,
                    EntityPersistentStorage.class,
                    "permanentStorage"
            ));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private RPersistentEntitySectionManager() {}

    @SuppressWarnings("unchecked")
    public static EntitySectionStorage<Entity> getSectionStorage(
            PersistentEntitySectionManager<Entity> entityManager
    ) throws Throwable {
        return (EntitySectionStorage<Entity>) sectionStorageMethodHandle.invoke(entityManager);
    }

    @SuppressWarnings("unchecked")
    public static EntityPersistentStorage<Entity> getPermanentStorage(
            PersistentEntitySectionManager<Entity> entityManager
    ) throws Throwable {
        return (EntityPersistentStorage<Entity>) permanentStorageMethodHandle.invoke(entityManager);
    }
}
