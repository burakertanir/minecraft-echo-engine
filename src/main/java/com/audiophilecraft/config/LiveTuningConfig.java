package com.audiophilecraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Hot-Reloading Live Tuning Configuration.
 *
 * All fields are public and mutable. Gson maps JSON keys directly to field
 * names.
 * New fields can be added at any time — if the JSON file doesn't contain a
 * field,
 * the Java default value is used automatically (Gson skips missing keys).
 *
 * Usage from any class:
 * LiveTuningConfig.get().sub_refDist
 *
 * File location: .minecraft/config/audiophilecraft_tuning.json
 * Check interval: every 20 ticks (1 second)
 */
public class LiveTuningConfig {
        private static LiveTuningConfig INSTANCE;
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static Path configPath;
        private static long lastModifiedTime = 0;
        private static int tickCounter = 0;
        private static long reloadGeneration = 0;

        /**
         * Returns a counter that increments every time the config is reloaded from
         * disk.
         */
        public static long getReloadGeneration() {
                return reloadGeneration;
        }

        // ════════════════════════════════════════════════════════════
        // CATEGORY 1: DISTANCE ATTENUATION
        // ════════════════════════════════════════════════════════════
        public float sub_refDist = 15.0f;
        public float sub_baseMaxDist = 120.0f;
        public float sub_rolloffExponent = 1.5f;
        public float mid_refDist = 7.0f;
        public float mid_baseMaxDist = 100.0f;
        public float mid_rolloffExponent = 1.8f;
        public float line_refDist = 5.0f;
        public float line_baseMaxDist = 90.0f;
        public float line_rolloffExponent = 2.0f;
        public float fadeStartPercent = 0.80f;

        // ════════════════════════════════════════════════════════════
        // CATEGORY 2: DIRECTIONALITY
        // ════════════════════════════════════════════════════════════
        public float line_hzExp = 1.5f;
        public float line_vtExpBase = 1.5f;
        public float line_vtExpPerSpeaker = 0.3f;
        public float line_rearGain = 0.50f;
        public float mid_hzExp = 2.2f;
        public float mid_vtExp = 2.0f;
        public float mid_rearGain = 0.35f;
        public float normal_hzExp = 2.7f;
        public float normal_vtExp = 2.0f;
        public float normal_rearGain = 0.24f;

        // ════════════════════════════════════════════════════════════
        // CATEGORY 3: WALL OCCLUSION
        // ════════════════════════════════════════════════════════════
        public float occ_standardWall = 0.42f;
        public float occ_varianceBlend = 0.25f;
        public float occ_sub_floor = 0.35f;
        public float occ_mid_floor = 0.38f;
        public float occ_line_floor = 0.50f;
        public float occ_lerpIn = 0.35f;
        public float occ_lerpOut = 0.15f;
        public float occ_hfExp_occluding = 1.35f;
        public float occ_hfExp_deoccluding = 1.10f;
        public float occ_raycast_flexOffset = 0.75f;
        public float occ_thicknessDecay = 0.85f;

        // ════════════════════════════════════════════════════════════
        // CATEGORY 4: PROXIMITY BOOST
        // ════════════════════════════════════════════════════════════
        public float prox_sub_maxBoost = 1.5f;
        public float prox_other_maxBoost = 0.15f;

        // ════════════════════════════════════════════════════════════
        // CATEGORY 5: HF DIRECTIONALITY & AIR ABSORPTION
        // ════════════════════════════════════════════════════════════
        public float hf_line_behindFloor = 0.05f;
        public float hf_line_frontFloor = 0.20f;
        public float hf_mid_behindFloor = 0.30f;
        public float hf_mid_frontFloor = 0.42f;
        public float hf_normal_behindFloor = 0.10f;
        public float hf_normal_frontFloor = 0.25f;
        public float hf_air_absorb_halving_dist = 145.0f;

        // ════════════════════════════════════════════════════════════
        // CATEGORY 6: REVERB MASTER (post-venue-preset multipliers)
        // ════════════════════════════════════════════════════════════
        public float reverb_decayMultiplier = 1.0f;
        public float reverb_gainMultiplier = 1.0f;
        public float reverb_gainHFMultiplier = 1.0f;
        public float reverb_reflGainMultiplier = 1.0f;
        public float reverb_lateGainMultiplier = 1.0f;
        public float reverb_densityOverride = -1.0f;
        public float reverb_diffusionOverride = -1.0f;

        // Master reverb occlusion
        public float masterOcc_gainFloor = 0.70f;
        public float masterOcc_hfExponent = 1.8f;
        public float masterOcc_lerpIn = 0.35f;
        public float masterOcc_lerpOut = 0.20f;

        // ════════════════════════════════════════════════════════════
        // CATEGORY 7: REVERB TIER SYSTEM
        // ════════════════════════════════════════════════════════════

        // --- OPEN AIR DYNAMIC TUNING ---
        public float openAir_dynamic_minGain = 0.08f;
        public float openAir_dynamic_reflGainFloor = 0.08f;
        public float openAir_dynamic_lateReverbMul = 0.05f;

