var fs = require('fs');
var prj = 'C:/Users/Burak/Desktop/Minecraft Hoparlör';

console.log('=== 1) AudioEngine.java ===');
var aePath = prj + '/src/main/java/com/audiophilecraft/sound/AudioEngine.java';
var ae = fs.readFileSync(aePath, 'utf8');
var lines = ae.split('\n');

// --- A) processAudioBackground: multi-session ---
var procStart = -1;
for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('private synchronized void processAudioBackground') >= 0) { procStart = i; break; }
}
var procEnd = -1;
for (var i = procStart; i < lines.length; i++) {
    if (lines[i].match(/^\s+\/\*\*$/) && i > procStart + 30) { procEnd = i; break; }
}
if (procStart < 0 || procEnd < 0) { console.log('processAudioBackground not found'); process.exit(1); }

var newProc = [
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

lines.splice(procStart, procEnd - procStart, ...newProc);

// --- B) stopAll: session-scoped ---
var saStart = -1, saEnd = -1;
for (var i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('public void stopAll()') >= 0) { saStart = i; }
    if (saStart > 0 && lines[i].match(/^\s+\}$/) && i > saStart + 5) { saEnd = i + 1; break; }
}
if (saStart < 0 || saEnd < 0) { console.log('stopAll not found'); process.exit(1); }

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

lines.splice(saStart, saEnd - saStart, ...newStop);
ae = lines.join('\n');

// --- C) updateSourcesTick: multi-session ---
var utIdx = ae.indexOf('public void updateSourcesTick(World world)');
var paIdx = ae.indexOf('public void pauseAll()');
if (utIdx < 0 || paIdx < 0) { console.log('updateSourcesTick/pauseAll not found'); process.exit(1); }

var newTick = 
'    public void updateSourcesTick(World world) {\n' +
'        MinecraftClient mc = MinecraftClient.getInstance();\n' +
'        boolean gamePaused = mc.isPaused();\n' +
'\n' +
'        for (java.util.Map.Entry<java.util.UUID, PlaybackSession> entry : sessions.entrySet()) {\n' +
'            PlaybackSession session = entry.getValue();\n' +
'            if (session == null || !session.isPlaying()) continue;\n' +
'\n' +
'            if (gamePaused != session.isPaused()) {\n' +
'                session.setPaused(gamePaused);\n' +
'                if (session.isPaused()) {\n' +
'                    session.setPauseStartTimestamp(System.nanoTime());\n' +
'                    for (StreamSource sound : session.getStreamSources()) sound.pause();\n' +
'                } else {\n' +
'                    if (session.getPauseStartTimestamp() > 0 && session.getStreamStartTime() > 0) {\n' +
'                        long pauseDuration = System.nanoTime() - session.getPauseStartTimestamp();\n' +
'                        session.setStreamStartTime(session.getStreamStartTime() + pauseDuration);\n' +
'                    }\n' +
'                    for (StreamSource sound : session.getStreamSources()) sound.resume();\n' +
'                }\n' +
'            }\n' +
'\n' +
'            if (session.isPaused()) continue;\n' +
'\n' +
'            double timeSinceStart = 0;\n' +
'            if (session.getStreamStartTime() != 0) {\n' +
'                timeSinceStart = (System.nanoTime() - session.getStreamStartTime()) / 1_000_000_000.0;\n' +
'            }\n' +
'\n' +
'            for (StreamSource source : session.getStreamSources()) {\n' +
'                if (!source.update(world, this.listenerPos, timeSinceStart)) {\n' +
'                    source.cleanup();\n' +
'                    session.getStreamSources().remove(source);\n' +
'                }\n' +
'            }\n' +
'\n' +
'            if (session.getStreamSources().isEmpty() && this.venuePreset != null) {\n' +
'                this.venuePreset = null;\n' +
'                this.venuePresetApplied = false;\n' +
'            }\n' +
'        }\n' +
'\n' +
'        float maxOcclusion = 0.0f;\n' +
'        boolean anySource = false;\n' +
'        for (PlaybackSession s : sessions.values()) {\n' +
'            if (s == null) continue;\n' +
'            for (StreamSource source : s.getStreamSources()) {\n' +
'                anySource = true;\n' +
'                if (source.currentOcclusion > maxOcclusion) maxOcclusion = source.currentOcclusion;\n' +
'            }\n' +
'        }\n' +
'        if (!anySource) maxOcclusion = 1.0f;\n' +
'        updateMasterReverbOcclusion(maxOcclusion);\n' +
'\n' +
'        boolean anyPlaying = false;\n' +
'        for (PlaybackSession s : sessions.values()) {\n' +
'            if (s != null && s.isPlaying()) { anyPlaying = true; break; }\n' +
'        }\n' +
'        if (anyPlaying) updateListenerReflections(world);\n' +
'\n' +
'        ensureVenueReverb();\n' +
'        lastTickTime = System.nanoTime();\n' +
'    }';

