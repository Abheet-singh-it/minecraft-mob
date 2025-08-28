package com.example.tpsmobspawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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
            // Use reflection to get TPS from server
            Object serverInstance = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            double[] recentTps = (double[]) serverInstance.getClass().getField("recentTps").get(serverInstance);
            return recentTps[0];
        } catch (Exception e) {
            // Fallback method
            return 20.0;
        }
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
            
            // Get current mob count
            int currentMobCount = world.getLivingEntities().size();
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
            
            // Apply LevelledMobs if enabled
            if (enableLevelledMobs && isLevelledMobsAvailable()) {
                applyLevelledMobs(mob);
            }
            
            // Update spawn count
            worldSpawnCounts.put(world, worldSpawnCounts.getOrDefault(world, 0) + 1);
            
            getLogger().fine("Spawned " + mobType.name() + " at " + spawnLoc.toString());
            
        } catch (Exception e) {
            getLogger().warning("Failed to spawn mob: " + e.getMessage());
        }
    }
    
    private Location findSpawnLocation(Location playerLoc, World world) {
        Random random = new Random();
        
        for (int attempts = 0; attempts < 15; attempts++) {
            // Generate random offset within spawn radius (increased minimum distance)
            int minDistance = minSpawnDistance; // Minimum distance from player
            int maxDistance = spawnRadius;
            
            int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
            double angle = random.nextDouble() * 2 * Math.PI;
            
            int x = (int) (Math.cos(angle) * distance);
            int z = (int) (Math.sin(angle) * distance);
            
            Location testLoc = playerLoc.clone().add(x, 0, z);
            
            // Find highest block at this location
            int y = world.getHighestBlockYAt(testLoc);
            testLoc.setY(y + 1);
            
            // Check if location is suitable for spawning
            if (isValidSpawnLocation(testLoc, world)) {
                return testLoc;
            }
        }
        
        return null;
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
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distance(loc) < 15) {
                return false;
            }
        }
        
        // Check for grief prevention land protection
        if (enableGriefPreventionCheck && isGriefPreventionProtected(loc)) {
            return false;
        }
        
        return true;
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
            // Try to use GriefPrevention API
            Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
            Object gpInstance = gpClass.getMethod("instance").invoke(null);
            
            if (gpInstance != null) {
                // Check if location is in a claim
                Object dataStore = gpInstance.getClass().getMethod("dataStore").invoke(gpInstance);
                if (dataStore != null) {
                    Object claim = dataStore.getClass().getMethod("getClaimAt", Location.class).invoke(dataStore, loc);
                    return claim != null; // If claim exists, location is protected
                }
            }
        } catch (Exception e) {
            getLogger().fine("GriefPrevention API check failed: " + e.getMessage());
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
    
    private void applyLevelledMobs(LivingEntity mob) {
        try {
            // Use LevelledMobs API to level the mob
            // This is a simplified implementation - you may need to adjust based on actual LevelledMobs API
            if (Bukkit.getPluginManager().getPlugin("LevelledMobs") != null) {
                // Apply custom name with level indicator
                int level = new Random().nextInt(50) + 1;
                String customName = levelledMobsPrefix.replace("&", "§") + " " + mob.getType().name() + " &eLv." + level;
                mob.setCustomName(customName);
                mob.setCustomNameVisible(true);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to apply LevelledMobs: " + e.getMessage());
        }
    }
    
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // Track natural spawns
        World world = event.getLocation().getWorld();
        if (world != null && !blacklistedWorlds.contains(world.getName())) {
            worldSpawnCounts.put(world, worldSpawnCounts.getOrDefault(world, 0) + 1);
        }
    }
    
    @EventHandler
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        // Ensure mobs can damage players (prevent any interference)
        if (allowMobDamage && event.getDamager() instanceof LivingEntity && event.getEntity() instanceof Player) {
            // Allow mobs to damage players
            event.setCancelled(false);
        }
    }
    
    @EventHandler
    public void onEntityTarget(org.bukkit.event.entity.EntityTargetEvent event) {
        // Ensure mobs can target players
        if (allowMobTargeting && event.getEntity() instanceof LivingEntity && event.getTarget() instanceof Player) {
            // Allow mobs to target players
            event.setCancelled(false);
        }
    }
    
    public void reloadPlugin() {
        loadConfig();
        getLogger().info("Plugin configuration reloaded!");
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
        }
        
        private void showStats(org.bukkit.command.CommandSender sender) {
            Map<World, Integer> spawnCounts = plugin.getWorldSpawnCounts();
            sender.sendMessage("§6=== Spawn Statistics ===");
            for (Map.Entry<World, Integer> entry : spawnCounts.entrySet()) {
                sender.sendMessage("§e" + entry.getKey().getName() + "§7: §a" + entry.getValue() + " mobs spawned");
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
    }
}