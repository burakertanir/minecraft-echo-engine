var fs = require('fs');
var prj = 'C:/Users/Burak/Desktop/Minecraft Hoparlör';
console.log('Dir:', prj);

// ============ AUDIOENGINE ============
var aePath = prj + '/src/main/java/com/audiophilecraft/sound/AudioEngine.java';
var ae = fs.readFileSync(aePath, 'utf8');

// Helper: find method by name, return [startLine, endLine]
function findMethod(lines, methodName) {
    var start = -1, end = -1;
    for (var i = 0; i < lines.length; i++) {
        if (lines[i].indexOf(methodName) >= 0 && lines[i].match(/public|private/)) {
            start = i;
            var braces = 0;
            for (var j = i; j < lines.length; j++) {
                for (var k = 0; k < lines[j].length; k++) {
                    if (lines[j][k] === '{') braces++;
                    if (lines[j][k] === '}') braces--;
                }
                if (braces === 0 && j > i) { end = j; break; }
            }
            break;
        }
    }
    return [start, end];
}

var lines = ae.split('\n');

// 1. processAudioBackground → multi-session
var [paStart, paEnd] = findMethod(lines, 'processAudioBackground');
if (paStart < 0) { console.log('processAudioBackground NOT FOUND'); process.exit(1); }

var newPA = [
    '    private synchronized void processAudioBackground() {',
    '        if (sessions.isEmpty()) return;',
    '        if (Thread.interrupted()) {',
    '            Thread.currentThread().interrupt();',
    '            return;',
    '        }',
    '        try {',
    '            Vec3d rawPos = this.listenerPos;',
    '            Vec3d prev = this.smoothedListenerPos;',
    '            double alpha = 0.35;',
    '            this.smoothedListenerPos = new Vec3d(',
    '                    prev.x + (rawPos.x - prev.x) * alpha,',
    '                    prev.y + (rawPos.y - prev.y) * alpha,',
    '                    prev.z + (rawPos.z - prev.z) * alpha);',
    '            Vec3d currentPos = this.smoothedListenerPos;',
    '            reusableRestartBuffer.clear();',
    '',
    '            for (java.util.Map.Entry<java.util.UUID, PlaybackSession> entry : sessions.entrySet()) {',
    '                PlaybackSession session = entry.getValue();',
    '                if (session == null || session.isPaused() || session.isSeeking()) continue;',
    '                if (!session.isPlaying() || session.getStreamSources().isEmpty()) continue;',
    '',
    '                long startTime = session.getStreamStartTime();',
    '                if (startTime == 0) continue;',
    '                double currentWallTime = (System.nanoTime() - startTime) / 1_000_000_000.0;',
    '',
    '                int sampleRate = 48000;',
    '                for (AudioStreamBuffer buffer : session.getStreamBuffers().values()) {',
    '                    if (buffer.sampleRate > 0) {',
    '                        buffer.syncToTime(currentWallTime + BUFFER_LOOKAHEAD);',
    '                        sampleRate = buffer.sampleRate;',
    '                    }',
    '                }',
    '',
    '                double globalSampleTime = currentWallTime * sampleRate;',
    '',
    '                for (StreamSource source : session.getStreamSources()) {',
    '                    if (source.feedOpenALFromAudioThread(globalSampleTime, currentPos)) {',
    '                        if (reusableRestartBuffer.remaining() > 0) {',
    '                            reusableRestartBuffer.put(source.sourceId);',
    '                        }',
    '                    }',
    '                }',
    '            }',
    '',
    '            if (reusableRestartBuffer.position() > 0) {',
    '                reusableRestartBuffer.flip();',
    '                org.lwjgl.openal.AL10.alSourcePlayv(reusableRestartBuffer);',
    '            }',
    '        } catch (Exception e) {',
    '            System.err.println("[AudioEngine] processAudioBackground failed: " + e.getMessage());',
    '            e.printStackTrace();',
    '        }',
    '    }'
];
lines.splice(paStart, paEnd - paStart + 1, ...newPA);

// 2. stopAll → session-scoped
var [saStart, saEnd] = findMethod(lines, 'public void stopAll');
if (saStart < 0) { console.log('stopAll NOT FOUND'); process.exit(1); }