        // --- TIER 1: CLOSET ---
        public float tier1_minGain = 0.28f;
        public float tier1_gainMul = 0.70f;
        public float tier1_reflGainMul = 0.80f;
        public float tier1_reflGainMax = 0.85f;
        public float tier1_lateReverbMul = 1.00f;

        // --- TIER 2: SMALL ROOM ---
        public float tier2_minGain = 0.25f;
        public float tier2_gainMul = 0.70f;
        public float tier2_reflGainMul = 1.00f;
        public float tier2_reflGainMax = 0.95f;
        public float tier2_lateReverbMul = 1.15f;

        // --- TIER 3: MEDIUM ROOM ---
        public float tier3_minGain = 0.18f;
        public float tier3_gainMul = 0.65f;
        public float tier3_reflGainMul = 0.85f;
        public float tier3_reflGainMax = 1.00f;
        public float tier3_lateReverbMul = 0.80f;

        // --- TIER 4: LARGE ROOM / SMALL HALL ---
        public float tier4_minGain = 0.165f;
        public float tier4_gainMul = 0.58f;
        public float tier4_reflGainMul = 0.67f;
        public float tier4_reflGainMax = 1.00f;
        public float tier4_lateReverbMul = 0.80f;
        public float tier4_lateReverbRoomScale = 0.10f;

        // --- TIER 5: LARGE CLUB ---
        public float tier5_minGain = 0.15f;
        public float tier5_gainMul = 0.52f;
        public float tier5_reflGainMul = 0.50f;
        public float tier5_reflGainMax = 1.00f;
        public float tier5_lateReverbMul = 0.80f;
        public float tier5_lateReverbRoomScale = 0.20f;

        // --- TIER 6: ARENA ---
        public float tier6_minGain = 0.12f;
        public float tier6_gainMul = 0.55f;
        public float tier6_reflGainMul = 0.60f;
        public float tier6_reflGainMax = 1.00f;
        public float tier6_lateReverbMul = 1.10f;
        public float tier6_lateReverbRoomScale = 0.30f;

        // --- TIER 7: MASSIVE STADIUM ---
        public float tier7_minGain = 0.20f;
        public float tier7_gainMul = 0.45f;
        public float tier7_reflGainMul = 0.45f;
        public float tier7_reflGainMax = 1.00f;
        public float tier7_lateReverbMul = 1.30f;
        public float tier7_lateReverbRoomScale = 0.70f;
        public float tier7_maxLateMultiplier_highEncl = 2.0f;
        public float tier7_maxLateMultiplier_lowEncl = 2.8f;

        // Tier Thresholds
        public float tier7_volumeThreshold = 200000.0f;
        public float tier7_distThreshold = 35.0f;
        public float tier6_volumeThreshold = 60000.0f;
        public float tier6_distThreshold = 22.0f;
        public float tier5_volumeThreshold = 15000.0f;
        public float tier5_distThreshold = 12.0f;
        public float tier4_volumeThreshold = 7000.0f;
        public float tier4_distThreshold = 9.0f;
        public float tier3_volumeThreshold = 2000.0f;
        public float tier3_distThreshold = 6.0f;
        public float tier2_volumeThreshold = 300.0f;
        public float tier2_distThreshold = 3.0f;

        // Open air thresholds
        public float openAir_openness_threshold = 0.25f;
        public float openAir_stronglyOpen_threshold = 0.65f;
        public float openAir_noCeiling_upClearance = 150.0f;

        // ════════════════════════════════════════════════════════════
        // CATEGORY 8: PHYSICS ENGINE
        // ════════════════════════════════════════════════════════════
        public float speedOfSound = 4000.0f;
        public float hrtf_yFlatten = 0.5f;
        public float physics_yFlatten = 0.5f;
        public float sourceRadius_sub = 0.35f;
        public float sourceRadius_mid = 0.20f;
        public float sourceRadius_line = 0.15f;
        public float gain_smoothing = 0.60f;
        public float reverb_send_near = 0.10f;
        public float reverb_send_far = 0.60f;

        // ════════════════════════════════════════════════════════════
        // SINGLETON & FILE MANAGEMENT
        // ════════════════════════════════════════════════════════════

        private LiveTuningConfig() {
        }

        /** Quick accessor: LiveTuningConfig.get().paramName */
        public static LiveTuningConfig get() {
                if (INSTANCE == null) {
                        initialize();
                }
                return INSTANCE;
        }

        /** Full singleton accessor */
        public static LiveTuningConfig getInstance() {
                return get();
        }

        /**
         * Initialize: load from file or create default.
         * Called once from AudiophileCraftClient.onInitializeClient().
         */
        public static void initialize() {
                configPath = FabricLoader.getInstance().getConfigDir().resolve("audiophilecraft_tuning.json");

                if (Files.exists(configPath)) {
                        loadFromFile();
                } else {
                        INSTANCE = new LiveTuningConfig();
                        saveToFile();
                }
        }

