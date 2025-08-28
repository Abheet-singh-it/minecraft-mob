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
- **Smart Spawn Locations**: Finds suitable spawn locations near players
- **Admin Commands**: Built-in commands for monitoring and control

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
  spawn_check_interval: 20       # Spawn check frequency (ticks)
  tps_check_interval: 20         # TPS check frequency (ticks)
  enable_levelledmobs: true      # Enable LevelledMobs integration
  levelledmobs_prefix: "&7[&b&lLv&7]"  # Custom name prefix
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
5. **Location Finding**: Searches for suitable spawn locations near players
6. **Mob Selection**: Uses weighted random selection from allowed mob types
7. **LevelledMobs Integration**: Applies custom names and levels if enabled

### Performance Optimization
- Uses concurrent collections for thread safety
- Efficient spawn location algorithms
- Configurable check intervals to balance performance and responsiveness
- Smart mob counting to prevent over-spawning

## LevelledMobs Integration

The plugin automatically detects if LevelledMobs is installed and:
- Applies custom names with level indicators
- Uses configurable name prefixes
- Maintains compatibility with LevelledMobs features

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