var newStop = [
    '    public void stopAll() {',
    '        if (getActiveSession() == null) return;',
    '',
    '        for (StreamSource sound : getActiveSession().getStreamSources()) {',
    '            sound.cleanup();',
    '        }',
    '        getActiveSession().getStreamSources().clear();',
    '        getActiveSession().setPaused(false);',
    '        getActiveSession().setPlaying(false);',
    '        getActiveSession().setStreamStartTime(0);',
    '',
    '        this.venuePreset = null;',
    '        this.venuePresetApplied = false;',
    '        this.storedVenueDescriptor = null;',
    '        this.storedVenueProbePos = null;',
    '',
    '        boolean anyPlaying = false;',
    '        for (PlaybackSession s : sessions.values()) {',
    '            if (s.isPlaying()) { anyPlaying = true; break; }',
    '        }',
    '        if (!anyPlaying && audioThread != null) {',
    '            audioThread.shutdownNow();',
    '            try { audioThread.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS); }',
    '            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }',
    '            audioThread = null;',
    '        }',
    '    }'
];
lines.splice(saStart, saEnd - saStart + 1, ...newStop);

ae = lines.join('\n');

// Re-split for remaining replacements
lines = ae.split('\n');

// 3. updateSourcesTick → multi-session
var [utStart, utEnd] = findMethod(lines, 'public void updateSourcesTick');
if (utStart < 0) { console.log('updateSourcesTick NOT FOUND'); process.exit(1); }

var newTick = [
    '    public void updateSourcesTick(World world) {',
    '        MinecraftClient mc = MinecraftClient.getInstance();',
    '        boolean gamePaused = mc.isPaused();',
    '',
    '        for (java.util.Map.Entry<java.util.UUID, PlaybackSession> entry : sessions.entrySet()) {',
    '            PlaybackSession session = entry.getValue();',
    '            if (session == null || !session.isPlaying()) continue;',
    '',
    '            if (gamePaused != session.isPaused()) {',
    '                session.setPaused(gamePaused);',
    '                if (session.isPaused()) {',
    '                    session.setPauseStartTimestamp(System.nanoTime());',
    '                    for (StreamSource sound : session.getStreamSources()) sound.pause();',
    '                } else {',
    '                    if (session.getPauseStartTimestamp() > 0 && session.getStreamStartTime() > 0) {',
    '                        long pauseDuration = System.nanoTime() - session.getPauseStartTimestamp();',
    '                        session.setStreamStartTime(session.getStreamStartTime() + pauseDuration);',
    '                    }',
    '                    for (StreamSource sound : session.getStreamSources()) sound.resume();',
    '                }',
    '            }',
    '',
    '            if (session.isPaused()) continue;',
    '',
    '            double timeSinceStart = 0;',
    '            if (session.getStreamStartTime() != 0) {',
    '                timeSinceStart = (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0;',
    '            }',
    '',
    '            for (StreamSource source : session.getStreamSources()) {',
    '                if (!source.update(world, this.listenerPos, timeSinceStart)) {',
    '                    source.cleanup();',
    '                    session.getStreamSources().remove(source);',
    '                }',
    '            }',
    '',
    '            if (session.getStreamSources().isEmpty() && this.venuePreset != null) {',
    '                this.venuePreset = null;',
    '                this.venuePresetApplied = false;',
    '            }',
    '        }',
    '',
    '        float maxOcclusion = 0.0f;',
    '        boolean anySource = false;',
    '        for (PlaybackSession s : sessions.values()) {',
    '            if (s == null) continue;',
    '            for (StreamSource source : s.getStreamSources()) {',
    '                anySource = true;',
    '                if (source.currentOcclusion > maxOcclusion) maxOcclusion = source.currentOcclusion;',
    '            }',
    '        }',
    '        if (!anySource) maxOcclusion = 1.0f;',
    '        updateMasterReverbOcclusion(maxOcclusion);',
    '',
    '        boolean anyPlaying = false;',
    '        for (PlaybackSession s : sessions.values()) {',
    '            if (s != null && s.isPlaying()) { anyPlaying = true; break; }',
    '        }',
    '        if (anyPlaying) updateListenerReflections(world);',
    '',
    '        ensureVenueReverb();',
    '        lastTickTime = System.nanoTime();',
    '    }'
];
lines.splice(utStart, utEnd - utStart + 1, ...newTick);