        /**
         * Check if the file has been modified since last read.
         * Called every tick, but only actually reads every 20 ticks (1 second).
         */
        public void checkReload() {
                tickCounter++;
                if (tickCounter < 20)
                        return;
                tickCounter = 0;

                try {
                        if (configPath == null || !Files.exists(configPath))
                                return;
                        long currentModified = configPath.toFile().lastModified();
                        if (currentModified != lastModifiedTime) {
                                loadFromFile();
                        }
                } catch (Exception e) {
                        System.err.println("[LiveTuning] Reload check failed: " + e.getMessage());
                }
        }

        /**
         * Load from file, stripping // comments before Gson parsing.
         */
        private static void loadFromFile() {
                try {
                        // Read all lines, strip // comments, then parse as JSON
                        String raw = Files.readString(configPath);
                        String cleaned = raw.lines()
                                        .map(line -> {
                                                // Remove // comments but preserve strings containing //
                                                int commentIdx = -1;
                                                boolean inString = false;
                                                for (int i = 0; i < line.length() - 1; i++) {
                                                        char c = line.charAt(i);
                                                        if (c == '"')
                                                                inString = !inString;
                                                        if (!inString && c == '/' && line.charAt(i + 1) == '/') {
                                                                commentIdx = i;
                                                                break;
                                                        }
                                                }
                                                return commentIdx >= 0 ? line.substring(0, commentIdx) : line;
                                        })
                                        .collect(Collectors.joining("\n"));

                        INSTANCE = GSON.fromJson(cleaned, LiveTuningConfig.class);
                        if (INSTANCE == null) {
                                INSTANCE = new LiveTuningConfig();
                        }
                        lastModifiedTime = configPath.toFile().lastModified();
                        reloadGeneration++;
                } catch (Exception e) {
                        System.err.println("[LiveTuning] Failed to load config: " + e.getMessage());
                        if (INSTANCE == null) {
                                INSTANCE = new LiveTuningConfig();
                        }
                }
        }

