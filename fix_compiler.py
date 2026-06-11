import re

path = 'src/main/java/com/audiophilecraft/sound/AudioEngine.java'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Fix prepareStreamBuffers
content = content.replace(
    "public void prepareStreamBuffers(String trackId) {",
    "public void prepareStreamBuffers(PlaybackSession session, String trackId) {"
)
content = content.replace(
    "session.getStreamBuffers().clear();",
    "session.getStreamBuffers().clear();"
)

# 2. Fix playTrack calls to prepareStreamBuffers
content = content.replace(
    "prepareStreamBuffers(trackId);",
    "prepareStreamBuffers(sessions.get(sessionUUID), trackId);"
)

# Fix playTrack's session.setPlaying
content = content.replace(
    "session.setPlaying(true);\n            session.setPaused(false);\n            for (AudioStreamBuffer buffer : session.getStreamBuffers().values())",
    "sessions.get(sessionUUID).setPlaying(true);\n            sessions.get(sessionUUID).setPaused(false);\n            for (AudioStreamBuffer buffer : sessions.get(sessionUUID).getStreamBuffers().values())"
)

# 3. Fix startPlaybackWithVenueScan calls in AudioEngine that missed the session argument
# In playFromPcmData:
content = content.replace(
    "createSourcesFromClusters(clusters, counts, world, power, inputGain);",
    "createSourcesFromClusters(sessions.get(sessionUUID), clusters, counts, world, power, inputGain);"
)
content = content.replace(
    "startPlaybackWithVenueScan(world, speakers, true);",
    "startPlaybackWithVenueScan(sessions.get(sessionUUID), world, speakers, true);"
)

content = content.replace(
    "createSourcesFromClusters(clusters, counts, world, power, inputGain);\n            startPlaybackWithVenueScan(world, speakers, false);",
    "createSourcesFromClusters(sessions.get(sessionUUID), clusters, counts, world, power, inputGain);\n            startPlaybackWithVenueScan(sessions.get(sessionUUID), world, speakers, false);"
)

content = content.replace(
    "sessions.get(sessionUUID).setPlaying(true);\n            sessions.get(sessionUUID).setPaused(false);\n            for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {",
    "sessions.get(sessionUUID).setPlaying(true);\n            sessions.get(sessionUUID).setPaused(false);\n            for (AudioStreamBuffer buffer : sessions.get(sessionUUID).getStreamBuffers().values()) {"
)


# Fix AdvancedAcousticScanner usages
scanner_path = 'src/main/java/com/audiophilecraft/sound/AdvancedAcousticScanner.java'
with open(scanner_path, 'r', encoding='utf-8') as f:
    s_content = f.read()

s_content = s_content.replace(
    "engine.createSourcesFromClusters(clusters, counts, world, power, inputGain);",
    "engine.createSourcesFromClusters(engine.getActiveSession(), clusters, counts, world, power, inputGain);"
)
s_content = s_content.replace(
    "engine.startPlaybackWithVenueScan(world, speakers, false);",
    "engine.startPlaybackWithVenueScan(engine.getActiveSession(), world, speakers, false);"
)

with open(scanner_path, 'w', encoding='utf-8') as f:
    f.write(s_content)


# Fix PlaybackSession usages
pb_path = 'src/main/java/com/audiophilecraft/sound/PlaybackSession.java'
with open(pb_path, 'r', encoding='utf-8') as f:
    pb_content = f.read()

pb_content = pb_content.replace(
    "engine.createSourcesFromClusters(clusters, counts, world, power, inputGain);",
    "engine.createSourcesFromClusters(this, clusters, counts, world, power, inputGain);"
)
pb_content = pb_content.replace(
    "engine.startPlaybackWithVenueScan(world, speakers, false);",
    "engine.startPlaybackWithVenueScan(this, world, speakers, false);"
)

with open(pb_path, 'w', encoding='utf-8') as f:
    f.write(pb_content)


with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