// 4. pauseAll / resumeAll → multi-session
var [pStart, pEnd] = findMethod(lines, 'public void pauseAll');
var [rStart, rEnd] = findMethod(lines, 'public void resumeAll');
if (pStart < 0 || rStart < 0) { console.log('pauseAll/resumeAll NOT FOUND'); process.exit(1); }

var newPause = [
    '    public void pauseAll() {',
    '        if (auxSlotId != 0) alAuxiliaryEffectSlotf(auxSlotId, AL_EFFECTSLOT_GAIN, 0.0f);',
    '        for (PlaybackSession session : sessions.values()) {',
    '            if (session == null) continue;',
    '            for (StreamSource sound : session.getStreamSources()) sound.pause();',
    '        }',
    '    }'
];
var newResume = [
    '    public void resumeAll() {',
    '        if (auxSlotId != 0) alAuxiliaryEffectSlotf(auxSlotId, AL_EFFECTSLOT_GAIN, 1.0f);',
    '        for (PlaybackSession session : sessions.values()) {',
    '            if (session == null) continue;',
    '            for (StreamSource sound : session.getStreamSources()) sound.resume();',
    '        }',
    '    }'
];
// Replace resumeAll first (it's later)
lines.splice(rStart, rEnd - rStart + 1, ...newResume);
// Recompute pauseAll index (might have shifted slightly)
[pStart, pEnd] = findMethod(lines, 'public void pauseAll');
lines.splice(pStart, pEnd - pStart + 1, ...newPause);

ae = lines.join('\n');

// 5. Move setPlaying(true) into startPlayback (string replace, more robust)
ae = ae.replace(
    '            prepareStreamBuffers(trackId);\r\n            getActiveSession().setPlaying(true);\r\n            getActiveSession().setPaused(false);\r\n            for (AudioStreamBuffer buffer : getActiveSession().getStreamBuffers().values()) {\r\n                if (buffer.sampleRate > 0)\r\n                    buffer.syncToTime(BUFFER_LOOKAHEAD);',
    '            prepareStreamBuffers(trackId);\r\n            for (AudioStreamBuffer buffer : getActiveSession().getStreamBuffers().values()) {\r\n                if (buffer.sampleRate > 0)\r\n                    buffer.syncToTime(BUFFER_LOOKAHEAD);'
);
ae = ae.replace(
    '            getActiveSession().setStreamStartTime(System.nanoTime());',
    '            getActiveSession().setPlaying(true);\r\n            getActiveSession().setPaused(false);\r\n            getActiveSession().setStreamStartTime(System.nanoTime());'
);

// 6. Add per-session power/gain helpers
ae = ae.replace(
    '    public void updatePower(float power) {\r\n        if (getActiveSession() == null) return;\r\n        for (StreamSource ss : getActiveSession().getStreamSources()) {\r\n            ss.power = power;\r\n        }\r\n    }',
    '    public void updatePower(float power) {\r\n        if (getActiveSession() == null) return;\r\n        for (StreamSource ss : getActiveSession().getStreamSources()) {\r\n            ss.power = power;\r\n        }\r\n    }\r\n\r\n    public void updatePowerForSession(java.util.UUID sessionId, float power) {\r\n        PlaybackSession session = sessions.get(sessionId);\r\n        if (session == null) return;\r\n        for (StreamSource ss : session.getStreamSources()) {\r\n            ss.power = power;\r\n        }\r\n    }\r\n\r\n    public void updateInputGainForSession(java.util.UUID sessionId, float gain) {\r\n        PlaybackSession session = sessions.get(sessionId);\r\n        if (session == null) return;\r\n        for (StreamSource ss : session.getStreamSources()) {\r\n            ss.inputGain = gain;\r\n        }\r\n    }'
);

fs.writeFileSync(aePath, ae, 'utf8');
console.log('AudioEngine: DONE -', ae.length, 'bytes');
console.log('  sessions.isEmpty:', ae.indexOf('sessions.isEmpty()') >= 0);
console.log('  anyPlaying:', ae.indexOf('anyPlaying') >= 0);
console.log('  updatePowerForSession:', ae.indexOf('updatePowerForSession') >= 0);

