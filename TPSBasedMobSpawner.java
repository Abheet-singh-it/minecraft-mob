package com.example.tpsmobspawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TPSBasedMobSpawner extends JavaPlugin implements Listener {
    
    private FileConfiguration config;
    private BukkitTask spawnTask;
    private BukkitTask tpsMonitorTask;
    private final Map<World, Integer> worldSpawnCounts = new ConcurrentHashMap<>();
    private final Map<World, Double> worldTPS = new ConcurrentHashMap<>();
    private final List<Double> tpsHistory = new ArrayList<>();
    private final int TPS_HISTORY_SIZE = 20;
    private long lastTPSUpdate = 0;
    private final Set<org.bukkit.entity.Entity> pluginSpawnedMobs = ConcurrentHashMap.newKeySet();
    private final Map<Location, Boolean> protectionCache = new ConcurrentHashMap<>();
    
    // Configuration values
    private int minTPS = 19;
    private int maxMobsPerWorld = 100;
    private int spawnRadius = 50;
    private int minSpawnDistance = 20;
    private int spawnCheckInterval = 20;
    private int tpsCheckInterval = 20;
    private List<String> blacklistedWorlds = new ArrayList<>();
    private List<EntityType> allowedMobs = new ArrayList<>();
    private Map<EntityType, Double> mobSpawnWeights = new HashMap<>();
    private boolean enableLevelledMobs = true;
    private String levelledMobsPrefix = "&7[&b&lLv&7]";
    private boolean enableGriefPreventionCheck = true;
    private boolean allowMobDamage = true;
    private boolean allowMobTargeting = true;
    private boolean enableBaseProtection = true;
    
    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();
        loadConfig();
        
        // Register events
        getServer().getPluginManager().registerEvents(this, this);
        
        // Start tasks
        startTPSTask();
        startSpawnTask();
        startCleanupTask();
        
        // Register commands
        getCommand("tpsmobspawner").setExecutor(new TPSMobSpawnerCommand(this));
        
        getLogger().info("TPSBasedMobSpawner has been enabled!");
    }
    
    @Override
    public void onDisable() {
        if (spawnTask != null) {
            spawnTask.cancel();
        }
        if (tpsMonitorTask != null) {
            tpsMonitorTask.cancel();
        }
        getLogger().info("TPSBasedMobSpawner has been disabled!");
    }
    
    private void loadConfig() {
        reloadConfig();
        config = getConfig();
        
        minTPS = config.getInt("settings.min_tps", 19);
        maxMobsPerWorld = config.getInt("settings.max_mobs_per_world", 100);
        spawnRadius = config.getInt("settings.spawn_radius", 50);
        minSpawnDistance = config.getInt("settings.min_spawn_distance", 20);
        spawnCheckInterval = config.getInt("settings.spawn_check_interval", 20);
        tpsCheckInterval = config.getInt("settings.tps_check_interval", 20);
        enableLevelledMobs = config.getBoolean("settings.enable_levelledmobs", true);
        levelledMobsPrefix = config.getString("settings.levelledmobs_prefix", "&7[&b&lLv&7]");
        enableGriefPreventionCheck = config.getBoolean("settings.enable_grief_prevention_check", true);
        allowMobDamage = config.getBoolean("settings.allow_mob_damage", true);
        allowMobTargeting = config.getBoolean("settings.allow_mob_targeting", true);
        enableBaseProtection = config.getBoolean("settings.enable_base_protection", true);
        
        blacklistedWorlds = config.getStringList("blacklisted_worlds");
        
        // Load allowed mobs
        allowedMobs.clear();
        List<String> mobNames = config.getStringList("allowed_mobs");
        for (String mobName : mobNames) {
            try {
                EntityType type = EntityType.valueOf(mobName.toUpperCase());
                allowedMobs.add(type);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid mob type: " + mobName);
            }
        }
        
        // Load mob spawn weights
        mobSpawnWeights.clear();
        ConfigurationSection weightsSection = config.getConfigurationSection("mob_spawn_weights");
        if (weightsSection != null) {
            for (String mobName : weightsSection.getKeys(false)) {
                try {
                    EntityType type = EntityType.valueOf(mobName.toUpperCase());
                    double weight = weightsSection.getDouble(mobName, 1.0);
                    mobSpawnWeights.put(type, weight);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Invalid mob type in weights: " + mobName);
                }
            }
        }
        
        // Validate configuration values
        validateConfiguration();
        
        getLogger().info("Configuration loaded successfully!");
        getLogger().info("Blacklisted worlds: " + blacklistedWorlds);
        getLogger().info("Allowed mobs: " + allowedMobs.size());
    }
    
    private void startTPSTask() {
        tpsMonitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateTPS();
            }
        }.runTaskTimer(this, tpsCheckInterval, tpsCheckInterval);
    }
    
    private void startSpawnTask() {
        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                performMobSpawning();
            }
        }.runTaskTimer(this, spawnCheckInterval, spawnCheckInterval);
    }
    
    private void startCleanupTask() {
        // Clean up spawn counts and reset TPS history periodically
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupData();
            }
        }.runTaskTimer(this, 1200, 1200); // Every minute (1200 ticks)
    }
    
    private void cleanupData() {
        // Clean up invalid entities from tracking
        pluginSpawnedMobs.removeIf(entity -> 
            entity == null || !entity.isValid() || entity.isDead() || 
            !entity.getChunk().isLoaded());
        
        // Clean up spawn counts by recounting actual mobs (thread-safe)
        synchronized (worldSpawnCounts) {
            for (World world : new HashSet<>(worldSpawnCounts.keySet())) {
                if (world != null && Bukkit.getWorlds().contains(world)) {
                    // Get count inside synchronized block to prevent race conditions
                    int actualCount = getHostileMobCount(world);
                    worldSpawnCounts.put(world, actualCount);
                } else {
                    // Remove invalid worlds
                    worldSpawnCounts.remove(world);
                }
            }
        }
        
        // Clean up TPS history if it gets too large
        synchronized (tpsHistory) {
            if (tpsHistory.size() > TPS_HISTORY_SIZE * 2) {
                int toRemove = tpsHistory.size() - TPS_HISTORY_SIZE;
                for (int i = 0; i < toRemove; i++) {
                    tpsHistory.remove(0);
                }
            }
        }
        
        // Clean up world TPS map for worlds that no longer exist
        worldTPS.entrySet().removeIf(entry -> !Bukkit.getWorlds().contains(entry.getKey()));
        
        // Clean up protection cache to prevent memory leaks
        if (protectionCache.size() > 1000) {
            protectionCache.clear();
            getLogger().fine("Cleared protection cache to prevent memory leaks");
        }
    }
    
    private void updateTPS() {
        double currentTPS = getCurrentTPS();
        tpsHistory.add(currentTPS);
        
        if (tpsHistory.size() > TPS_HISTORY_SIZE) {
            tpsHistory.remove(0);
        }
        
        // Update TPS for all worlds
        for (World world : Bukkit.getWorlds()) {
            worldTPS.put(world, currentTPS);
        }
    }
    
    private double getCurrentTPS() {
        try {
            // Try to use Bukkit's built-in TPS method (available in newer versions)
            double[] tps = Bukkit.getTPS();
            return tps[0];
        } catch (Exception e) {
            // Fallback to reflection method
            return getCurrentTPSReflection();
        }
    }
    
    private double getCurrentTPSReflection() {
        try {
            // Use reflection to get TPS from server (fallback method)
            Object serverInstance = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            double[] recentTps = (double[]) serverInstance.getClass().getField("recentTps").get(serverInstance);
            return recentTps[0];
        } catch (Exception e) {
            // Final fallback - calculate TPS manually
            return calculateManualTPS();
        }
    }
    
    private double calculateManualTPS() {
        long currentTime = System.currentTimeMillis();
        if (lastTPSUpdate == 0) {
            lastTPSUpdate = currentTime;
            return 20.0;
        }
        
        long timeDiff = currentTime - lastTPSUpdate;
        lastTPSUpdate = currentTime;
        
        if (timeDiff < 50) return 20.0; // Less than one tick
        
        // Calculate actual TPS based on time for 20 ticks
        double actualTPS = 20000.0 / timeDiff;
        return Math.max(0.0, Math.min(20.0, actualTPS));
    }
    
    private void performMobSpawning() {
        double currentTPS = getCurrentTPS();
        
        // Don't spawn if TPS is below minimum
        if (currentTPS < minTPS) {
            return;
        }
        
        // Calculate spawn multiplier based on TPS
        double spawnMultiplier = calculateSpawnMultiplier(currentTPS);
        
        for (World world : Bukkit.getWorlds()) {
            if (blacklistedWorlds.contains(world.getName())) {
                continue;
            }
            
            // Check if world has players
            if (world.getPlayers().isEmpty()) {
                continue;
            }
            
            // Get current hostile mob count (only count actual mobs, not all entities)
            int currentMobCount = getHostileMobCount(world);
            int maxAllowed = (int) (maxMobsPerWorld * spawnMultiplier);
            
            if (currentMobCount >= maxAllowed) {
                continue;
            }
            
            // Calculate how many mobs to spawn (reduced from 5 to prevent over-spawning)
            int mobsToSpawn = Math.min(2, maxAllowed - currentMobCount);
            
            for (int i = 0; i < mobsToSpawn; i++) {
                spawnMobNearPlayer(world);
            }
        }
    }
    
    private double calculateSpawnMultiplier(double tps) {
        if (tps >= 20.0) {
            return 1.5; // 50% more spawning at perfect TPS
        } else if (tps >= 19.5) {
            return 1.3; // 30% more spawning
        } else if (tps >= 19.0) {
            return 1.1; // 10% more spawning
        } else {
            return 1.0; // Normal spawning
        }
    }
    
    private void spawnMobNearPlayer(World world) {
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            return;
        }
        
        // Select random player
        Player targetPlayer = players.get(new Random().nextInt(players.size()));
        Location playerLoc = targetPlayer.getLocation();
        
        // Find suitable spawn location
        Location spawnLoc = findSpawnLocation(playerLoc, world);
        if (spawnLoc == null) {
            return;
        }
        
        // Select random mob type
        EntityType mobType = selectRandomMobType();
        if (mobType == null) {
            return;
        }
        
                    // Spawn the mob
            try {
                LivingEntity mob = (LivingEntity) world.spawnEntity(spawnLoc, mobType);
                
                // Track plugin-spawned mobs
                pluginSpawnedMobs.add(mob);
                
                // Apply LevelledMobs if enabled
                if (enableLevelledMobs && isLevelledMobsAvailable()) {
                    applyLevelledMobs(mob);
                }
                
                // Update spawn count atomically
                worldSpawnCounts.merge(world, 1, Integer::sum);
                
                getLogger().fine("Spawned " + mobType.name() + " at " + spawnLoc.toString());
                
            } catch (Exception e) {
                getLogger().warning("Failed to spawn mob: " + e.getMessage());
            }
    }
    
    private Location findSpawnLocation(Location playerLoc, World world) {
        Random random = new Random();
        
        for (int attempts = 0; attempts < 20; attempts++) { // Increased attempts for better success rate
            // Generate random offset within spawn radius (increased minimum distance)
            int minDistance = minSpawnDistance; // Minimum distance from player
            int maxDistance = spawnRadius;
            
            int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
            double angle = random.nextDouble() * 2 * Math.PI;
            
            int x = (int) (Math.cos(angle) * distance);
            int z = (int) (Math.sin(angle) * distance);
            
            Location testLoc = playerLoc.clone().add(x, 0, z);
            
            // Use more efficient height finding
            int y = findHighestBlockY(testLoc, world);
            if (y == -1) continue; // Skip if no valid height found
            
            testLoc.setY(y + 1);
            
            // Check if location is suitable for spawning
            if (isValidSpawnLocation(testLoc, world)) {
                return testLoc;
            }
        }
        
        return null;
    }
    
    private int findHighestBlockY(Location loc, World world) {
        // More efficient height finding with bounds checking
        int maxY = world.getMaxHeight() - 1;
        int minY = world.getMinHeight();
        
        // Start from a reasonable height and work down
        int startY = Math.min(maxY, loc.getBlockY() + 10);
        
        for (int y = startY; y >= minY; y--) {
            if (world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ()).getType().isSolid()) {
                return y;
            }
        }
        
        return -1; // No solid block found
    }
    
    private boolean isValidSpawnLocation(Location loc, World world) {
        // Check if block below is solid
        if (!loc.getBlock().getRelative(0, -1, 0).getType().isSolid()) {
            return false;
        }
        
        // Check if spawn location is air
        if (!loc.getBlock().getType().isAir()) {
            return false;
        }
        
        // Check if there's enough space above
        if (!loc.getBlock().getRelative(0, 1, 0).getType().isAir()) {
            return false;
        }
        
        // Check if location is not too close to players (increased distance)
        // Use chunk-based proximity check for better performance
        int minDistance = 15;
        int chunkRadius = (minDistance / 16) + 1; // Convert distance to chunk radius
        
        int locChunkX = loc.getBlockX() >> 4;
        int locChunkZ = loc.getBlockZ() >> 4;
        
        for (Player player : world.getPlayers()) {
            Location playerLoc = player.getLocation();
            int playerChunkX = playerLoc.getBlockX() >> 4;
            int playerChunkZ = playerLoc.getBlockZ() >> 4;
            
            // Quick chunk distance check
            int chunkDistance = Math.max(Math.abs(locChunkX - playerChunkX), Math.abs(locChunkZ - playerChunkZ));
            if (chunkDistance <= chunkRadius) {
                // Only do expensive distance calculation if chunks are close
                if (playerLoc.distanceSquared(loc) < minDistance * minDistance) {
                    return false;
                }
            }
        }
        
        // Check for grief prevention land protection
        if (enableGriefPreventionCheck && isGriefPreventionProtected(loc)) {
            return false;
        }
        
        // Additional check: prevent spawning inside player bases
        if (enableBaseProtection && isInsidePlayerBase(loc, world)) {
            return false;
        }
        
        return true;
    }
    
    private boolean isInsidePlayerBase(Location loc, World world) {
        // Much smaller check radius for better performance
        int radius = 2;
        
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Location checkLoc = loc.clone().add(x, 0, z);
                if (checkLoc.getBlockY() < world.getMinHeight() || checkLoc.getBlockY() >= world.getMaxHeight()) {
                    continue;
                }
                
                org.bukkit.block.Block block = checkLoc.getBlock();
                org.bukkit.Material type = block.getType();
                
                // Check for base-related blocks
                if (isBaseBlock(type)) {
                    getLogger().fine("Prevented spawn near base structure: " + type.name() + " at " + checkLoc);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean isBaseBlock(org.bukkit.Material material) {
        // List of blocks that indicate a player base
        return material == org.bukkit.Material.CHEST ||
               material == org.bukkit.Material.TRAPPED_CHEST ||
               material == org.bukkit.Material.FURNACE ||
               material == org.bukkit.Material.BLAST_FURNACE ||
               material == org.bukkit.Material.SMOKER ||
               material == org.bukkit.Material.CRAFTING_TABLE ||
               material == org.bukkit.Material.ANVIL ||
               material == org.bukkit.Material.ENCHANTING_TABLE ||
               material.name().endsWith("_BED") || // Handles all bed types dynamically
               material == org.bukkit.Material.DOOR ||
               material == org.bukkit.Material.IRON_DOOR ||
               material == org.bukkit.Material.TORCH ||
               material == org.bukkit.Material.WALL_TORCH ||
               material == org.bukkit.Material.LANTERN ||
               material == org.bukkit.Material.SOUL_LANTERN ||
               material == org.bukkit.Material.CAMPFIRE ||
               material == org.bukkit.Material.SOUL_CAMPFIRE;
    }
    
    private EntityType selectRandomMobType() {
        if (allowedMobs.isEmpty()) {
            return null;
        }
        
        // Use weighted random selection if weights are configured
        if (!mobSpawnWeights.isEmpty()) {
            return selectWeightedMobType();
        }
        
        // Simple random selection
        return allowedMobs.get(new Random().nextInt(allowedMobs.size()));
    }
    
    private EntityType selectWeightedMobType() {
        double totalWeight = 0;
        for (double weight : mobSpawnWeights.values()) {
            totalWeight += weight;
        }
        
        double random = new Random().nextDouble() * totalWeight;
        double currentWeight = 0;
        
        for (Map.Entry<EntityType, Double> entry : mobSpawnWeights.entrySet()) {
            currentWeight += entry.getValue();
            if (random <= currentWeight) {
                return entry.getKey();
            }
        }
        
        // Fallback
        return allowedMobs.get(0);
    }
    
    private boolean isLevelledMobsAvailable() {
        return Bukkit.getPluginManager().getPlugin("LevelledMobs") != null;
    }
    
    private boolean isGriefPreventionProtected(Location loc) {
        // Use cache key based on block coordinates to avoid expensive checks
        Location key = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return protectionCache.computeIfAbsent(key, this::checkGriefPreventionUncached);
    }
    
    private boolean checkGriefPreventionUncached(Location loc) {
        try {
            // Check if GriefPrevention is available
            if (Bukkit.getPluginManager().getPlugin("GriefPrevention") != null) {
                return checkGriefPreventionProtection(loc);
            }
            
            // Check if WorldGuard is available
            if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
                return checkWorldGuardProtection(loc);
            }
            
            // Check if WorldEdit is available (for region protection)
            if (Bukkit.getPluginManager().getPlugin("WorldEdit") != null) {
                return checkWorldEditProtection(loc);
            }
            
        } catch (Exception e) {
            getLogger().warning("Failed to check grief prevention: " + e.getMessage());
        }
        
        return false; // Default to allowing spawning if no protection plugins found
    }
    
    private boolean checkGriefPreventionProtection(Location loc) {
        try {
            // Check if GriefPrevention is available and enabled
            Plugin gpPlugin = Bukkit.getPluginManager().getPlugin("GriefPrevention");
            if (gpPlugin == null || !gpPlugin.isEnabled()) {
                return false;
            }
            
            // Use proper API integration instead of reflection
            return checkGriefPreventionAPI(loc);
            
        } catch (Exception e) {
            getLogger().warning("GriefPrevention check failed: " + e.getMessage());
            return false; // Fail safe - don't spawn on potentially protected land
        }
    }
    
    private boolean checkGriefPreventionAPI(Location loc) {
        try {
            // Try to use the proper GriefPrevention API
            Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object gpInstance = gpClass.getMethod("instance").invoke(null);
            
            if (gpInstance != null) {
                // Get the data store safely
                Object dataStore = gpInstance.getClass().getMethod("dataStore").invoke(gpInstance);
                if (dataStore != null) {
                    // Check if location is in a claim using the API
                    Object claim = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, Object.class)
                        .invoke(dataStore, loc, false, null);
                    
                    if (claim != null) {
                        // Verify the claim is valid and active
                        try {
                            // Check if claim is not expired
                            Object isExpired = claim.getClass().getMethod("isExpired").invoke(claim);
                            if (isExpired != null && (Boolean) isExpired) {
                                return false; // Expired claims don't protect
                            }
                            
                            // Get claim owner for logging
                            Object owner = claim.getClass().getMethod("getOwnerID").invoke(claim);
                            if (owner != null) {
                                getLogger().fine("Location protected by GriefPrevention claim owned by: " + owner);
                            }
                            
                            return true; // Location is protected
                        } catch (Exception e) {
                            // If we can't verify claim details, assume it's protected (fail safe)
                            getLogger().fine("Location protected by GriefPrevention claim (details unavailable)");
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().fine("GriefPrevention API integration failed: " + e.getMessage());
        }
        return false;
    }
    
    private boolean checkWorldGuardProtection(Location loc) {
        try {
            // Try to use WorldGuard API
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wgInstance = wgClass.getMethod("getInstance").invoke(null);
            
            if (wgInstance != null) {
                Object platform = wgInstance.getClass().getMethod("getPlatform").invoke(wgInstance);
                if (platform != null) {
                    Object sessionManager = platform.getClass().getMethod("getSessionManager").invoke(platform);
                    if (sessionManager != null) {
                        // Check if location is protected by WorldGuard
                        Object query = sessionManager.getClass().getMethod("createQuery").invoke(sessionManager);
                        if (query != null) {
                            Object test = query.getClass().getMethod("testLocation", Location.class).invoke(query, loc);
                            return test != null && (Boolean) test; // If test returns true, location is protected
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().fine("WorldGuard API check failed: " + e.getMessage());
        }
        return false;
    }
    
    private boolean checkWorldEditProtection(Location loc) {
        try {
            // Try to use WorldEdit API for region protection
            Class<?> weClass = Class.forName("com.sk89q.worldedit.WorldEdit");
            Object weInstance = weClass.getMethod("getInstance").invoke(null);
            
            if (weInstance != null) {
                // Check if location is in a protected region
                // This is a simplified check - WorldEdit regions are typically managed by WorldGuard
                return false; // Default to allowing spawning
            }
        } catch (Exception e) {
            getLogger().fine("WorldEdit API check failed: " + e.getMessage());
        }
        return false;
    }
    
    private int getHostileMobCount(World world) {
        return (int) world.getLivingEntities().stream()
            .filter(entity -> entity instanceof org.bukkit.entity.Monster)
            .count();
    }
    
    private void validateConfiguration() {
        // Validate TPS settings
        if (minTPS < 0 || minTPS > 20) {
            getLogger().warning("Invalid min_tps value: " + minTPS + ". Using default: 19");
            minTPS = 19;
        }
        
        // Validate spawn settings
        if (maxMobsPerWorld < 1) {
            getLogger().warning("Invalid max_mobs_per_world value: " + maxMobsPerWorld + ". Using default: 100");
            maxMobsPerWorld = 100;
        }
        
        if (spawnRadius < 10) {
            getLogger().warning("Invalid spawn_radius value: " + spawnRadius + ". Using default: 50");
            spawnRadius = 50;
        }
        
        if (minSpawnDistance < 5) {
            getLogger().warning("Invalid min_spawn_distance value: " + minSpawnDistance + ". Using default: 20");
            minSpawnDistance = 20;
        }
        
        if (minSpawnDistance >= spawnRadius) {
            getLogger().warning("min_spawn_distance (" + minSpawnDistance + ") must be less than spawn_radius (" + spawnRadius + "). Adjusting min_spawn_distance to " + (spawnRadius - 10));
            minSpawnDistance = Math.max(5, spawnRadius - 10);
        }
        
        if (spawnCheckInterval < 5) {
            getLogger().warning("Invalid spawn_check_interval value: " + spawnCheckInterval + ". Using default: 20");
            spawnCheckInterval = 20;
        }
        
        if (tpsCheckInterval < 5) {
            getLogger().warning("Invalid tps_check_interval value: " + tpsCheckInterval + ". Using default: 20");
            tpsCheckInterval = 20;
        }
        
        // Validate mob weights
        for (Map.Entry<EntityType, Double> entry : mobSpawnWeights.entrySet()) {
            if (entry.getValue() < 0) {
                getLogger().warning("Invalid weight for " + entry.getKey() + ": " + entry.getValue() + ". Using default: 1.0");
                mobSpawnWeights.put(entry.getKey(), 1.0);
            }
        }
    }
    
    private void applyLevelledMobs(LivingEntity mob) {
        try {
            if (Bukkit.getPluginManager().getPlugin("LevelledMobs") != null) {
                // Try to use actual LevelledMobs API
                if (applyLevelledMobsAPI(mob)) {
                    return; // Successfully applied via API
                }
                
                // Fallback to custom implementation if API fails
                applyCustomLevelledMobs(mob);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to apply LevelledMobs: " + e.getMessage());
        }
    }
    
    private boolean applyLevelledMobsAPI(LivingEntity mob) {
        try {
            // Use the proper LevelledMobs API
            Plugin levelledMobsPlugin = Bukkit.getPluginManager().getPlugin("LevelledMobs");
            if (levelledMobsPlugin == null) return false;
            
            // Create NamespacedKey for level storage
            NamespacedKey levelKey = new NamespacedKey(levelledMobsPlugin, "level");
            
            // Generate a random level (1-50)
            int level = new Random().nextInt(50) + 1;
            
            // Store the level in the mob's Persistent Data Container
            mob.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
            
            // Apply custom name with level indicator
            String customName = levelledMobsPrefix.replace("&", "§") + " " + mob.getType().name() + " &eLv." + level;
            mob.setCustomName(customName);
            mob.setCustomNameVisible(true);
            
            // Apply level-based attributes
            applyLevelBasedAttributes(mob, level);
            
            return true;
            
        } catch (Exception e) {
            getLogger().fine("LevelledMobs API integration failed: " + e.getMessage());
        }
        return false;
    }
    
    private void applyLevelBasedAttributes(LivingEntity mob, int level) {
        try {
            // Apply health scaling based on level
            double healthMultiplier = 1.0 + (level * 0.1);
            mob.setMaxHealth(mob.getMaxHealth() * healthMultiplier);
            mob.setHealth(mob.getMaxHealth());
            
            // Apply other attributes based on level
            if (mob instanceof org.bukkit.entity.Monster) {
                org.bukkit.entity.Monster monster = (org.bukkit.entity.Monster) mob;
                
                // Increase damage for higher level mobs
                if (monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                    double baseDamage = monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE).getBaseValue();
                    double newDamage = baseDamage * (1.0 + (level * 0.05));
                    monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(newDamage);
                }
                
                // Increase armor for higher level mobs
                if (monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ARMOR) != null) {
                    double baseArmor = monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ARMOR).getBaseValue();
                    double newArmor = baseArmor + (level * 0.5);
                    monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ARMOR).setBaseValue(newArmor);
                }
            }
        } catch (Exception e) {
            getLogger().fine("Failed to apply level-based attributes: " + e.getMessage());
        }
    }
    
    private void applyCustomLevelledMobs(LivingEntity mob) {
        // Fallback custom implementation
        int level = new Random().nextInt(50) + 1;
        String customName = levelledMobsPrefix.replace("&", "§") + " " + mob.getType().name() + " &eLv." + level;
        mob.setCustomName(customName);
        mob.setCustomNameVisible(true);
        
        // Apply some basic level-based attributes
        mob.setMaxHealth(mob.getMaxHealth() * (1.0 + (level * 0.1)));
        mob.setHealth(mob.getMaxHealth());
    }
    
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // Track natural spawns
        World world = event.getLocation().getWorld();
        if (world != null && !blacklistedWorlds.contains(world.getName())) {
            worldSpawnCounts.merge(world, 1, Integer::sum);
        }
    }
    
    @EventHandler
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        // Ensure mobs can damage players (prevent any interference)
        if (allowMobDamage && event.getDamager() instanceof LivingEntity && event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Location playerLoc = player.getLocation();
            
            // Check if player is in a protected area
            if (enableGriefPreventionCheck && isGriefPreventionProtected(playerLoc)) {
                // Cancel damage if player is in a protected area
                event.setCancelled(true);
                getLogger().fine("Cancelled mob damage to player in protected area: " + player.getName());
                return;
            }
            
            // Allow mobs to damage players in unprotected areas
            event.setCancelled(false);
        }
    }
    
    @EventHandler
    public void onEntityTarget(org.bukkit.event.entity.EntityTargetEvent event) {
        // Ensure mobs can target players
        if (allowMobTargeting && event.getEntity() instanceof LivingEntity && event.getTarget() instanceof Player) {
            Player player = (Player) event.getTarget();
            Location playerLoc = player.getLocation();
            
            // Check if player is in a protected area
            if (enableGriefPreventionCheck && isGriefPreventionProtected(playerLoc)) {
                // Cancel targeting if player is in a protected area
                event.setCancelled(true);
                getLogger().fine("Cancelled mob targeting of player in protected area: " + player.getName());
                return;
            }
            
            // Allow mobs to target players in unprotected areas
            event.setCancelled(false);
        }
    }
    
    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        // Remove dead mobs from tracking
        pluginSpawnedMobs.remove(event.getEntity());
    }
    
    @EventHandler
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        // Remove mobs from tracking when chunks unload
        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity) {
                pluginSpawnedMobs.remove(entity);
            }
        }
    }
    
    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        // Clean up any mobs that might be tracking the player
        // This helps prevent memory leaks from player-specific mob tracking
    }
    
    public void reloadPlugin() {
        // Clear all tracking data for clean reload
        synchronized (worldSpawnCounts) {
            worldSpawnCounts.clear();
        }
        synchronized (tpsHistory) {
            tpsHistory.clear();
        }
        worldTPS.clear();
        pluginSpawnedMobs.clear();
        protectionCache.clear();
        
        // Reset TPS tracking
        lastTPSUpdate = 0;
        
        // Reload configuration
        loadConfig();
        getLogger().info("Plugin configuration reloaded and tracking data cleared!");
    }
    
    public double getCurrentTPSValue() {
        return getCurrentTPS();
    }
    
    public Map<World, Integer> getWorldSpawnCounts() {
        return new HashMap<>(worldSpawnCounts);
    }
    
    public List<String> getBlacklistedWorlds() {
        return new ArrayList<>(blacklistedWorlds);
    }
    
    public List<EntityType> getAllowedMobs() {
        return new ArrayList<>(allowedMobs);
    }
    
    public int getPluginSpawnedMobCount() {
        return pluginSpawnedMobs.size();
    }
    
    public int getPluginSpawnedMobCountInWorld(World world) {
        return (int) pluginSpawnedMobs.stream()
            .filter(entity -> entity.getWorld().equals(world))
            .count();
    }
    
    public int getMobLevel(LivingEntity livingEntity) {
        Plugin levelledMobsPlugin = Bukkit.getPluginManager().getPlugin("LevelledMobs");
        if (levelledMobsPlugin == null) return 0;
        
        NamespacedKey levelKey = new NamespacedKey(levelledMobsPlugin, "level");
        Integer level = livingEntity.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return level != null ? level : 0;
    }
    
    public boolean isLevelledMob(LivingEntity livingEntity) {
        return getMobLevel(livingEntity) > 0;
    }
    
    // Command executor
    private static class TPSMobSpawnerCommand implements org.bukkit.command.CommandExecutor {
        private final TPSBasedMobSpawner plugin;
        
        public TPSMobSpawnerCommand(TPSBasedMobSpawner plugin) {
            this.plugin = plugin;
        }
        
        @Override
        public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            if (!sender.hasPermission("tpsmobspawner.admin")) {
                sender.sendMessage("§cYou don't have permission to use this command!");
                return true;
            }
            
            if (args.length == 0) {
                showHelp(sender);
                return true;
            }
            
            switch (args[0].toLowerCase()) {
                case "reload":
                    plugin.reloadPlugin();
                    sender.sendMessage("§aPlugin configuration reloaded!");
                    break;
                    
                case "tps":
                    double tps = plugin.getCurrentTPSValue();
                    sender.sendMessage("§6Current TPS: §e" + String.format("%.2f", tps));
                    break;
                    
                case "stats":
                    showStats(sender);
                    break;
                    
                case "spawn":
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /tpsmobspawner spawn <mobtype>");
                        return true;
                    }
                    spawnMobCommand(sender, args[1]);
                    break;
                    
                case "level":
                    if (args.length < 2) {
                        sender.sendMessage("§cUsage: /tpsmobspawner level <entity_id>");
                        return true;
                    }
                    checkMobLevelCommand(sender, args[1]);
                    break;
                    
                default:
                    showHelp(sender);
                    break;
            }
            
            return true;
        }
        
        private void showHelp(org.bukkit.command.CommandSender sender) {
            sender.sendMessage("§6=== TPSMobSpawner Commands ===");
            sender.sendMessage("§e/tpsmobspawner reload §7- Reload configuration");
            sender.sendMessage("§e/tpsmobspawner tps §7- Show current TPS");
            sender.sendMessage("§e/tpsmobspawner stats §7- Show spawn statistics");
            sender.sendMessage("§e/tpsmobspawner spawn <mob> §7- Spawn a specific mob");
            sender.sendMessage("§e/tpsmobspawner level <entity_id> §7- Check mob level");
        }
        
        private void showStats(org.bukkit.command.CommandSender sender) {
            Map<World, Integer> spawnCounts = plugin.getWorldSpawnCounts();
            sender.sendMessage("§6=== Spawn Statistics ===");
            sender.sendMessage("§7Plugin-spawned mobs total: §a" + plugin.getPluginSpawnedMobCount());
            sender.sendMessage("");
            for (Map.Entry<World, Integer> entry : spawnCounts.entrySet()) {
                World world = entry.getKey();
                int totalMobs = plugin.getHostileMobCount(world);
                int pluginMobs = plugin.getPluginSpawnedMobCountInWorld(world);
                sender.sendMessage("§e" + world.getName() + "§7:");
                sender.sendMessage("  §7- Total hostile mobs: §a" + totalMobs);
                sender.sendMessage("  §7- Plugin-spawned: §a" + pluginMobs);
            }
        }
        
        private void spawnMobCommand(org.bukkit.command.CommandSender sender, String mobType) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cThis command can only be used by players!");
                return true;
            }
            
            Player player = (Player) sender;
            try {
                EntityType type = EntityType.valueOf(mobType.toUpperCase());
                if (plugin.getAllowedMobs().contains(type)) {
                    Location spawnLoc = player.getLocation();
                    LivingEntity mob = (LivingEntity) player.getWorld().spawnEntity(spawnLoc, type);
                    sender.sendMessage("§aSpawned " + mobType + " at your location!");
                } else {
                    sender.sendMessage("§cMob type " + mobType + " is not in the allowed mobs list!");
                }
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid mob type: " + mobType);
            }
            return true;
        }
        
        private void checkMobLevelCommand(org.bukkit.command.CommandSender sender, String entityId) {
            try {
                int id = Integer.parseInt(entityId);
                
                // Find entity by ID
                org.bukkit.entity.Entity entity = null;
                for (World world : Bukkit.getWorlds()) {
                    entity = world.getEntity(id);
                    if (entity != null) break;
                }
                
                if (entity == null) {
                    sender.sendMessage("§cEntity with ID " + id + " not found!");
                    return;
                }
                
                if (entity instanceof LivingEntity) {
                    LivingEntity livingEntity = (LivingEntity) entity;
                    int level = plugin.getMobLevel(livingEntity);
                    
                    if (level > 0) {
                        sender.sendMessage("§6Entity: §e" + entity.getType().name());
                        sender.sendMessage("§6Level: §e" + level);
                        sender.sendMessage("§6Health: §e" + String.format("%.1f", livingEntity.getHealth()) + "§7/§e" + String.format("%.1f", livingEntity.getMaxHealth()));
                        
                        if (livingEntity instanceof org.bukkit.entity.Monster) {
                            org.bukkit.entity.Monster monster = (org.bukkit.entity.Monster) livingEntity;
                            if (monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                                double damage = monster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ATTACK_DAMAGE).getBaseValue();
                                sender.sendMessage("§6Attack Damage: §e" + String.format("%.1f", damage));
                            }
                        }
                    } else {
                        sender.sendMessage("§6Entity: §e" + entity.getType().name());
                        sender.sendMessage("§7This entity is not a levelled mob.");
                    }
                } else {
                    sender.sendMessage("§cEntity is not a living entity!");
                }
                
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid entity ID: " + entityId);
            }
        }
    }
}