ae = ae.substring(0, utIdx) + newTick + ae.substring(paIdx);

// --- D) pauseAll / resumeAll: multi-session ---
var paStart = ae.indexOf('public void pauseAll()');
var saStart2 = ae.indexOf('public void stopAll()', paStart);
if (paStart < 0 || saStart2 < 0) { console.log('pauseAll/stopAll not found 2'); process.exit(1); }

var newPause = 
'    public void pauseAll() {\n' +
'        if (auxSlotId != 0) alAuxiliaryEffectSlotf(auxSlotId, AL_EFFECTSLOT_GAIN, 0.0f);\n' +
'        for (PlaybackSession session : sessions.values()) {\n' +
'            if (session == null) continue;\n' +
'            for (StreamSource sound : session.getStreamSources()) sound.pause();\n' +
'        }\n' +
'    }\n' +
'\n' +
'    public void resumeAll() {\n' +
'        if (auxSlotId != 0) alAuxiliaryEffectSlotf(auxSlotId, AL_EFFECTSLOT_GAIN, 1.0f);\n' +
'        for (PlaybackSession session : sessions.values()) {\n' +
'            if (session == null) continue;\n' +
'            for (StreamSource sound : session.getStreamSources()) sound.resume();\n' +
'        }\n' +
'    }';

ae = ae.substring(0, paStart) + newPause + ae.substring(saStart2);

// --- E) Move setPlaying(true) into startPlayback ---
ae = ae.replace(
    '            prepareStreamBuffers(trackId);\n            getActiveSession().setPlaying(true);\n            getActiveSession().setPaused(false);\n            for (AudioStreamBuffer buffer : getActiveSession().getStreamBuffers().values()) {\n                if (buffer.sampleRate > 0)\n                    buffer.syncToTime(BUFFER_LOOKAHEAD);',
    '            prepareStreamBuffers(trackId);\n            for (AudioStreamBuffer buffer : getActiveSession().getStreamBuffers().values()) {\n                if (buffer.sampleRate > 0)\n                    buffer.syncToTime(BUFFER_LOOKAHEAD);'
);

ae = ae.replace(
    '            getActiveSession().setStreamStartTime(System.nanoTime());',
    '            getActiveSession().setPlaying(true);\n            getActiveSession().setPaused(false);\n            getActiveSession().setStreamStartTime(System.nanoTime());'
);

// --- F) Add per-session power/gain helpers ---
ae = ae.replace(
    '    public void updatePower(float power) {\n        if (getActiveSession() == null) return;\n        for (StreamSource ss : getActiveSession().getStreamSources()) {\n            ss.power = power;\n        }\n    }',
    '    public void updatePower(float power) {\n        if (getActiveSession() == null) return;\n        for (StreamSource ss : getActiveSession().getStreamSources()) {\n            ss.power = power;\n        }\n    }\n\n    public void updatePowerForSession(java.util.UUID sessionId, float power) {\n        PlaybackSession session = sessions.get(sessionId);\n        if (session == null) return;\n        for (StreamSource ss : session.getStreamSources()) {\n            ss.power = power;\n        }\n    }\n\n    public void updateInputGainForSession(java.util.UUID sessionId, float gain) {\n        PlaybackSession session = sessions.get(sessionId);\n        if (session == null) return;\n        for (StreamSource ss : session.getStreamSources()) {\n            ss.inputGain = gain;\n        }\n    }'
);

fs.writeFileSync(aePath, ae, 'utf8');
console.log('AudioEngine done');

