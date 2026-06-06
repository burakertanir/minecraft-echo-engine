const fs = require('fs');
const s = fs.readFileSync('src/main/java/com/audiophilecraft/sound/AudioEngine.java', 'utf8');
const lines = s.split('\n');
console.log('Total lines:', lines.length);

// Rough line count by section
const sections = {
    'EFX (Reverb, HRTF, EA, occlusion, reflection, probe, stageDir)': 0,
    'Listener (position, underwater, HF)': 0,
    'Mixer (gain, EQ, mute, band)': 0,
    'Playback (clock, thread, pause/resume/stop, tick, sync)': 0,
    'Buffer (Ogg decode, StreamBuffer)': 0,
    'Track loading (playTrack, PCM, URL, Lava)': 0,
    'Source creation (cluster sources, cleanup)': 0,
    'Other (constructor, fields, imports)': 0
};

const marks = {};
// Find section headers
for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (l.includes('--- MASTER REVERB OCCLUSION') || l.includes('// ═')) marks['efx_occlusion'] = i;
    if (l.includes('updateListenerReflections') || l.includes('calculateVenueProbe')) marks['efx_probe'] = i;
    if (l.includes('updateMasterReverbOcclusion')) marks['efx_masterocc'] = i;
    if (l.includes('updateListener')) marks['listener'] = i;
    if (l.includes('UNDERWATER DETECTION') || l.includes('getUnderwaterHFGain')) marks['water'] = i;
    if (l.includes('MIXER STATE') || l.includes('mixerGainSub')) marks['mixer'] = i;
    if (l.includes('PLAYBACK CONTROL')) marks['playback'] = i;
    if (l.includes('processAudioBackground') || l.includes('startAudioThread')) marks['thread'] = i;
    if (l.includes('prepareStreamBuffers') || l.includes('createStreamBufferForType') || l.includes('applyDspForType')) marks['buffer'] = i;
    if (l.includes('playTrack(')) marks['track'] = i;
    if (l.includes('playFromPcmData')) marks['pcm'] = i;
    if (l.includes('createSourcesFromClusters')) marks['sources'] = i;
}

// Count lines between marks
const markNames = Object.keys(marks).sort((a,b) => marks[a] - marks[b]);
for (let i = 0; i < markNames.length; i++) {
    const next = i+1 < markNames.length ? marks[markNames[i+1]] : lines.length;
    const start = marks[markNames[i]];
    const size = next - start;
    console.log(`  ${markNames[i]}: ${size} lines`);
}

// Count field declarations area
let fieldCount = 0;
let inFields = false;
for (let i = 0; i < 150; i++) {
    const l = lines[i];
    if (l.includes('private ') || l.includes('public ') || l.includes('volatile ') || l.includes('final ')) {
        if (!l.includes('(')) fieldCount++;
    }
}
console.log('Fields (approx):', fieldCount);
