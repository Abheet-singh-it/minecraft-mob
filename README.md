# TPSBasedMobSpawner

A comprehensive Minecraft plugin that dynamically increases mob spawning around players based on server TPS (Ticks Per Second), with configurable blacklisted worlds and mob lists. Designed to work seamlessly with the LevelledMobs plugin.

## Features

- **TPS-Based Spawning**: Automatically adjusts mob spawning based on server performance
- **Minimum TPS Protection**: Maintains minimum 19 TPS by stopping spawns when TPS drops
- **World Blacklisting**: Configure specific worlds where mob spawning is disabled
- **Configurable Mob Lists**: Choose which mob types can spawn
- **Weighted Spawning**: Set spawn probabilities for different mob types
- **LevelledMobs Integration**: Works with LevelledMobs plugin for enhanced mob experience
- **Performance Monitoring**: Real-time TPS tracking and spawn statistics
- **Smart Spawn Locations**: Finds suitable spawn locations near players with configurable minimum distance
- **Grief Prevention Protection**: Respects protected land claims and regions
- **Mob Damage Control**: Ensures mobs can damage and target players
- **Admin Commands**: Built-in commands for monitoring and control
- **Memory Management**: Automatic cleanup of dead mobs and invalid data
- **Configuration Validation**: Automatic validation and correction of invalid settings
- **Efficient Algorithms**: Optimized spawn location finding and distance calculations

## Installation

1. **Download the plugin files**:
   - `TPSBasedMobSpawner.java` (main plugin file)
   - `plugin.yml` (plugin metadata)
   - `config.yml` (configuration file)

2. **Compile the Java file**:
   ```bash
   javac -cp "path/to/spigot.jar" TPSBasedMobSpawner.java
   ```

3. **Create the JAR file**:
   ```bash
   jar cf TPSBasedMobSpawner.jar TPSBasedMobSpawner.class plugin.yml config.yml
   ```

4. **Place the JAR file** in your server's `plugins` folder

5. **Restart your server** or reload plugins

## Configuration

The plugin creates a `config.yml` file with the following options:

### Settings Section
```yaml
settings:
  min_tps: 19                    # Minimum TPS required for spawning
  max_mobs_per_world: 100        # Maximum mobs per world
  spawn_radius: 50               # Spawn radius around players
  min_spawn_distance: 20         # Minimum distance from players
  spawn_check_interval: 20       # Spawn check frequency (ticks)
  tps_check_interval: 20         # TPS check frequency (ticks)
  enable_levelledmobs: true      # Enable LevelledMobs integration
  levelledmobs_prefix: "&7[&b&lLv&7]"  # Custom name prefix
  enable_grief_prevention_check: true  # Check for protected land
  allow_mob_damage: true         # Allow mobs to damage players
  allow_mob_targeting: true      # Allow mobs to target players
  enable_base_protection: true   # Prevent spawning near player bases
```

### Blacklisted Worlds
```yaml
blacklisted_worlds:
  - "world_nether"
  - "world_the_end"
  - "creative_world"
  - "minigame_world"
```

### Allowed Mobs
```yaml
allowed_mobs:
  - "ZOMBIE"
  - "SKELETON"
  - "SPIDER"
  - "CREEPER"
  # ... and many more
```

