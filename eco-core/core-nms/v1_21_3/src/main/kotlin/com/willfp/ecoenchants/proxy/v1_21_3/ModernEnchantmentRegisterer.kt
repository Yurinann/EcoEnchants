package com.willfp.ecoenchants.proxy.v1_21_3

import com.willfp.ecoenchants.enchant.EcoEnchant
import com.willfp.ecoenchants.enchant.EcoEnchants
import com.willfp.ecoenchants.enchant.impl.EcoEnchantBase
import com.willfp.ecoenchants.enchant.registration.modern.ModernEnchantmentRegistererProxy
import com.willfp.ecoenchants.proxy.v1_21_3.registration.EcoEnchantsCraftEnchantment
import com.willfp.ecoenchants.proxy.v1_21_3.registration.ModifiedVanillaCraftEnchantment
import com.willfp.ecoenchants.proxy.v1_21_3.registration.vanillaEcoEnchantsEnchantment
import net.minecraft.core.Holder
import net.minecraft.core.MappedRegistry
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.CraftRegistry
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.util.CraftNamespacedKey
import org.bukkit.enchantments.Enchantment
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.function.BiFunction

private val enchantmentRegistry =
    (Bukkit.getServer() as CraftServer).server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)

@Suppress("DEPRECATION")
private val bukkitRegistry =
    org.bukkit.Registry.ENCHANTMENT

class ModernEnchantmentRegisterer : ModernEnchantmentRegistererProxy {
    private val frozenField = MappedRegistry::class.java
        .declaredFields
        .filter { it.type.isPrimitive }[0]
        .apply { isAccessible = true }

    private val allTags = MappedRegistry::class.java
        .declaredFields
        .filter { it.type.name.contains("TagSet") }[0]
        .apply { isAccessible = true }

    private val unregisteredIntrusiveHoldersField = MappedRegistry::class.java
        .declaredFields
        .last { it.type == Map::class.java }
        .apply { isAccessible = true }

    private val minecraftToBukkit = bukkitRegistry::class.java
        .getDeclaredField("minecraftToBukkit")
        .apply { isAccessible = true }

    // Paper has two minecraftToBukkit fields, one in CraftRegistry and one in WritableCraftRegistry
    private val minecraftToBukkitAlt = CraftRegistry::class.java
        .getDeclaredField("minecraftToBukkit")
        .apply { isAccessible = true }

    private val cache = CraftRegistry::class.java
        .getDeclaredField("cache")
        .apply { isAccessible = true }

    override fun replaceRegistry() {
        val newRegistryMTB =
            BiFunction<NamespacedKey, net.minecraft.world.item.enchantment.Enchantment, Enchantment?> { key, registry ->
                val eco = EcoEnchants.getByID(key.key)
                val registered = enchantmentRegistry.containsKey(CraftNamespacedKey.toMinecraft(key))

                if (eco != null) {
                    eco as Enchantment
                } else if (registered) {
                    ModifiedVanillaCraftEnchantment(key, registry)
                } else {
                    null
                }
            }

        // Update bukkit registry
        minecraftToBukkit.set(bukkitRegistry, newRegistryMTB)
        minecraftToBukkitAlt.set(bukkitRegistry, newRegistryMTB)

        // Clear the enchantment cache
        cache.set(bukkitRegistry, mutableMapOf<NamespacedKey, Enchantment>())

        // Unfreeze NMS registry
        frozenField.set(enchantmentRegistry, false)
        unregisteredIntrusiveHoldersField.set(
            enchantmentRegistry,
            IdentityHashMap<net.minecraft.world.item.enchantment.Enchantment,
                    Holder.Reference<net.minecraft.world.item.enchantment.Enchantment>>()
        )

        /*
        Creating an unbound tag set requires using reflection because the inner class is
        package-private, so we just find the method manually.
         */

        val unboundTagSet = MappedRegistry::class.java
            .declaredClasses[0]
            .declaredMethods
            .filter { Modifier.isStatic(it.modifiers) }
            .filter { it.parameterCount == 0 }[0]
            .apply { isAccessible = true }
            .invoke(null)

        allTags.set(enchantmentRegistry, unboundTagSet)
    }

    override fun register(enchant: EcoEnchantBase): Enchantment {
        // Clear the enchantment cache
        cache.set(bukkitRegistry, mutableMapOf<NamespacedKey, Enchantment>())

        if (enchantmentRegistry.containsKey(CraftNamespacedKey.toMinecraft(enchant.enchantmentKey))) {
            val nms = enchantmentRegistry[CraftNamespacedKey.toMinecraft(enchant.enchantmentKey)]

            if (nms.isPresent) {
                return EcoEnchantsCraftEnchantment(enchant, nms.get().value())
            } else {
                throw IllegalStateException("Enchantment ${enchant.id} wasn't registered")
            }
        }

        val vanillaEnchantment = vanillaEcoEnchantsEnchantment(enchant)

        enchantmentRegistry.createIntrusiveHolder(vanillaEnchantment)

        Registry.register(
            enchantmentRegistry,
            ResourceLocation.withDefaultNamespace(enchant.id),
            vanillaEnchantment
        )

        return register(enchant)
    }

    override fun unregister(enchant: EcoEnchant) {
        /*

        You can't unregister from a minecraft registry, so we simply leave the stale reference there.
        This shouldn't cause many issues in production as the bukkit registry is replaced on each reload.

         */
    }
}