// === 2) ModMessages.java ===
console.log('=== 2) ModMessages.java ===');
var mmPath = prj + '/src/main/java/com/audiophilecraft/network/ModMessages.java';
var mm = fs.readFileSync(mmPath, 'utf8');
var mlines = mm.split('\n');

// --- A) Add EQ packet identifiers ---
for (var i = 0; i < mlines.length; i++) {
    if (mlines[i].indexOf('S2C_SEEK_TRACK') >= 0 && mlines[i].indexOf('Identifier') >= 0) {
        mlines.splice(i + 1, 0,
            '    public static final Identifier C2S_UPDATE_EQ = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_eq");',
            '    public static final Identifier S2C_SYNC_EQ = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_eq");'
        );
        break;
    }
}
mm = mlines.join('\n');

// --- B) C2S play handlers: use findByOwner ---
mm = mm.replace(
    '                            List<BlockPos> speakers = SpeakerRegistry.findSpeakersInRange(\n                                    player.getBlockPos(), AmplifierTabletItem.SCAN_RADIUS);',
    '                            UUID ownerUUID = player.getUuid();\n                            List<BlockPos> speakers = SpeakerRegistry.findSpeakersByOwner(ownerUUID);'
);
// There are 2 occurrences (PLAY and URL), the first replace handles both with the same pattern

// --- C) Fix sendPlayTrack / sendPlayUrl calls to include ownerUUID ---
mm = mm.replace(
    '                                sendPlayTrack(nearby, testTrackId, speakers, power, inputGain);',
    '                                sendPlayTrack(nearby, ownerUUID, testTrackId, speakers, power, inputGain);'
);
mm = mm.replace(
    '                                sendPlayUrl(nearby, url, speakers, power, inputGain);',
    '                                sendPlayUrl(nearby, ownerUUID, url, speakers, power, inputGain);'
);

// --- D) C2S_UPDATE_POWER: add ownerUUID ---
mm = mm.replace(
    '                            syncBuf.writeInt(handOrdinal);',
    '                            syncBuf.writeUuid(player.getUuid());\n                            syncBuf.writeInt(handOrdinal);'
);

// --- E) C2S_SEEK: add ownerUUID (already partially done) ---
mm = mm.replace(
    '                    float targetTime = buf.readFloat();\n                    server.execute(() -> {\n                        ItemStack mainStack',
    '                    float targetTime = buf.readFloat();\n                    server.execute(() -> {\n                        UUID ownerUUID = player.getUuid();\n                        ItemStack mainStack'
);

// --- F) S2C_PLAY_TRACK reader: add sessionUUID read + ensureActiveSession ---
mm = mm.replace(
    "ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {\n            String trackId = buf.readString();",
    "ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_TRACK, (client, handler, buf, responseSender) -> {\n            UUID sessionUUID = buf.readUuid();\n            String trackId = buf.readString();"
);
mm = mm.replace(
    '                com.audiophilecraft.sound.AudioEngine.getInstance().playTrack(trackId, speakers, power, inputGain);',
    '                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\n                engine.ensureActiveSession(sessionUUID);\n                engine.playTrack(trackId, speakers, power, inputGain);'
);

// --- G) S2C_PLAY_URL reader: add sessionUUID ---
mm = mm.replace(
    "ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {\n            String url = buf.readString(2048);",
    "ClientPlayNetworking.registerGlobalReceiver(S2C_PLAY_URL, (client, handler, buf, responseSender) -> {\n            UUID sessionUUID = buf.readUuid();\n            String url = buf.readString(2048);"
);
mm = mm.replace(
    '                com.audiophilecraft.sound.AudioEngine.getInstance().playFromUrl(url, speakers, power, inputGain);',
    '                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\n                engine.ensureActiveSession(sessionUUID);\n                engine.playFromUrl(url, speakers, power, inputGain);'
);

// --- H) S2C_SYNC_POWER: add sessionUUID ---
mm = mm.replace(
    "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {\n            int handOrdinal = buf.readInt();",
    "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {\n            UUID sessionUUID = buf.readUuid();\n            int handOrdinal = buf.readInt();"
);
mm = mm.replace(
    '                com.audiophilecraft.sound.AudioEngine.getInstance().updatePower(power);',
    '                com.audiophilecraft.sound.AudioEngine.getInstance().updatePowerForSession(sessionUUID, power);'
);