// ============ MODMESSAGES ============
var mmPath = prj + '/src/main/java/com/audiophilecraft/network/ModMessages.java';
var mm = fs.readFileSync(mmPath, 'utf8');

// 1. Add EQ packet identifiers
mm = mm.replace(
    '    public static final Identifier S2C_SEEK_TRACK',
    '    public static final Identifier C2S_UPDATE_EQ = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_eq");\r\n    public static final Identifier S2C_SYNC_EQ = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_eq");\r\n    public static final Identifier S2C_SEEK_TRACK'
);

// 2. C2S play handlers: findByOwner
mm = mm.replace(
    '                            List<BlockPos> speakers = SpeakerRegistry.findSpeakersInRange(\r\n                                    player.getBlockPos(), AmplifierTabletItem.SCAN_RADIUS);',
    '                            UUID ownerUUID = player.getUuid();\r\n                            List<BlockPos> speakers = SpeakerRegistry.findSpeakersByOwner(ownerUUID);'
);

// 3. sendPlayTrack/sendPlayUrl: add ownerUUID param
mm = mm.replace(
    '                                sendPlayTrack(nearby, testTrackId, speakers, power, inputGain);',
    '                                sendPlayTrack(nearby, ownerUUID, testTrackId, speakers, power, inputGain);'
);
mm = mm.replace(
    '                                sendPlayUrl(nearby, url, speakers, power, inputGain);',
    '                                sendPlayUrl(nearby, ownerUUID, url, speakers, power, inputGain);'
);

// 4. C2S power/gain: add ownerUUID to syncBuf
mm = mm.replace(
    '                            PacketByteBuf syncBuf = PacketByteBufs.create();\r\n                            syncBuf.writeInt(handOrdinal);',
    '                            PacketByteBuf syncBuf = PacketByteBufs.create();\r\n                            syncBuf.writeUuid(player.getUuid());\r\n                            syncBuf.writeInt(handOrdinal);'
);
// This handles both power and gain (2 occurrences)

// 5. C2S seek: add ownerUUID
mm = mm.replace(
    '                    float targetTime = buf.readFloat();\r\n                    server.execute(() -> {\r\n                        ItemStack mainStack',
    '                    float targetTime = buf.readFloat();\r\n                    server.execute(() -> {\r\n                        UUID ownerUUID = player.getUuid();\r\n                        ItemStack mainStack'
);

// 6. Add C2S_EQ handler
mm = mm.replace(
    '        // Speaker shift (speaker block entity',
    '        // EQ update \u2014 synced per-session\r\n' +
    '        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ,\r\n' +
    '                (server, player, handler, buf, responseSender) -> {\r\n' +
    '                    String speakerType = buf.readString();\r\n' +
    '                    int band = buf.readInt();\r\n' +
    '                    float db = buf.readFloat();\r\n' +
    '                    server.execute(() -> {\r\n' +
    '                        UUID ownerUUID = player.getUuid();\r\n' +
    '                        PacketByteBuf syncBuf = PacketByteBufs.create();\r\n' +
    '                        syncBuf.writeUuid(ownerUUID);\r\n' +
    '                        syncBuf.writeString(speakerType);\r\n' +
    '                        syncBuf.writeInt(band);\r\n' +
    '                        syncBuf.writeFloat(db);\r\n' +
    '                        for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {\r\n' +
    '                            ServerPlayNetworking.send(nearby, S2C_SYNC_EQ, syncBuf);\r\n' +
    '                        }\r\n' +
    '                    });\r\n' +
    '                });\r\n\r\n' +
    '        // Speaker shift (speaker block entity'
);

// 7. S2C PLAY_TRACK: read sessionUUID
mm = mm.replace(
    'ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {\r\n            String trackId',
    'ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {\r\n            UUID sessionUUID = buf.readUuid();\r\n            String trackId'
);
mm = mm.replace(
    'com.audiophilecraft.sound.AudioEngine.getInstance().playTrack(trackId,',
    'com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\r\n                engine.ensureActiveSession(sessionUUID);\r\n                engine.playTrack(trackId,'
);

