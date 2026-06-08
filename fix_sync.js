var fs = require('fs');
var prj = 'C:/Users/Burak/Desktop/Minecraft Hoparlör';

// === 1) Fix ModMessages.java: add sessionUUID to power/gain, add EQ packets ===
var mmPath = prj + '/src/main/java/com/audiophilecraft/network/ModMessages.java';
var mm = fs.readFileSync(mmPath, 'utf8');

// a) Add EQ packet identifiers after S2C_SEEK_TRACK
var seekIdLine = '    public static final Identifier S2C_SEEK_TRACK = new Identifier(AudiophileCraft.MOD_ID, "s2c_seek_track");';
mm = mm.replace(seekIdLine, seekIdLine + '\n' +
    '    public static final Identifier C2S_UPDATE_EQ = new Identifier(AudiophileCraft.MOD_ID, "c2s_update_eq");\n' +
    '    public static final Identifier S2C_SYNC_EQ = new Identifier(AudiophileCraft.MOD_ID, "s2c_sync_eq");');

// b) Fix C2S_UPDATE_POWER: add ownerUUID to syncBuf
var powerBufWrite = '                            syncBuf.writeInt(handOrdinal);\n                            syncBuf.writeFloat(power);';
mm = mm.replace(powerBufWrite, '                            syncBuf.writeUuid(player.getUuid());\n' +
    '                            syncBuf.writeInt(handOrdinal);\n' +
    '                            syncBuf.writeFloat(power);');

// c) Fix C2S_UPDATE_INPUT_GAIN: add ownerUUID to syncBuf
var gainBufWrite = '                            syncBuf.writeInt(handOrdinal);\n                            syncBuf.writeFloat(gain);';
// There are two occurrences - only change the one in C2S_UPDATE_INPUT_GAIN
var gainHandlerIdx = mm.indexOf('C2S_UPDATE_INPUT_GAIN');
var gainBufIdx = mm.indexOf(gainBufWrite, gainHandlerIdx);
if (gainBufIdx >= 0) {
    mm = mm.substring(0, gainBufIdx) +
        '                            syncBuf.writeUuid(player.getUuid());\n' +
        '                            syncBuf.writeInt(handOrdinal);\n' +
        '                            syncBuf.writeFloat(gain);' +
        mm.substring(gainBufIdx + gainBufWrite.length);
}

// d) Add C2S_UPDATE_EQ handler before the speaker shift section
var shiftSection = "        // Speaker shift (speaker block entity \u2014 unchanged)\n" +
    "        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_SPEAKER_SHIFT,";
var eqHandler = "        // EQ update — synced per-session\n" +
    "        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_EQ,\n" +
    "                (server, player, handler, buf, responseSender) -> {\n" +
    "                    String speakerType = buf.readString();\n" +
    "                    int band = buf.readInt();\n" +
    "                    float db = buf.readFloat();\n" +
    "                    server.execute(() -> {\n" +
    "                        UUID ownerUUID = player.getUuid();\n" +
    "                        PacketByteBuf syncBuf = PacketByteBufs.create();\n" +
    "                        syncBuf.writeUuid(ownerUUID);\n" +
    "                        syncBuf.writeString(speakerType);\n" +
    "                        syncBuf.writeInt(band);\n" +
    "                        syncBuf.writeFloat(db);\n" +
    "                        for (net.minecraft.server.network.ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {\n" +
    "                            ServerPlayNetworking.send(nearby, S2C_SYNC_EQ, syncBuf);\n" +
    "                        }\n" +
    "                    });\n" +
    "                });\n\n" +
    "        ";

mm = mm.replace(shiftSection, eqHandler + shiftSection);

// e) Fix S2C_SYNC_POWER receiver: add sessionUUID read + target correct session
var powerReceiver = "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {\n" +
    "            int handOrdinal = buf.readInt();\n" +
    "            float power = buf.readFloat();\n" +
    "            client.execute(() -> {\n" +
    "                if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {\n" +
    "                    screen.updateSpeakerPower(power);\n" +
    "                }\n" +
    "                com.audiophilecraft.sound.AudioEngine.getInstance().updatePower(power);\n" +
    "            });\n" +
    "        });";

var newPowerReceiver = "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_POWER, (client, handler, buf, responseSender) -> {\n" +
    "            UUID sessionUUID = buf.readUuid();\n" +
    "            int handOrdinal = buf.readInt();\n" +
    "            float power = buf.readFloat();\n" +
    "            client.execute(() -> {\n" +
    "                if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {\n" +
    "                    screen.updateSpeakerPower(power);\n" +
    "                }\n" +
    "                com.audiophilecraft.sound.AudioEngine.getInstance().updatePowerForSession(sessionUUID, power);\n" +
    "            });\n" +
    "        });";

mm = mm.replace(powerReceiver, newPowerReceiver);

// f) Fix S2C_SYNC_INPUT_GAIN receiver: add sessionUUID
var gainReceiver = "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {\n" +
    "            int handOrdinal = buf.readInt();\n" +
    "            float gain = buf.readFloat();\n" +
    "            client.execute(() -> {\n" +
    "                if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {\n" +
    "                    screen.updateInputGain(gain);\n" +
    "                }\n" +
    "                com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGain(gain);\n" +
    "            });\n" +
    "        });";