        /**
         * Save to file with beautiful Turkish comments explaining every parameter.
         */
        private static void saveToFile() {
                try {
                        Files.createDirectories(configPath.getParent());
                        try (PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(configPath.toFile())))) {
                                LiveTuningConfig c = INSTANCE;
                                w.println("{");
                                w.println("  // ╔══════════════════════════════════════════════════════════════════╗");
                                w.println("  // ║  AudiophileCraft - Canli Ses Ayar Dosyasi                       ║");
                                w.println("  // ║  Bu dosyayi oyun acikken duzenleyip kaydedin (Ctrl+S).           ║");
                                w.println("  // ║  Degisiklikler 1 saniye icinde otomatik olarak uygulanir.        ║");
                                w.println("  // ║  Oyundan cikmaniza GEREK YOK.                                   ║");
                                w.println("  // ╚══════════════════════════════════════════════════════════════════╝");
                                w.println();

                                // ─── CATEGORY 1 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 1: MESAFE ZAYIFLAMASI");
                                w.println("  // Hoparlorden uzaklastikca sesin ne kadar ve nasil azaldigini kontrol eder.");
                                w.println("  // refDist = Bu mesafeye kadar ses tam gucte kalir (blok cinsinden).");
                                w.println("  // baseMaxDist = Bu mesafeden sonra ses tamamen duyulmaz olur.");
                                w.println("  // rolloffExponent = Sesin dusus egrisi. Dusuk=yavas azalir, Yuksek=hizli azalir.");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();
                                writeParam(w, "sub_refDist", c.sub_refDist,
                                                "Subwoofer: Tam gucte duyulma mesafesi (blok). Arttirirsan bass daha uzaga gider.");
                                writeParam(w, "sub_baseMaxDist", c.sub_baseMaxDist,
                                                "Subwoofer: Maksimum duyulma mesafesi. Bunun otesinde tamamen sessiz.");
                                writeParam(w, "sub_rolloffExponent", c.sub_rolloffExponent,
                                                "Subwoofer: Ses dusus sertligi. 1.0=yavas, 2.0=hizli, 3.0=cok sert.");
                                writeParam(w, "mid_refDist", c.mid_refDist, "Mid-Range: Tam gucte duyulma mesafesi.");
                                writeParam(w, "mid_baseMaxDist", c.mid_baseMaxDist,
                                                "Mid-Range: Maksimum duyulma mesafesi.");
                                writeParam(w, "mid_rolloffExponent", c.mid_rolloffExponent,
                                                "Mid-Range: Ses dusus sertligi.");
                                writeParam(w, "line_refDist", c.line_refDist,
                                                "Line Array: Tam gucte duyulma mesafesi.");
                                writeParam(w, "line_baseMaxDist", c.line_baseMaxDist,
                                                "Line Array: Maksimum duyulma mesafesi.");
                                writeParam(w, "line_rolloffExponent", c.line_rolloffExponent,
                                                "Line Array: Ses dusus sertligi.");
                                writeParam(w, "fadeStartPercent", c.fadeStartPercent,
                                                "Son yuzde kacta ses yumusak sekilde soner. 0.80 = son %20'de kaybolur.");
                                w.println();

                                // ─── CATEGORY 2 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 2: YONSELLIK (Directionality)");
                                w.println("  // Hoparlorun onunden mi arksindan mi dinledigin fark eder.");
                                w.println("  // hzExp = Yatay odaklanma. Yuksek deger = kenarlardan daha az duyulur.");
                                w.println("  // vtExp = Dikey odaklanma. Yuksek deger = ust/alttan daha az duyulur.");
                                w.println("  // rearGain = Hoparlorun TAM ARKASINDA duyulma orani. 0.0=sessiz, 1.0=tam ses.");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();
                                writeParam(w, "line_hzExp", c.line_hzExp,
                                                "Line Array: Yatay odaklanma keskinligi. Arttirirsan yanlarda ses azalir.");
                                writeParam(w, "line_vtExpBase", c.line_vtExpBase,
                                                "Line Array: Dikey odaklanma tabani.");
                                writeParam(w, "line_vtExpPerSpeaker", c.line_vtExpPerSpeaker,
                                                "Line Array: Her ek hoparlor dikey odaklanmayi bu kadar arttirir.");
                                writeParam(w, "line_rearGain", c.line_rearGain,
                                                "Line Array: Arkadan duyulma orani. 0.50 = arkada %50 ses duyulur.");
                                writeParam(w, "mid_hzExp", c.mid_hzExp, "Mid-Range: Yatay odaklanma keskinligi.");
                                writeParam(w, "mid_vtExp", c.mid_vtExp, "Mid-Range: Dikey odaklanma keskinligi.");
                                writeParam(w, "mid_rearGain", c.mid_rearGain, "Mid-Range: Arkadan duyulma orani.");
                                writeParam(w, "normal_hzExp", c.normal_hzExp,
                                                "Normal Hoparlor: Yatay odaklanma keskinligi.");
                                writeParam(w, "normal_vtExp", c.normal_vtExp,
                                                "Normal Hoparlor: Dikey odaklanma keskinligi.");
                                writeParam(w, "normal_rearGain", c.normal_rearGain,
                                                "Normal Hoparlor: Arkadan duyulma orani.");
                                w.println();

                                // ─── CATEGORY 3 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 3: DUVAR ARKASI OKLUZYON");
                                w.println("  // Duvar/bina arkasinda sesin ne kadar boguklasmasi gerektigini kontrol eder.");
                                w.println("  // floor = Minimum duyulma orani. Yani duvar arkasinda en az ne kadar ses gecer.");
                                w.println("  // lerp = Gecis hizi. Yuksek=hizli gecis, Dusuk=yavas/yumusak gecis.");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();
                                writeParam(w, "occ_standardWall", c.occ_standardWall,
                                                "1 blok standart duvardan ne kadar ses gecer. 0.0=hic, 1.0=cam gibi saydam.");
                                writeParam(w, "occ_varianceBlend", c.occ_varianceBlend,
                                                "Farkli malzemelerin (tas, tahta, cam) okluzyon farki. Dusuk=hepsi benzer davranir.");
                                writeParam(w, "occ_sub_floor", c.occ_sub_floor,
                                                "Sub bass duvar arkasi minimum ses. 0.67 = duvar arkasinda bile %67 bass duyulur.");
                                writeParam(w, "occ_mid_floor", c.occ_mid_floor,
                                                "Mid duvar arkasi minimum ses. 0.35 = duvar arkasinda %35 mid duyulur.");
                                writeParam(w, "occ_line_floor", c.occ_line_floor,
                                                "Line Array duvar arkasi minimum ses.");
                                writeParam(w, "occ_lerpIn", c.occ_lerpIn,
                                                "Duvar arkasina GIRME hizi. 0.35=hizli boguklasin, 0.05=yavasca boguklasin.");
                                writeParam(w, "occ_lerpOut", c.occ_lerpOut,
                                                "Duvar arkasindan CIKMA hizi. 0.15=yavas acilsin, 0.50=hizli acilsin.");
                                writeParam(w, "occ_hfExp_occluding", c.occ_hfExp_occluding,
                                                "Duvar arkasina girerken tiz frekans bastirma egrisi. Yuksek=tizler daha hizli kesilir.");
                                writeParam(w, "occ_hfExp_deoccluding", c.occ_hfExp_deoccluding,
                                                "Duvar arkasindan cikarken tiz acilma egrisi. Dusuk=tizler daha hizli acilir.");
                                writeParam(w, "occ_raycast_flexOffset", c.occ_raycast_flexOffset,
                                                "Okluzyon yuvarlama esnekligi. 0.75 = capraz gecisleri (1.5 blok mesafeyi) 1 blok sayar.");
                                w.println();

                                // ─── CATEGORY 4 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 4: YAKINLIK ETKISI (Proximity Boost)");
                                w.println("  // Hoparlore cok yaklastiginda sesin ne kadar guclenmesi gerektigini ayarlar.");
                                w.println("  // Gercek hayatta hoparlore yaklasinca bas frekanslar artar (proximity effect).");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();
                                writeParam(w, "prox_sub_maxBoost", c.prox_sub_maxBoost,
                                                "Subwoofer yakin alan guclenme orani. 1.5 = yaklasinca bass %150 artar.");
                                writeParam(w, "prox_other_maxBoost", c.prox_other_maxBoost,
                                                "Mid/Line yakin alan guclenme orani. 0.15 = yaklasinca ses %15 artar.");
                                w.println();

                                // ─── CATEGORY 5 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 5: TIZ FREKANS YONSELLIGI VE HAVA EMILIMI");
                                w.println("  // Tiz frekanslar (cis sesler, zilller) yonsel davranir.");
                                w.println("  // behindFloor = Hoparlorun arkasinda tiz minimum seviye.");
                                w.println("  // frontFloor = Hoparlorun onunde tiz minimum seviye.");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();
                                writeParam(w, "hf_line_behindFloor", c.hf_line_behindFloor,
                                                "Line Array arkasinda tiz taban seviyesi. 0.05 = arkada tizlerin %5'i duyulur.");
                                writeParam(w, "hf_line_frontFloor", c.hf_line_frontFloor,
                                                "Line Array onunde tiz taban seviyesi. 0.20 = onde tiz asla %20'nin altina dusmez.");
                                writeParam(w, "hf_mid_behindFloor", c.hf_mid_behindFloor,
                                                "Mid arkasinda tiz taban seviyesi.");
                                writeParam(w, "hf_mid_frontFloor", c.hf_mid_frontFloor,
                                                "Mid onunde tiz taban seviyesi.");
                                writeParam(w, "hf_normal_behindFloor", c.hf_normal_behindFloor,
                                                "Normal Hoparlor arkasinda tiz taban seviyesi.");
                                writeParam(w, "hf_normal_frontFloor", c.hf_normal_frontFloor,
                                                "Normal Hoparlor onunde tiz taban seviyesi.");
                                writeParam(w, "hf_air_absorb_halving_dist", c.hf_air_absorb_halving_dist,
                                                "Kac blok mesafede tizler yariya duser. 135 = 135 blokta tiz yarilir.");
                                w.println();

                                // ─── CATEGORY 6 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 6: REVERB (YANKI) ANA KONTROLLERI");
                                w.println("  // Bunlar mekan taramasi sonrasi uygulanan CARPANLARDIR.");
                                w.println("  // 1.0 = degisiklik yok. 2.0 = iki katina cikar. 0.5 = yariya duser.");
                                w.println("  // Override degerler: -1 = otomatik (mekan taramasina birak), 0-1 = sabit deger.");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();
                                writeParam(w, "reverb_decayMultiplier", c.reverb_decayMultiplier,
                                                "Yanki suresi carpani. 2.0 yaparsan yanki 2 kat uzar. 0.5 yaparsan yarilir.");
                                writeParam(w, "reverb_gainMultiplier", c.reverb_gainMultiplier,
                                                "Genel yanki hacmi carpani. Arttirirsan yanki daha gur duyulur.");
                                writeParam(w, "reverb_gainHFMultiplier", c.reverb_gainHFMultiplier,
                                                "Yankinin tiz frekans carpani. Dusursen yanki daha boguk/sicak duyulur.");
                                writeParam(w, "reverb_reflGainMultiplier", c.reverb_reflGainMultiplier,
                                                "Erken yansima carpani. Duvarlardan gelen ilk yansimalar.");
                                writeParam(w, "reverb_lateGainMultiplier", c.reverb_lateGainMultiplier,
                                                "Gec yanki (kuyruk) carpani. Yankinin uzun kuyrugunun gucu.");
                                writeParam(w, "reverb_densityOverride", c.reverb_densityOverride,
                                                "Yanki yogunlugu. -1=otomatik. 0.0=seyrek, 1.0=yogun/dolu.");
                                writeParam(w, "reverb_diffusionOverride", c.reverb_diffusionOverride,
                                                "Yanki dagilimi. -1=otomatik. 0.0=belirgin eko, 1.0=yumusak dagilan yanki.");
                                w.println();

                                w.println("  // --- Duvar Arkasi Yanki Okluzyon ---");
                                w.println("  // Binadan disari ciktiginda yankinin ne kadar boguklasmasi gerektigini ayarlar.");
                                writeParam(w, "masterOcc_gainFloor", c.masterOcc_gainFloor,
                                                "Duvar arkasinda yankinin minimum hacmi. 0.60 = en az %60 yanki duyulur.");
                                writeParam(w, "masterOcc_hfExponent", c.masterOcc_hfExponent,
                                                "Yanki tiz bastirma sertligi. Yuksek=duvar arkasi yanki daha boguk.");
                                writeParam(w, "masterOcc_lerpIn", c.masterOcc_lerpIn,
                                                "Binaya girme (yanki boguklasmasi) hizi.");
                                writeParam(w, "masterOcc_lerpOut", c.masterOcc_lerpOut,
                                                "Binadan cikma (yanki acilmasi) hizi.");
                                w.println();

                                // ─── CATEGORY 7 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 7: REVERB TIER SISTEMI");
                                w.println("  // Her oda boyutu icin ayri ayri yanki parametreleri.");
                                w.println("  // minGain = Minimum yanki hacmi.");
                                w.println("  // gainMul = Yanki hacim carpani (kapaliligin etkisi).");
                                w.println("  // reflGainMul = Erken yansima gucu carpani.");
                                w.println("  // reflGainMax = Erken yansima maksimum siniri.");
                                w.println("  // lateReverbMul = Gec yanki kuyrugu carpani. Arttir = daha uzun/epic kuyruk.");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();

                                // Open Air
                                w.println("  // --- ACIK HAVA DINAMIK AYARLARI ---");
                                w.println("  // Acik hava artik Sabine tarafindan hesaplanir, ancak bu carpanlar aciklik oranina bagli eklenebilir.");
                                writeParam(w, "openAir_dynamic_minGain", c.openAir_dynamic_minGain,
                                                "Acik havada minimum yanki hacmi tabani.");
                                writeParam(w, "openAir_dynamic_reflGainFloor", c.openAir_dynamic_reflGainFloor,
                                                "Acik havada yer yansimalari (erken yansima) icin minimum guc.");
                                writeParam(w, "openAir_dynamic_lateReverbMul", c.openAir_dynamic_lateReverbMul,
                                                "Acik havada uzun kuyruk (late reverb) minimum carpani.");
                                w.println();

                                // Tier 1
                                w.println("  // --- TIER 1: DOLAP / COK KUCUK ALAN (1-3 blok) ---");
                                w.println("  // Duslarin gibi kucuk bir kutu. Yanki hemen geri gelir.");
                                writeParam(w, "tier1_minGain", c.tier1_minGain, "Minimum yanki hacmi.");
                                writeParam(w, "tier1_gainMul", c.tier1_gainMul, "Yanki hacim carpani.");
                                writeParam(w, "tier1_reflGainMul", c.tier1_reflGainMul, "Erken yansima gucu carpani.");
                                writeParam(w, "tier1_reflGainMax", c.tier1_reflGainMax,
                                                "Erken yansima maksimum siniri.");
                                writeParam(w, "tier1_lateReverbMul", c.tier1_lateReverbMul, "Gec yanki carpani.");
                                w.println();

                                // Tier 2
                                w.println("  // --- TIER 2: KUCUK ODA (3-6 blok) ---");
                                w.println("  // Yatak odasi veya kucuk ofis buyuklugunde. Belirgin oda etkisi.");
                                writeParam(w, "tier2_minGain", c.tier2_minGain, "Minimum yanki hacmi.");
                                writeParam(w, "tier2_gainMul", c.tier2_gainMul, "Yanki hacim carpani.");
                                writeParam(w, "tier2_reflGainMul", c.tier2_reflGainMul, "Erken yansima gucu carpani.");
                                writeParam(w, "tier2_reflGainMax", c.tier2_reflGainMax,
                                                "Erken yansima maksimum siniri.");
                                writeParam(w, "tier2_lateReverbMul", c.tier2_lateReverbMul, "Gec yanki carpani.");
                                w.println();

                                // Tier 3
                                w.println("  // --- TIER 3: ORTA ODA / STUDYO (6-12 blok) ---");
                                w.println("  // Konferans salonu veya muzik studyosu. Dengeli yanki.");
                                writeParam(w, "tier3_minGain", c.tier3_minGain, "Minimum yanki hacmi.");
                                writeParam(w, "tier3_gainMul", c.tier3_gainMul, "Yanki hacim carpani.");
                                writeParam(w, "tier3_reflGainMul", c.tier3_reflGainMul, "Erken yansima gucu carpani.");
                                writeParam(w, "tier3_reflGainMax", c.tier3_reflGainMax,
                                                "Erken yansima maksimum siniri.");
                                writeParam(w, "tier3_lateReverbMul", c.tier3_lateReverbMul, "Gec yanki carpani.");
                                w.println();

                                // Tier 4
                                w.println("  // --- TIER 4: LARGE ROOM / SMALL HALL (7-12 blok) ---");
                                w.println("  // Buyuk oda veya kucuk balo salonu. Daha uzun yanki kuyruklari.");
                                writeParam(w, "tier4_minGain", c.tier4_minGain, "Minimum yanki hacmi.");
                                writeParam(w, "tier4_gainMul", c.tier4_gainMul, "Yanki hacim carpani.");
                                writeParam(w, "tier4_reflGainMul", c.tier4_reflGainMul, "Erken yansima gucu carpani.");
                                writeParam(w, "tier4_reflGainMax", c.tier4_reflGainMax,
                                                "Erken yansima maksimum siniri.");
                                writeParam(w, "tier4_lateReverbMul", c.tier4_lateReverbMul, "Gec yanki carpani.");
                                writeParam(w, "tier4_lateReverbRoomScale", c.tier4_lateReverbRoomScale,
                                                "Oda boyutunun gec yanki uzerine etkisi.");
                                w.println();

                                // Tier 5
                                w.println("  // --- TIER 5: BUYUK KULUP / SPOR SALONU (12-22 blok) ---");
                                w.println("  // Gece kulubu, balo salonu. Belirgin yanki kuyruklari.");
                                writeParam(w, "tier5_minGain", c.tier5_minGain, "Minimum yanki hacmi.");
                                writeParam(w, "tier5_gainMul", c.tier5_gainMul, "Yanki hacim carpani.");
                                writeParam(w, "tier5_reflGainMul", c.tier5_reflGainMul, "Erken yansima gucu carpani.");
                                writeParam(w, "tier5_reflGainMax", c.tier5_reflGainMax,
                                                "Erken yansima maksimum siniri.");
                                writeParam(w, "tier5_lateReverbMul", c.tier5_lateReverbMul, "Gec yanki carpani.");
                                writeParam(w, "tier5_lateReverbRoomScale", c.tier5_lateReverbRoomScale,
                                                "Oda boyutunun gec yanki uzerine etkisi.");
                                w.println();

                                // Tier 6
                                w.println("  // --- TIER 6: ARENA / KONSER SALONU (22-35 blok) ---");
                                w.println("  // Kapali arena, tiyatro. Epic yanki kuyruklari.");
                                writeParam(w, "tier6_minGain", c.tier6_minGain, "Minimum yanki hacmi.");
                                writeParam(w, "tier6_gainMul", c.tier6_gainMul, "Yanki hacim carpani.");
                                writeParam(w, "tier6_reflGainMul", c.tier6_reflGainMul, "Erken yansima gucu carpani.");
                                writeParam(w, "tier6_reflGainMax", c.tier6_reflGainMax,
                                                "Erken yansima maksimum siniri.");
                                writeParam(w, "tier6_lateReverbMul", c.tier6_lateReverbMul, "Gec yanki carpani.");
                                writeParam(w, "tier6_lateReverbRoomScale", c.tier6_lateReverbRoomScale,
                                                "Oda boyutunun gec yanki uzerine etkisi.");
                                w.println();

                                // Tier 7
                                w.println("  // --- TIER 7: DEVASA STADYUM (35+ blok) ---");
                                w.println("  // Kapali stadyum. Muazzam yanki kuyruklari ve atmosfer.");
                                writeParam(w, "tier7_minGain", c.tier7_minGain, "Minimum yanki hacmi.");
                                writeParam(w, "tier7_gainMul", c.tier7_gainMul, "Yanki hacim carpani.");
                                writeParam(w, "tier7_reflGainMul", c.tier7_reflGainMul, "Erken yansima gucu carpani.");
                                writeParam(w, "tier7_reflGainMax", c.tier7_reflGainMax,
                                                "Erken yansima maksimum siniri.");
                                writeParam(w, "tier7_lateReverbMul", c.tier7_lateReverbMul,
                                                "Gec yanki carpani. Stadyum epic kuyrugu.");
                                writeParam(w, "tier7_lateReverbRoomScale", c.tier7_lateReverbRoomScale,
                                                "Stadyum boyutunun gec yanki etkisi.");
                                writeParam(w, "tier7_maxLateMultiplier_highEncl", c.tier7_maxLateMultiplier_highEncl,
                                                "Tam kapali stadyumda gec yanki ust siniri.");
                                writeParam(w, "tier7_maxLateMultiplier_lowEncl", c.tier7_maxLateMultiplier_lowEncl,
                                                "Yarim acik stadyumda gec yanki ust siniri.");
                                w.println();

                                // Tier Thresholds
                                w.println("  // --- TIER SINIRLARI ---");
                                w.println("  // Hangi oda boyutu hangi tier'a duser. Volume=hacim, dist=ortalama mesafe.");
                                writeParam(w, "tier7_volumeThreshold", c.tier7_volumeThreshold,
                                                "Tier 7 icin minimum hacim (blok^3). Bunun ustu = Stadyum.");
                                writeParam(w, "tier7_distThreshold", c.tier7_distThreshold,
                                                "Tier 7 icin minimum ortalama duvar mesafesi (blok).");
                                writeParam(w, "tier6_volumeThreshold", c.tier6_volumeThreshold,
                                                "Tier 6 icin minimum hacim.");
                                writeParam(w, "tier6_distThreshold", c.tier6_distThreshold,
                                                "Tier 6 icin minimum mesafe.");
                                writeParam(w, "tier5_volumeThreshold", c.tier5_volumeThreshold,
                                                "Tier 5 icin minimum hacim.");
                                writeParam(w, "tier5_distThreshold", c.tier5_distThreshold,
                                                "Tier 5 icin minimum mesafe.");
                                writeParam(w, "tier4_volumeThreshold", c.tier4_volumeThreshold,
                                                "Tier 4 icin minimum hacim.");
                                writeParam(w, "tier4_distThreshold", c.tier4_distThreshold,
                                                "Tier 4 icin minimum mesafe.");
                                writeParam(w, "tier3_volumeThreshold", c.tier3_volumeThreshold,
                                                "Tier 3 icin minimum hacim.");
                                writeParam(w, "tier3_distThreshold", c.tier3_distThreshold,
                                                "Tier 3 icin minimum mesafe.");
                                writeParam(w, "tier2_volumeThreshold", c.tier2_volumeThreshold,
                                                "Tier 2 icin minimum hacim.");
                                writeParam(w, "tier2_distThreshold", c.tier2_distThreshold,
                                                "Tier 2 icin minimum mesafe. Bunun alti = Dolap (Tier 1).");
                                w.println();

                                // Open Air Thresholds
                                w.println("  // --- ACIK HAVA ESIKLERI ---");
                                writeParam(w, "openAir_openness_threshold", c.openAir_openness_threshold,
                                                "Bu aciklik oraninin ustu = 'yari acik hava' sayilir.");
                                writeParam(w, "openAir_stronglyOpen_threshold", c.openAir_stronglyOpen_threshold,
                                                "Bu aciklik oraninin ustu = 'tam acik hava festivali' moduna gecer.");
                                writeParam(w, "openAir_noCeiling_upClearance", c.openAir_noCeiling_upClearance,
                                                "Yukari kac blok bos olursa 'tavan yok' sayilir.");
                                w.println();

                                // ─── CATEGORY 8 ───
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println("  // BOLUM 8: FIZIK MOTORU");
                                w.println("  // Sesin fiziksel davranisini kontrol eden temel parametreler.");
                                w.println("  // ════════════════════════════════════════════════════════════════");
                                w.println();
                                writeParam(w, "speedOfSound", c.speedOfSound,
                                                "Ses hizi (blok/saniye). Gercek hayat=343 m/s. Minecraft icin 4000 ideal.");
                                writeParam(w, "hrtf_yFlatten", c.hrtf_yFlatten,
                                                "HRTF Y ekseni daraltma. 0.5 = yukseklik etkisi yariya iner. 0=yok, 1=tam.");
                                writeParam(w, "physics_yFlatten", c.physics_yFlatten,
                                                "Fizik Y ekseni daraltma. Mesafe hesaplamasinda yukseklik ne kadar sayilir.");
                                writeParam(w, "sourceRadius_sub", c.sourceRadius_sub,
                                                "Sub hoparlor kaynak genisligi. Arttirirsan ses daha genis yayilir.");
                                writeParam(w, "sourceRadius_mid", c.sourceRadius_mid, "Mid hoparlor kaynak genisligi.");
                                writeParam(w, "sourceRadius_line", c.sourceRadius_line, "Line Array kaynak genisligi.");
                                writeParam(w, "gain_smoothing", c.gain_smoothing,
                                                "Hacim gecis yumusakligi. Dusuk=yavas/yumusak, Yuksek=hizli/anlik.");
                                writeParam(w, "reverb_send_near", c.reverb_send_near,
                                                "Yakin mesafede yankiya gonderilen ses miktari.");
                                writeLastParam(w, "reverb_send_far", c.reverb_send_far,
                                                "Uzak mesafede yankiya gonderilen ses miktari. Uzakta yanki baskin olur.");

                                w.println("}");
                        }
                        lastModifiedTime = configPath.toFile().lastModified();
                } catch (Exception e) {
                        System.err.println("[LiveTuning] Failed to save config: " + e.getMessage());
                }
        }

        /** Write a JSON parameter with a trailing // comment */
        private static void writeParam(PrintWriter w, String key, float value, String comment) {
                String valueStr = formatFloat(value);
                w.println("  \"" + key + "\": " + valueStr + ",  // " + comment);
        }

        /** Write the LAST JSON parameter (no trailing comma) */
        private static void writeLastParam(PrintWriter w, String key, float value, String comment) {
                String valueStr = formatFloat(value);
                w.println("  \"" + key + "\": " + valueStr + "   // " + comment);
        }

        /** Format float: show .0 for whole numbers, avoid scientific notation */
        private static String formatFloat(float value) {
                if (value == (int) value) {
                        return String.valueOf((int) value) + ".0";
                }
                // Avoid trailing zeros like 0.3500000
                String s = String.valueOf(value);
                // Remove unnecessary trailing zeros but keep at least one decimal
                if (s.contains(".")) {
                        s = s.replaceAll("0+$", "");
                        if (s.endsWith("."))
                                s += "0";
                }
                return s;
        }
}