### Mob Spawn Weights
```yaml
mob_spawn_weights:
  ZOMBIE: 2.0      # 2x more likely to spawn
  SKELETON: 1.5    # 1.5x more likely to spawn
  CREEPER: 1.2     # 1.2x more likely to spawn
```

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/tpsmobspawner reload` | Reload plugin configuration | `tpsmobspawner.admin` |
| `/tpsmobspawner tps` | Show current server TPS | `tpsmobspawner.admin` |
| `/tpsmobspawner stats` | Show spawn statistics | `tpsmobspawner.admin` |
| `/tpsmobspawner spawn <mob>` | Spawn a specific mob | `tpsmobspawner.admin` |
| `/tpsmobspawner level <entity_id>` | Check mob level and attributes | `tpsmobspawner.admin` |

## How It Works

### TPS Monitoring
- Continuously monitors server TPS using reflection
- Maintains a history of TPS values for stability
- Automatically stops spawning when TPS drops below 19

### Spawn Logic
1. **TPS Check**: Verifies current TPS is above minimum threshold
2. **World Filtering**: Skips blacklisted worlds
3. **Player Proximity**: Only spawns in worlds with active players
4. **Mob Count Check**: Respects maximum mob limits per world
5. **Location Finding**: Searches for suitable spawn locations near players (respects minimum distance)
6. **Grief Prevention Check**: Avoids spawning on protected land claims
7. **Mob Selection**: Uses weighted random selection from allowed mob types
8. **LevelledMobs Integration**: Applies custom names and levels if enabled
9. **Mob Behavior**: Ensures mobs can damage and target players

### Performance Optimization
- Uses concurrent collections for thread safety
- Efficient spawn location algorithms
- Configurable check intervals to balance performance and responsiveness
- Smart mob counting to prevent over-spawning

## LevelledMobs Integration

The plugin uses the official LevelledMobs API and provides comprehensive integration:

### **API Integration**
- **Persistent Data Storage**: Uses LevelledMobs' `NamespacedKey` for level storage
- **Level Management**: Automatically assigns random levels (1-50) to spawned mobs
- **Attribute Scaling**: Applies level-based health, damage, and armor bonuses
- **Custom Names**: Configurable name prefixes with level indicators

### **Level-Based Features**
- **Health Scaling**: +10% health per level
- **Damage Scaling**: +5% attack damage per level  
- **Armor Scaling**: +0.5 armor per level
- **Persistent Storage**: Levels persist through server restarts

### **Commands**
- `/tpsmobspawner level <entity_id>` - Check mob level and attributes
- Shows health, damage, and other level-based stats

### **Compatibility**
- Works with LevelledMobs 3.9.3+ (latest versions)
- Uses official API methods when available
- Graceful fallback to custom implementation

## GriefPrevention Integration

The plugin provides comprehensive protection against spawning mobs on protected land:

### **Supported Protection Plugins**
- **GriefPrevention**: Full API integration with claim detection
- **WorldGuard**: Region protection and flag checking
- **WorldEdit**: Region-based protection systems

### **Protection Features**
- **Claim Detection**: Automatically detects GriefPrevention claims
- **Owner Verification**: Validates claim ownership and status
- **Region Protection**: Respects WorldGuard protected regions
- **Base Protection**: Prevents spawning near player structures
- **Player Safety**: Mobs cannot damage players in protected areas
- **Configurable**: Can be enabled/disabled via configuration

### **Base Protection System**
- **Structure Detection**: Identifies player bases by checking for base-related blocks
- **Safe Distance**: Maintains minimum distance from chests, furnaces, beds, etc.
- **Performance Optimized**: Uses efficient chunk-based proximity checks
- **Configurable Radius**: 5-block radius protection around base structures

### **Configuration Options**
```yaml
settings:
  enable_grief_prevention_check: true  # Enable protection checks
```

### **How It Works**
1. **Location Check**: Before spawning, checks if location is protected
2. **Plugin Detection**: Automatically detects installed protection plugins
3. **API Integration**: Uses official plugin APIs when available
4. **Fallback Handling**: Graceful degradation if APIs are unavailable

## Permissions

- `tpsmobspawner.admin` - Access to all plugin commands (default: op)

## Troubleshooting

### Common Issues

1. **Plugin won't load**:
   - Ensure you have the correct Spigot version
   - Check that all files are in the correct locations
   - Verify Java compilation was successful

2. **Mobs not spawning**:
   - Check if TPS is above 19
   - Verify world is not blacklisted
   - Ensure allowed_mobs list is not empty
   - Check spawn radius and interval settings

3. **Performance issues**:
   - Increase spawn_check_interval
   - Reduce max_mobs_per_world
   - Add more worlds to blacklist
   - Adjust spawn_radius

### Debug Information

Enable debug logging in your server.properties:
```properties
debug=true
```

The plugin logs important information including:
- Configuration loading status
- TPS values
- Spawn attempts and results
- Error messages

## Performance Impact

- **Minimal CPU usage** with configurable check intervals
- **Efficient memory management** using concurrent collections
- **Smart spawning** prevents server overload
- **TPS protection** ensures server stability
- **Optimized algorithms** with chunk-based proximity checks
- **Protection caching** reduces expensive API calls
- **Efficient base detection** with minimal block checking
- **Chunk-based mob counting** eliminates expensive world iteration
- **Smart caching system** for protection checks and mob counts
- **Efficient event handling** with minimal overhead

## Compatibility

- **Minecraft Versions**: 1.13+ (API version 1.13)
- **Server Software**: Spigot, Paper, Bukkit
- **Dependencies**: None (optional: LevelledMobs)
- **Java Version**: Java 8+

## Support

For issues or questions:
1. Check the troubleshooting section
2. Review server logs for error messages
3. Verify configuration settings
4. Test with default configuration

## License

This plugin is provided as-is for educational and server use. Modify and distribute as needed.

## Changelog

### Version 1.0.0
- Initial release
- TPS-based mob spawning
- World blacklisting
- Configurable mob lists
- LevelledMobs integration
- Admin commands
- Performance monitoring