// --- I) S2C_SYNC_INPUT_GAIN: add sessionUUID ---
mm = mm.replace(
    "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {\n            int handOrdinal = buf.readInt();",
    "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {\n            UUID sessionUUID = buf.readUuid();\n            int handOrdinal = buf.readInt();"
);
mm = mm.replace(
    '                com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGain(gain);',
    '                com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGainForSession(sessionUUID, gain);'
);

// --- J) S2C_SEEK: add sessionUUID read + ensureActiveSession ---
mm = mm.replace(
    "ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {\n            float targetTime = buf.readFloat();",
    "ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {\n            UUID sessionUUID = buf.readUuid();\n            float targetTime = buf.readFloat();"
);
mm = mm.replace(
    '                com.audiophilecraft.sound.AudioEngine.getInstance().seek(targetTime);',
    '                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\n                engine.ensureActiveSession(sessionUUID);\n                engine.seek(targetTime);'
);

// --- K) Add C2S_UPDATE_EQ handler ---
var shiftSection = "        // Speaker shift (speaker block entity";
mm = mm.replace(shiftSection,
    '        // EQ update — synced per-session\n' +
    '        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ,\n' +
    '                (server, player, handler, buf, responseSender) -> {\n' +
    '                    String speakerType = buf.readString();\n' +
    '                    int band = buf.readInt();\n' +
    '                    float db = buf.readFloat();\n' +
    '                    server.execute(() -> {\n' +
    '                        UUID ownerUUID = player.getUuid();\n' +
    '                        PacketByteBuf syncBuf = PacketByteBufs.create();\n' +
    '                        syncBuf.writeUuid(ownerUUID);\n' +
    '                        syncBuf.writeString(speakerType);\n' +
    '                        syncBuf.writeInt(band);\n' +
    '                        syncBuf.writeFloat(db);\n' +
    '                        for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {\n' +
    '                            ServerPlayNetworking.send(nearby, S2C_SYNC_EQ, syncBuf);\n' +
    '                        }\n' +
    '                    });\n' +
    '                });\n\n' +
    '        ' + shiftSection
);

// --- L) Add S2C_SYNC_EQ receiver ---
var endOfS2C = "    public static void sendPlayTrack";
mm = mm.replace(endOfS2C,
    '        // EQ Sync \u2014 scoped to session UUID\n' +
    '        ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_EQ, (client, handler, buf, responseSender) -> {\n' +
    '            UUID sessionUUID = buf.readUuid();\n' +
    '            String speakerType = buf.readString();\n' +
    '            int band = buf.readInt();\n' +
    '            float db = buf.readFloat();\n' +
    '            client.execute(() -> {\n' +
    '                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\n' +
    '                engine.ensureActiveSession(sessionUUID);\n' +
    '                engine.setEqDb(speakerType, band, db);\n' +
    '            });\n' +
    '        });\n\n' +
    '    ' + endOfS2C
);

// --- M) Update sendPlayTrack/sendPlayUrl signatures ---
mm = mm.replace(
    '    public static void sendPlayTrack(net.minecraft.server.network.ServerPlayerEntity player, String trackId,',
    '    public static void sendPlayTrack(net.minecraft.server.network.ServerPlayerEntity player, UUID ownerUUID, String trackId,'
);
mm = mm.replace(
    '        buf.writeString(trackId);',
    '        buf.writeUuid(ownerUUID);\n        buf.writeString(trackId);'
);
mm = mm.replace(
    '    public static void sendPlayUrl(net.minecraft.server.network.ServerPlayerEntity player, String url,',
    '    public static void sendPlayUrl(net.minecraft.server.network.ServerPlayerEntity player, UUID ownerUUID, String url,'
);
mm = mm.replace(
    '        buf.writeString(url);\n        buf.writeFloat(power);',
    '        buf.writeUuid(ownerUUID);\n        buf.writeString(url);\n        buf.writeFloat(power);'
);

fs.writeFileSync(mmPath, mm, 'utf8');
console.log('ModMessages done');
console.log('--- All patches applied ---');