// 8. S2C PLAY_URL: read sessionUUID
mm = mm.replace(
    'ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {\r\n            String url',
    'ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {\r\n            UUID sessionUUID = buf.readUuid();\r\n            String url'
);
mm = mm.replace(
    'com.audiophilecraft.sound.AudioEngine.getInstance().playFromUrl(url,',
    'com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\r\n                engine.ensureActiveSession(sessionUUID);\r\n                engine.playFromUrl(url,'
);

// 9. S2C SYNC_POWER: sessionUUID + per-session
mm = mm.replace(
    'ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {\r\n            int handOrdinal',
    'ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {\r\n            UUID sessionUUID = buf.readUuid();\r\n            int handOrdinal'
);
mm = mm.replace(
    'com.audiophilecraft.sound.AudioEngine.getInstance().updatePower(power);',
    'com.audiophilecraft.sound.AudioEngine.getInstance().updatePowerForSession(sessionUUID, power);'
);

// 10. S2C SYNC_INPUT_GAIN: sessionUUID + per-session
mm = mm.replace(
    'ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {\r\n            int handOrdinal',
    'ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {\r\n            UUID sessionUUID = buf.readUuid();\r\n            int handOrdinal'
);
mm = mm.replace(
    'com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGain(gain);',
    'com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGainForSession(sessionUUID, gain);'
);

// 11. S2C SEEK: sessionUUID
mm = mm.replace(
    'ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {\r\n            float targetTime',
    'ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {\r\n            UUID sessionUUID = buf.readUuid();\r\n            float targetTime'
);
mm = mm.replace(
    'com.audiophilecraft.sound.AudioEngine.getInstance().seek(targetTime);',
    'com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\r\n                engine.ensureActiveSession(sessionUUID);\r\n                engine.seek(targetTime);'
);

// 12. Add S2C_SYNC_EQ receiver
mm = mm.replace(
    '    public static void sendPlayTrack(net.minecraft.server.network.ServerPlayerEntity player, String trackId',
    '        // EQ Sync \u2014 scoped to session UUID\r\n' +
    '        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ, (client, handler, buf, responseSender) -> {\r\n' +
    '            UUID sessionUUID = buf.readUuid();\r\n' +
    '            String speakerType = buf.readString();\r\n' +
    '            int band = buf.readInt();\r\n' +
    '            float db = buf.readFloat();\r\n' +
    '            client.execute(() -> {\r\n' +
    '                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\r\n' +
    '                engine.ensureActiveSession(sessionUUID);\r\n' +
    '                engine.setEqDb(speakerType, band, db);\r\n' +
    '            });\r\n' +
    '        });\r\n\r\n' +
    '    public static void sendPlayTrack(net.minecraft.server.network.ServerPlayerEntity player, String trackId'
);

// 13. Update sendPlayTrack/sendPlayUrl signatures
mm = mm.replace(
    '    public static void sendPlayTrack(net.minecraft.server.network.ServerPlayerEntity player, String trackId,',
    '    public static void sendPlayTrack(net.minecraft.server.network.ServerPlayerEntity player, UUID ownerUUID, String trackId,'
);
mm = mm.replace(
    '        buf.writeString(trackId);\r\n        buf.writeFloat(power);',
    '        buf.writeUuid(ownerUUID);\r\n        buf.writeString(trackId);\r\n        buf.writeFloat(power);'
);
mm = mm.replace(
    '    public static void sendPlayUrl(net.minecraft.server.network.ServerPlayerEntity player, String url,',
    '    public static void sendPlayUrl(net.minecraft.server.network.ServerPlayerEntity player, UUID ownerUUID, String url,'
);
mm = mm.replace(
    '        buf.writeString(url);\r\n        buf.writeFloat(power);\r\n        buf.writeFloat(inputGain);',
    '        buf.writeUuid(ownerUUID);\r\n        buf.writeString(url);\r\n        buf.writeFloat(power);\r\n        buf.writeFloat(inputGain);'
);

fs.writeFileSync(mmPath, mm, 'utf8');
console.log('ModMessages: DONE -', mm.length, 'bytes');
console.log('  C2S_UPDATE_EQ:', mm.indexOf('C2S_UPDATE_EQ') >= 0);
console.log('  S2C_SYNC_EQ:', mm.indexOf('S2C_SYNC_EQ') >= 0);
console.log('  ownerUUID sendPlayTrack:', mm.indexOf('(ServerPlayerEntity player, UUID ownerUUID, String trackId') >= 0);
