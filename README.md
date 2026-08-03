# ECHO Sound Engine for MINECRAFT

ECHO Sound Engine is a Fabric-based physical speaker and spatial sound mod developed
for Minecraft 1.20.1. It processes speaker arrays, directivity, distance,
frequency loss behind walls, and room acoustics within a custom OpenAL sound engine.

## Requirements

- Minecraft 1.20.1
- Fabric Loader 0.15.7 or higher
- Fabric API
- Java 17 or higher

For multiplayer use, the mod must be installed on both the server and the connecting clients.

## Features

### Speaker system

- Subwoofer, Studio Monitor, and Line Array Module blocks
- Clustering that combines nearby speakers into physical emitter groups
- Power and range scaling in large arrays
- Horizontal/vertical directivity and adjustable vertical angle for Line Arrays
- `BOTH`, `LEFT`, and `RIGHT` channel selection
- 0-30 ms sample shift per speaker
- Independent mixer and parametric EQ for sub, mid, line, and normal channels

### Physical sound engine

- OpenAL-based spatial sources and HRTF-compatible positioning
- Distance attenuation and sound propagation delay
- Occlusion based on wall thickness and block permeability
- High-frequency directivity and air absorption
- Controlled harmonics depending on speaker type and power
- Time/propagation synchronization during pause, seek, and resume

### Room acoustics

- 1000-directional Spherical Fibonacci ray tracing for each probe
- Reflection and absorption calculation based on block materials
- Volume, surface area, opening, and enclosure analysis
- Sabine-based decay calculation and Tier 1-10 venue profiles
- Dynamic reverb reduction in open and semi-open spaces
- Smooth crossfading between multiple emitter groups and two physical room buses
- Reverb heatmap and acoustic zone display on the Sound Tablet

### Playback and resilience

- Local OGG/PCM and internet audio sources
- LavaPlayer integration for YouTube, SoundCloud, and direct HTTP(S) sources
- Multiple sessions and multiplayer synchronization
- Engine recovery without restarting the game when an audio device is plugged/unplugged
- Optional pause/resume and cleanup integration for the Replay Mod
- Request/cancellation system preventing stale decode tasks and callbacks

## Installation

1. Install Fabric Loader and Fabric API for Minecraft 1.20.1.
2. Place the distribution JAR in the `.minecraft/mods` folder.
3. Launch the game using the Fabric profile.

Internet audio libraries are embedded in the mod JAR; you do not need to install LavaPlayer separately.

## Usage

1. Get speakers and the `Sound Tablet` item from the `ECHO Sound Engine` tab in the creative inventory.
2. Place the Subwoofer, Studio Monitor, and Line Array blocks in your desired layout.
3. Right-click a speaker to configure the channel, sample shift, and vertical angle on supported blocks.
4. Open nearby setups using the Sound Tablet.
5. Search for a track name or paste a supported URL; adjust power, input gain, mixer, and EQ settings, then start playback.

The tablet scans for suitable speakers within a 500-block radius of the player. The heatmap view shows the acoustic results used in the actual venue scan.

## Live tuning

The following file is automatically created on the first run:

```text
.minecraft/config/audiophilecraft_tuning.json
```

The file can be edited while the game is running and reloads in about a second.
The config migration system updates old defaults, preserves user-modified values, and creates a `.bak` backup during version transitions.

## Development

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat test
.\gradlew.bat build
```

The test suite covers stream buffer limits, speaker clustering, reverb profile similarity, venue tier thresholds, and config migration/backup behavior.
Detailed ownership and lifecycle explanations are available in [ARCHITECTURE.md](ARCHITECTURE.md).

## License

ECHO Sound Engine is published under an [All Rights Reserved](LICENSE) license:
The source code is provided for viewing purposes only; copying, distribution, modification, and usage require written permission from the author (Burak).

Copyright (c) 2026 Burak. All rights reserved.
