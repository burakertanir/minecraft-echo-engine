import sys
import re

def replace_in_file(path, old, new):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    if old in content:
        content = content.replace(old, new)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Replaced in {path}")
    else:
        print(f"Target not found in {path}: {old[:50]}...")

def regex_replace(path, pattern, new):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    if re.search(pattern, content):
        content = re.sub(pattern, new, content)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)
        print(f"Regex replaced in {path}")
    else:
        print(f"Regex pattern not found in {path}")

ae = 'src/main/java/com/audiophilecraft/sound/AudioEngine.java'
mm = 'src/main/java/com/audiophilecraft/network/ModMessages.java'

# 1. Update ModMessages.java
replace_in_file(mm,
'''                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playFromUrl(url, speakers, power, inputGain);''',
'''                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playFromUrl(sessionUUID, url, speakers, power, inputGain);''')

replace_in_file(mm,
'''                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playTrack(trackId, speakers, power, inputGain);''',
'''                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playTrack(sessionUUID, trackId, speakers, power, inputGain);''')

replace_in_file(mm,
'''                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playFromPcmData(trackId, pcmData, sampleRate, speakers, power, inputGain);''',
'''                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();
                engine.playFromPcmData(sessionUUID, trackId, pcmData, sampleRate, speakers, power, inputGain);''')

# 2. Update AudioEngine.java method signatures and stopAll() calls

replace_in_file(ae,
'''    public void playTrack(String trackId, List<BlockPos> speakers, float power, float inputGain) {
        stopAll();''',
'''    public void playTrack(java.util.UUID sessionUUID, String trackId, List<BlockPos> speakers, float power, float inputGain) {
        sessions.computeIfAbsent(sessionUUID, k -> new PlaybackSession(this)).stop();''')

replace_in_file(ae,
'''    public void playFromUrl(String url, List<BlockPos> speakers, float power, float inputGain) {''',
'''    public void playFromUrl(java.util.UUID sessionUUID, String url, List<BlockPos> speakers, float power, float inputGain) {''')

replace_in_file(ae,
'''                stopAll();
                trackGeneration++;''',
'''                sessions.computeIfAbsent(sessionUUID, k -> new PlaybackSession(AudioEngine.this)).stop();
                trackGeneration++;''')

replace_in_file(ae,
'''    public void playFromPcmData(String trackId, short[] pcmData, int sampleRate, List<BlockPos> speakers, float power, float inputGain) {
        stopAll();''',
'''    public void playFromPcmData(java.util.UUID sessionUUID, String trackId, short[] pcmData, int sampleRate, List<BlockPos> speakers, float power, float inputGain) {
        sessions.computeIfAbsent(sessionUUID, k -> new PlaybackSession(this)).stop();''')

# 3. Update createSourcesFromClusters and startPlaybackWithVenueScan to take PlaybackSession
replace_in_file(ae,
'''public void createSourcesFromClusters(List<List<BlockPos>> clusters, int[] counts,
            World world, float power, float inputGain) {''',
'''public void createSourcesFromClusters(PlaybackSession session, List<List<BlockPos>> clusters, int[] counts,
            World world, float power, float inputGain) {''')

regex_replace(ae, r"getActiveSession\(\)\.getStreamBuffers\(\)", "session.getStreamBuffers()")
regex_replace(ae, r"getActiveSession\(\)\.getStreamSources\(\)", "session.getStreamSources()")
regex_replace(ae, r"getActiveSession\(\)\.setPlaying\(", "session.setPlaying(")

replace_in_file(ae,
'''createSourcesFromClusters(clusters, counts, world, power, inputGain);
            startPlaybackWithVenueScan(world, speakers, false);''',
'''createSourcesFromClusters(sessions.get(sessionUUID), clusters, counts, world, power, inputGain);
            startPlaybackWithVenueScan(sessions.get(sessionUUID), world, speakers, false);''')

replace_in_file(ae,
'''createSourcesFromClusters(clusters, counts, world, power, inputGain);
                startPlaybackWithVenueScan(world, speakers, false);''',
'''createSourcesFromClusters(sessions.get(sessionUUID), clusters, counts, world, power, inputGain);
                startPlaybackWithVenueScan(sessions.get(sessionUUID), world, speakers, false);''')

replace_in_file(ae,
'''public void startPlaybackWithVenueScan(World world, List<BlockPos> speakers, boolean atomicStart) {''',
'''public void startPlaybackWithVenueScan(PlaybackSession session, World world, List<BlockPos> speakers, boolean atomicStart) {''')

replace_in_file(ae,
'''getActiveSession().setStreamStartTime(System.nanoTime());''',
'''session.setStreamStartTime(System.nanoTime());''')

replace_in_file(ae,
'''getActiveSession().setPaused(false);''',
'''session.setPaused(false);''')


# 4. Audio Thread loop fix
processAudioBackgroundFix = '''    private synchronized void processAudioBackground() {
        // Respect interrupt: executor shutdown will interrupt us
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            double currentWallTime = 0;
            // Phase 0: Decode OGG on background thread
            for (PlaybackSession session : sessions.values()) {
                if (!session.isPlaying() || session.isPaused() || session.isSeeking()) continue;
                currentWallTime = session.getStreamStartTime() > 0 ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0 : 0.0;
                for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                    if (buffer.sampleRate > 0) {
                        buffer.syncToTime(currentWallTime + BUFFER_LOOKAHEAD);
                    }
                }
            }

            // Phase 1: Smooth listener position
            Vec3d rawPos = this.listenerPos;
            if (rawPos != null) {
                Vec3d prev = this.smoothedListenerPos;
                if (prev == null) prev = rawPos;
                double alpha = 0.35;
                this.smoothedListenerPos = new Vec3d(
                        prev.x + (rawPos.x - prev.x) * alpha,
                        prev.y + (rawPos.y - prev.y) * alpha,
                        prev.z + (rawPos.z - prev.z) * alpha);
            }
            Vec3d currentPos = this.smoothedListenerPos;

            reusableRestartBuffer.clear();

            for (PlaybackSession session : sessions.values()) {
                if (!session.isPlaying() || session.isPaused() || session.isSeeking()) continue;
                double sessionWallTime = session.getStreamStartTime() > 0 ? (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0 : 0.0;
                int sampleRate = 48000;
                for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {
                    if (buffer.sampleRate > 0) { sampleRate = buffer.sampleRate; break; }
                }
                double globalSampleTime = sessionWallTime * sampleRate;

                for (StreamSource source : session.getStreamSources()) {
                    if (source.feedOpenALFromAudioThread(globalSampleTime, currentPos)) {
                        if (reusableRestartBuffer.remaining() > 0) {
                            reusableRestartBuffer.put(source.sourceId);
                        }
                    }
                }
            }

            if (reusableRestartBuffer.position() > 0) {
                int count = reusableRestartBuffer.position();
                reusableRestartBuffer.flip();
                org.lwjgl.openal.AL10.alSourcePlayv(reusableRestartBuffer);
            }
        } catch (Exception e) {
            System.err.println("[AudioEngine] processAudioBackground failed: " + e.getMessage());
            e.printStackTrace();
        }
    }'''

with open(ae, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace processAudioBackground block
start_idx = content.find('    private synchronized void processAudioBackground() {')
end_idx = content.find('    public void cleanupEfx() {')
if start_idx != -1 and end_idx != -1:
    end_idx = content.rfind('}', start_idx, end_idx) + 1
    content = content[:start_idx] + processAudioBackgroundFix + '\n\n' + content[end_idx:].lstrip()
    with open(ae, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Replaced processAudioBackground")
else:
    print("Could not find processAudioBackground block")
