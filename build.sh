#!/bin/bash

# TPSBasedMobSpawner Build Script
# This script compiles the plugin and creates a JAR file

echo "=== TPSBasedMobSpawner Build Script ==="

# Check if Java is installed
if ! command -v javac &> /dev/null; then
    echo "Error: Java compiler (javac) not found!"
    echo "Please install Java Development Kit (JDK) first."
    exit 1
fi

# Check if required files exist
if [ ! -f "TPSBasedMobSpawner.java" ]; then
    echo "Error: TPSBasedMobSpawner.java not found!"
    exit 1
fi

if [ ! -f "plugin.yml" ]; then
    echo "Error: plugin.yml not found!"
    exit 1
fi

if [ ! -f "config.yml" ]; then
    echo "Error: config.yml not found!"
    exit 1
fi

# Create build directory
mkdir -p build
cd build

echo "Compiling Java source..."
javac -cp "../lib/*" ../TPSBasedMobSpawner.java

if [ $? -ne 0 ]; then
    echo "Error: Compilation failed!"
    exit 1
fi

echo "Creating JAR file..."
jar cf TPSBasedMobSpawner.jar TPSBasedMobSpawner.class ../plugin.yml ../config.yml

if [ $? -ne 0 ]; then
    echo "Error: JAR creation failed!"
    exit 1
fi

echo "Build successful!"
echo "JAR file created: build/TPSBasedMobSpawner.jar"
echo ""
echo "To install:"
echo "1. Copy build/TPSBasedMobSpawner.jar to your server's plugins folder"
echo "2. Restart your server or reload plugins"
echo "3. Configure the plugin using the generated config.yml"
echo ""
echo "Note: If you have Spigot/Paper in a different location, update the -cp path in this script."