var newGainReceiver = "ClientPlayNetworking.registerGlobalReceiver(S2C_SYNC_INPUT_GAIN, (client, handler, buf, responseSender) -> {\n" +
    "            UUID sessionUUID = buf.readUuid();\n" +
    "            int handOrdinal = buf.readInt();\n" +
    "            float gain = buf.readFloat();\n" +
    "            client.execute(() -> {\n" +
    "                if (client.currentScreen instanceof com.audiophilecraft.client.screen.AmplifierScreen screen) {\n" +
    "                    screen.updateInputGain(gain);\n" +
    "                }\n" +
    "                com.audiophilecraft.sound.AudioEngine.getInstance().updateInputGainForSession(sessionUUID, gain);\n" +
    "            });\n" +
    "        });";

mm = mm.replace(gainReceiver, newGainReceiver);

// g) Add S2C_SYNC_EQ receiver after S2C_SEEK_TRACK receiver
var seekReceiverEnd = "        // Track Timeline Seek Sync \u2014 now scoped to session UUID\n" +
    "        ClientPlayNetworking.registerGlobalReceiver(S2C_SEEK_TRACK, (client, handler, buf, responseSender) -> {\n" +
    "            UUID sessionUUID = buf.readUuid();\n" +
    "            float targetTime = buf.readFloat();\n" +
    "            client.execute(() -> {\n" +
    "                com.audiophilecraft.sound.AudioEngine engine = com.audiophilecraft.sound.AudioEngine.getInstance();\n" +
    "                engine.ensureActiveSession(sessionUUID);\n" +
    "                engine.seek(targetTime);\n" +
    "            });\n" +
    "        });";

seekReceiverEnd = seekReceiverEnd.normalize('NFC');

// Find this exact block
var seekBlockIdx = mm.indexOf('S2C_SEEK_TRACK, (client, handler, buf, responseSender)');
if (seekBlockIdx < 0) { console.log('SEEK BLOCK NOT FOUND'); process.exit(1); }
// Find the closing "});" of this block
var seekClose = mm.indexOf('\n    }', mm.indexOf('engine.seek(targetTime)', seekBlockIdx));
if (seekClose < 0) { seekClose = mm.indexOf('\n        });', mm.indexOf('engine.seek(targetTime)', seekBlockIdx)); }
// Find next two }); to get past the closing of registerGlobalReceiver
var afterSeek = mm.indexOf('});', seekClose + 1);
if (afterSeek < 0) afterSeek = mm.indexOf('}', seekClose + 1);

// Find the actual end of the block
var actualEnd = mm.indexOf('    }', afterSeek);
if (actualEnd < 0) actualEnd = seekClose + 40;

var eqReceiver = '\n\n        // EQ Sync — scoped to session UUID\n' +
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
    '        });';

mm = mm.substring(0, actualEnd + 1) + eqReceiver + mm.substring(actualEnd + 1);

fs.writeFileSync(mmPath, mm, 'utf8');
console.log('ModMessages.java updated');

// === 2) Add per-session helpers to AudioEngine.java ===
var aePath = prj + '/src/main/java/com/audiophilecraft/sound/AudioEngine.java';
var ae = fs.readFileSync(aePath, 'utf8');

// Add updatePowerForSession and updateInputGainForSession after updatePower
var updatePowerEnd = '    public void updatePower(float power) {\n' +
    '        if (getActiveSession() == null) return;\n' +
    '        for (StreamSource ss : getActiveSession().getStreamSources()) {\n' +
    '            ss.power = power;\n' +
    '        }\n' +
    '    }';

var newMethods = '\n' +
    '    public void updatePowerForSession(java.util.UUID sessionId, float power) {\n' +
    '        PlaybackSession session = sessions.get(sessionId);\n' +
    '        if (session == null) return;\n' +
    '        for (StreamSource ss : session.getStreamSources()) {\n' +
    '            ss.power = power;\n' +
    '        }\n' +
    '    }\n' +
    '\n' +
    '    public void updateInputGainForSession(java.util.UUID sessionId, float gain) {\n' +
    '        PlaybackSession session = sessions.get(sessionId);\n' +
    '        if (session == null) return;\n' +
    '        for (StreamSource ss : session.getStreamSources()) {\n' +
    '            ss.inputGain = gain;\n' +
    '        }\n' +
    '    }';

if (ae.indexOf(updatePowerEnd) >= 0) {
    ae = ae.replace(updatePowerEnd, updatePowerEnd + newMethods);
    fs.writeFileSync(aePath, ae, 'utf8');
    console.log('AudioEngine.java: per-session power/gain helpers added');
} else {
    console.log('updatePower method not found, trying with different whitespace');
    // Try finding it differently
    var upIdx = ae.indexOf('public void updatePower(float power)');
    if (upIdx >= 0) {
        console.log('Found at', upIdx);
        console.log(JSON.stringify(ae.substring(upIdx, upIdx + 200)));
    }
}

// Verify
var v = fs.readFileSync(aePath, 'utf8');
console.log('updatePowerForSession:', v.indexOf('updatePowerForSession') >= 0);
console.log('updateInputGainForSession:', v.indexOf('updateInputGainForSession') >= 0);
