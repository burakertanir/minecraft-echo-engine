# AudiophileCraft - Proje İlerleme Kaydı
> **Son Güncelleme:** 2026-03-20 22:58 (UTC+3)
> **Minecraft Sürümü:** 1.20.1 | **Fabric Loader:** 0.15.7 | **Loom:** 1.6-SNAPSHOT

---

## 📌 Proje Genel Durumu: DELAY ENGINE REWRITE + LATENCY OPTİMİZASYONU

Mod temel işlevselliği + internet üzerinden ses çalma (URL) + otomatik hoparlör-amfi bağlantısı + performans optimizasyonları çalışır durumda. Delay motoru tamamen yeniden yazıldı — sample-continuous interpolasyon ve unified engine ile tıkırtı artefaktları giderildi.

---

## 🏗️ Proje Mimarisi

### Dosya Yapısı (25+ Java kaynak dosyası)
```
src/main/java/com/audiophilecraft/
├── AudiophileCraft.java           (27 satır)  — Ana mod başlatıcı (server-side)
├── AudiophileCraftClient.java     (48 satır)  — Client başlatıcı, tick/render event hookları
├── block/
│   ├── AmplifierBlock.java        (85 satır)  — Amfi blok, GUI açma, auto-connect, registry
│   ├── SpeakerBlock.java          (103 satır) — Temel hoparlör blok (FACING, screen, entity, auto-connect)
│   ├── SubwooferBlock.java        (8 satır)   — SpeakerBlock extends → subwoofer
│   ├── MidRangeBlock.java         (8 satır)   — SpeakerBlock extends → mid-range
│   ├── LineArrayBlock.java        (8 satır)   — SpeakerBlock extends → line array
│   └── entity/
│       ├── AmplifierBlockEntity.java  (185 satır) — Bağlı hoparlörler, güç, input gain, NBT, registry
│       └── SpeakerBlockEntity.java    (86 satır)  — Sample shift ayarı, NBT, registry
├── client/screen/
│   ├── AmplifierScreen.java       (307 satır) — Amfi GUI (scan, play, power slider, gain slider)
│   └── SpeakerScreen.java         (101 satır) — Hoparlör GUI (shift slider)
├── screen/
│   ├── AmplifierScreenHandler.java (80 satır) — Amfi screen handler
│   └── SpeakerScreenHandler.java   (50 satır) — Hoparlör screen handler
├── network/
│   └── ModMessages.java           (260 satır) — C2S/S2C paketleri (play, power, gain, scan, shift)
├── registry/
│   ├── ModBlocks.java             (45 satır)  — Blok kayıtları
│   ├── ModBlockEntities.java      (35 satır)  — BlockEntity kayıtları
│   ├── ModScreenHandlers.java     (27 satır)  — ScreenHandler kayıtları
│   ├── ModItemGroups.java         (29 satır)  — Creative tab
│   └── SpeakerRegistry.java       (72 satır)  — **YENİ** Global hoparlör/amfi pozisyon kayıt sistemi
└── sound/
    ├── AudioEngine.java           (1130 satır) — ANA SES MOTORU (occlusion clustering eklendi)
    ├── StreamSource.java          (730 satır) — Per-speaker akış kaynağı (reusable buffer, batch start)
    ├── AdvancedAcousticScanner.java (880 satır) — 26-ışın akustik tarayıcı
    ├── AudioDSP.java              (191 satır) — Biquad filtre, sıkıştırma, soft clip
    ├── AudioStreamBuffer.java     (161 satır) — Ring buffer (47 sn)
    └── OggDecoder.java            (104 satır) — OGG → Mono PCM çözücü
```

### Kaynak (Resources) Dosyaları
```
src/main/resources/
├── fabric.mod.json
├── audiophilecraft.mixins.json
└── assets/audiophilecraft/
    ├── blockstates/     → amplifier, subwoofer, mid_range, line_array
    ├── models/block/    → amplifier, subwoofer, mid_range, line_array
    ├── models/item/     → amplifier, subwoofer, mid_range, line_array
    ├── lang/en_us.json
    ├── sounds/music/test_track.ogg
    └── textures/gui/    → (amplifier_gui.png)
```

---

## ⚙️ Tamamlanan Özellikler

### 1. Ses Motoru (AudioEngine.java — 878 satır)
- [x] **HRTF:** Binaural 3D ses (alcResetDeviceSOFT ile)
- [x] **EFX Reverb:** EAX Reverb effect + 2 auxiliary slot (room reverb + atmospheric reverb)
- [x] **Akustik Tarama Entegrasyonu:** AdvancedAcousticScanner sonuçları → EAX parametreleri
- [x] **Atmospheric Reverb:** Mesafeye bağlı morfing reverb (HF gain, decay, delay, density)
- [x] **Listener Tracking:** Her render frame'de pozisyon/yönelim güncelleme
- [x] **Underwater Filter:** Su altı HF gain smoothing (0.08→1.0)
- [x] **StreamSource Yönetimi:** Çoklu hoparlör kaynağı oluşturma/temizleme
- [x] **DSP Pipeline:** Her speaker tipi için ayrı DSP zinciri (Subwoofer/MidRange/LineArray)
- [x] **Stream Buffer Sistemi:** Track başına paylaşılan ring buffer
- [x] **Pause/Resume/Stop:** Oyun duraklatma/devam/durdurma desteği
- [x] **Live Power Update:** Amfi güç değişikliği → tüm aktif StreamSource'lara anlık yansıma
- [x] **Live Input Gain Update:** Input gain değişikliği → AudioEngine'e doğrudan push

### 2. DSP Pipeline (AudioDSP.java — 191 satır)
- [x] **Biquad Filter:** LowPass, HighPass, BandPass, PeakingEQ, HighShelf, LowShelf
- [x] **Gain Kontrolü:** Lineer gain uygulaması (clamp to short range)
- [x] **Soft Clipping:** tanh saturasyonu (drive parametresi ile)
- [x] **Peak Limiter:** Otomatik pik sınırlama
- [x] **Dynamic Range Compression:** Threshold/ratio/makeup gain ile sıkıştırma

### 3. Akustik Tarama (AdvancedAcousticScanner.java — 880 satır)
- [x] **26-Ray Temporal Slicing:** 5 tick'te tam tarama (her tick 5-6 ışın)
- [x] **Sabine Denklemi:** RT60 = 0.161 × V / A
- [x] **Malzeme Absorpsiyonu:** HashMap ile O(1) blok → katsayı eşlemesi
- [x] **Smoothed Parameters:** Per-frame interpolasyon (10%)
- [x] **Kapalılık (Enclosure):** Çevre duvar tespiti
- [x] **Yoğunluk/Dağılım:** Oda geometrisine dayalı density/diffusion
- [x] **HF Decay Ratio:** Sert/yumuşak malzeme ayrımı
- [x] **Hava Absorpsiyonu:** Mesafe bazlı HF kaybı

### 4. Per-Speaker Streaming (StreamSource.java — 714 satır)
- [x] **OpenAL Streaming:** 4 buffer, 8192 sample stream boyutu
- [x] **Mesafe Bazlı Fizik:** Ters kare/küp yasası, Doppler, bas maskeleme
- [x] **Occlusion (Duvar perdesi):** Raycast ile blok geçiş katsayısı
- [x] **Yönetim (Directivity):** Speaker yönüne göre HF/LF kazanç
- [x] **Sample Shift:** Per-speaker gecikme (0-30ms)
- [x] **Gain Smoothing:** Zipper noise önleme (α=0.05)
- [x] **Input Gain Support:** Amplifier'dan okunan input gain çarpanı
- [x] **Power Smoothing:** Live power değişikliklerinde yumuşak geçiş (α=0.08)

### 5. Blok/Entity/Screen Sistemi
- [x] **3 Speaker Tipi:** Subwoofer, MidRange, LineArray (SpeakerBlock extends)
- [x] **Amplifier Blok:** GUI ile hoparlör tarama, bağlantı, play, power, input gain
- [x] **Speaker Blok:** GUI ile sample shift ayarı
- [x] **NBT Persist:** Bağlantılar, güç, gain, shift ayarları kaydedilir
- [x] **BlockEntity Sync:** toUpdatePacket/toInitialChunkDataNbt ile chunk sync

### 6. Network (ModMessages.java — 260 satır)
- [x] **C2S_TOGGLE_CONNECTION:** Hoparlör bağlantı aç/kapa
- [x] **C2S_REQUEST_SCAN:** Amfi çevresinde hoparlör tarama
- [x] **C2S_REQUEST_PLAY:** Müzik çalma isteği
- [x] **C2S_UPDATE_POWER:** Güç ayarı güncelleme
- [x] **C2S_UPDATE_INPUT_GAIN:** Input gain güncelleme
- [x] **C2S_UPDATE_SPEAKER_SHIFT:** Hoparlör shift güncelleme
- [x] **S2C_PLAY_TRACK:** Müzik çalma komutu (broadcast)
- [x] **S2C_SYNC_POWER:** Güç senkronizasyonu
- [x] **S2C_SYNC_INPUT_GAIN:** Gain senkronizasyonu
- [x] **S2C_SYNC_SPEAKER_LIST:** Hoparlör listesi senkronizasyonu
- [x] **Multiplayer Support:** PlayerLookup.tracking ile çevredeki oyunculara broadcast

---

## 📜 Değişiklik Geçmişi (Son → İlk)

### 2026-03-20: Delay Engine Rewrite — Sample-Continuous + Unified Engine

#### 🔧 Delay Motoru Yeniden Yazıldı (StreamSource.java)

**Problem:** Delay sistemi buffer-continuous çalışıyordu (buffer başında readPos snap). Her ~85ms'de bir yeni delay değerine sert geçiş → ritmik tıkırtı artefaktı.

1. **Sample-continuous delay interpolation**
   - Her buffer içinde delay `startDelay → endDelay` olarak 4096 sample boyunca linear interpolation yapıyor
   - Buffer sınırında readPos sürekliliği garanti: `readPos(yeni buffer sample 0) = readPos(eski buffer son sample) + 1`
   - Oluşan mikro-Doppler: ~%0.05 pitch shift (algılanamaz)

2. **Tiered crossfade sistemi (gereksiz crossfade'ler kaldırıldı)**
   - `|delayDiff| < 128 sample` → direkt Lagrange okuma, crossfade yok (Lagrange yeter)
   - `128-500 sample` → kısa equal-power crossfade (256 sample, ~5ms)
   - `> 500 sample` → teleport crossfade (512 sample, ~10ms)
   - **Kaldırılan:** Her buffer başındaki 5ms crossfade (tıkırtının ana kaynağı)

3. **Unified `generatePcmBlock()` motoru**
   - `computeBufferData()` ve `refillBufferInternal()` içindeki ~130 satırlık duplicate delay/DSP motoru silindi
   - Tek authoritative `generatePcmBlock(short[] output)` fonksiyonu oluşturuldu
   - Background thread ve main thread fallback her ikisi de aynı motoru kullanıyor
   - `refillBufferInternal()` 8 satıra düştü (thin wrapper)

4. **Pipeline latency azaltıldı**
   - `MAX_PRECOMPUTED`: 8 → 1 (pre-computed buffer kuyruğu)
   - Toplam pipeline: 12 buffer × ~85ms ≈ 1.0s → 5 buffer × ~85ms ≈ **425ms**
   - Phantom center image response belirgin şekilde hızlandı

#### 📁 Değiştirilen Dosyalar

| Dosya | Değişiklik |
|-------|------------|
| `sound/StreamSource.java` | Delay engine rewrite, unified `generatePcmBlock()`, tiered crossfade, `MAX_PRECOMPUTED=1` |

### 2026-03-13: Delay/Oturma İyileştirmeleri + Stadyum Reverb Tuning

#### 🎧 Hareket ederken “geç oturma / faz oturması” iyileştirmeleri

1. **Amplifier input gain ramp gecikmesi azaltıldı**
   - **Belirti:** Hoparlöre yaklaşınca şiddetin 2-3 saniye sonra oturması
   - **Çözüm:** `StreamSource` yapıcısında `smoothedInputGain` ilk frame'de gerçek `inputGain` ile senkronlandı

2. **Occlusion ve HF davranışı hızlandırıldı (tizlerin geç açılması azaltıldı)**
   - Listener hareket eşiği küçültüldü, yakında daha sık occlusion recalc yapılıyor
   - De-occlusion (açılma) tarafında smoothing hızlandırıldı
   - HF occlusion eğrisi de-occlusion ve yakın alanda daha lineer hale getirildi
   - Air absorption (gainHF) yakın alanda devre dışı bırakıldı ve daha geç/agresif olmayan bir eğriye çekildi

3. **Speed-of-sound delay smoothing denemeleri**
   - Amaç: hızlı çapraz yaklaşmalarda “comb/phase drift” hissini azaltmak
   - Not: Agresif snap/deadband denemeleri ses kalitesini bozduğu için geri alındı; doğal slew limiter davranışına dönüldü

#### 🏟️ Kapalı dev stadyum reverb iyileştirmeleri (VenuePreset / Tier6)

1. **Metalik tail azaltıldı, late tail güçlendirildi, early reflections dengelendi**
   - `AdvancedAcousticScanner.descriptorToPreset()` Tier6 ayarları güncellendi:
     - Early reflections biraz azaltıldı (`vReflGain` düşürüldü)
     - Late tail ağırlığı artırıldı (`lateReverbMultiplier` yükseltildi)
     - Kapalı dev mekanlarda diffusion/density alt sınırları yükseltildi
     - Kapalı dev mekanlarda HF decay oranı üstten hafif sınırlandı (parlak/metalik his azaltma)

2. **Uzak bölgede (stadyum ucu) azıcık room reverb kalsın (ghost minimum)**
   - `StreamSource.updatePhysics()` Room Reverb send hesabına çok düşük bir far-field floor eklendi
   - Floor, dry audibility (`finalDryGain`) ile bağlı tutuldu (duyulmayan kaynaklar ghost reverb basmasın)

### 2026-03-08: Otomatik Bağlantı + Performans Optimizasyonu + Bug Fix

#### 🐛 Çözülen Buglar

1. **İkinci Şarkı Hoparlör Kesilmesi (Kritik)**
   - **Belirti:** İlk şarkı tüm hoparlörlerde çalıyor, ikinci şarkıda sadece subwoofer'lar çalıyor
   - **Kök Sebep:** `StreamSource.cleanup()` OpenAL buffer'larını unqueue etmeden siliyordu → kaynak sızıntısı
   - **Çözüm:** `alSourceUnqueueBuffers()` eklendi, OpenAL error queue drain eklendi, `pcmBuffer` bellek sızıntısı düzeltildi

2. **Hoparlör Playback Desenkronizasyonu (Kritik)**
   - **Belirti:** Arka cluster'lar ~500ms gecikmeli çalıyordu
   - **Kök Sebep:** `alSourcePlay()` her hoparlör oluşturulurken hemen çağrılıyordu → ilk hoparlör son hoparlörden ~500ms önce çalmaya başlıyordu
   - **Çözüm:** `start()` metodu eklendi, tüm hoparlörler oluşturulduktan sonra aynı anda başlatılıyor

3. **Başlangıç Gecikme Hatası**
   - **Belirti:** Tüm hoparlörler 0ms gecikmeyle başlayıp yavaşça doğru gecikmeye ramp yapıyordu
   - **Kök Sebep:** `currentDistanceSnapshot` yapıcıda 0 olarak başlıyordu
   - **Çözüm:** Yapıcıda oyuncu mesafesi hemen hesaplanıyor

#### ✨ Yeni Özellikler

1. **Otomatik Hoparlör-Amfi Bağlantısı**
   - Hoparlör konulduğunda en yakın amfiye otomatik bağlanır (576 blok yarıçap)
   - Amfi konulduğunda çevredeki tüm hoparlörleri otomatik bağlar
   - Blok kırıldığında registry'den otomatik çıkarılır

2. **SpeakerRegistry (Global Pozisyon Kayıt Sistemi)**
   - `registry/SpeakerRegistry.java` **(YENİ)**
   - Hoparlör ve amfi pozisyonlarını bellekte tutar
   - O(N) lookup (eski: O(576³) = ~340M blok tarama → yeni: 50-60 karşılaştırma)
   - World load'da `readNbt()` ile mevcut bloklar da kaydedilir

#### ⚡ Performans Optimizasyonları

1. **Reusable Native Buffer**
   - `refillBufferInternal()`: Her çağrıda `memAlloc/memFree` → reusable buffer
   - Tick başına ~200 native alloc/free → 0

2. **Mesafe Bazlı Occlusion Throttling**
   - <30 blok: Her 5 tick | 30-100 blok: Her 20 tick | >100 blok: Her 40 tick
   - Uzak hoparlörlerin raycast yükü %75 azaldı

3. **Raycast Occlusion Clustering**
   - 8 blok içindeki hoparlörler occlusion değerini paylaşıyor
   - Raycast sayısı ~%80 azaldı

4. **Amfi GUI / Scan Hız Optimizasyonu**
   - Brute-force blok tarama → SpeakerRegistry lookup
   - Scan süresi: saniyeler → milisaniyeler

5. **Background Audio Thread (Stutter Fix)**
   - `ScheduledExecutorService` ile ayrı ses işleme thread'i (her 5ms)
   - Ağır hesaplamalar (Hermite interpolasyon + DSP) ana thread'den ayrıldı
   - Ana thread sadece hafif OpenAL upload yapıyor
   - `ConcurrentLinkedQueue` ile thread-safe veri aktarımı
   - Stutter ve CPU spike'lar tamamen ortadan kalktı

#### 📁 Değiştirilen Dosyalar

| Dosya | Değişiklik |
|-------|------------|
| `registry/SpeakerRegistry.java` | **YENİ** — Global pozisyon kayıt sistemi |
| `block/SpeakerBlock.java` | Auto-connect + registry register/unregister |
| `block/AmplifierBlock.java` | Auto-connect + registry register/unregister |
| `block/entity/AmplifierBlockEntity.java` | `scanForSpeakers()` registry kullanıyor + `readNbt()` register |
| `block/entity/SpeakerBlockEntity.java` | `readNbt()` register |
| `sound/StreamSource.java` | Reusable buffer, `start()` metodu, initial distance fix, buffer unqueue fix |
| `sound/AudioEngine.java` | Batch start, occlusion clustering, error drain, pcmBuffer leak fix |

### 2026-03-01: Gain Staging Fix + Crossover 100Hz
- **Pre-gain:** `0.5` → kaldırıldı → `0.75` (bas güçlü, headroom yeterli)
- **Speaker count scaling:** `1/√N` — çoklu speaker clipping'i önler
- **Gain clamp:** `4.0` → `1.5` — mixer taşması engellenir
- **Crossover:** Sub LP `80Hz` → `100Hz`, Mid HP `80Hz` → `100Hz`

### 2026-03-01: Mesafe Bazlı Reverb + ESC Fix + %15 Azaltma
- **Distance-based Room Reverb Send:** Yakın: 0.25 (dry baskın), Uzak: 0.70 (wet baskın) — gerçek akustik gibi
- **ESC Fix:** Pause'da aux slot gain=0 → reverb kuyruğu kesilir, resume'da geri gelir
- **%15 Reverb Azaltma:** Gain `0.53`, Reflections `0.34` (cap `0.40`), Late Reverb `0.26+r×0.41`

### 2026-03-01: Reverb %70 Müdahale + %40 Geri Açma (Net ~%42 Kesim)
- **Overall Gain:** `enclosure × 0.8` → `enclosure × 0.50` (min 0.10)
- **Reflections Gain:** çarpan `0.5` → `0.33`, üst limit `0.6` → `0.38`
- **Late Reverb Multiplier:** `0.4 + room×0.6` → `0.25 + room×0.40`
- **Volume Threshold:** `2500` → `10000` (daha büyük odalarda da decay damping çalışır)
- **Decay Max Clamp:** `15s` → `8s`
- **Hedef:** 42×30 kapalı odada reverb'ü daha gerçekçi seviyeye getirmek

### 2026-02-27: Gradle Build Denemesi
- Gradle build scripti çalıştırıldı
- `gradlew.bat` kullanıldı

### 2026-02-25: Live Gain Smoothing (Son Büyük Değişiklik)
- Power knob artık **gerçek zamanlı** ayarlanabilir (oyun sırasında)
- `updateAmplifierPower()` metodu eklendi (AudioEngine.java)
- `updateAmplifierInputGain()` metodu eklendi (AudioEngine.java)
- Power smoothing: `α=0.08` ile yumuşak geçiş (StreamSource.java)
- Input gain smoothing: `α=0.05` ile yumuşak geçiş
- **S2C_SYNC_POWER** paketi eklendi: server→client power sync
- **S2C_SYNC_INPUT_GAIN** paketi eklendi: server→client gain sync
- AmplifierScreen'e `updateSpeakerPower()` ve `updateInputGain()` metotları eklendi

### 2026-02-24 ~ 2026-02-25: Reverb Tuning & Smoothing
- Atmospheric reverb parametreleri ince ayarlandı
- Orta mesafede reverb baskınlığı azaltıldı
- Gain smoothing'deki zipper noise sorunu çözüldü
- Smoothing alpha değerleri optimize edildi

### 2026-02-23: Proje Analizi & Optimizasyon
- Mimari ve pipeline kapsamlı analiz edildi
- Potansiyel sadeleştirme ve optimizasyon noktaları belirlendi
- Kod değişikliği yapılmadan rapor verildi

### 2026-02-22: Bass Masking & Gain Tuning
- Bas frekans maskeleme yakın mesafede düzeltildi
- Bas sesinin "kafanın içinde" hissedilmesi geri getirildi
- Input Gain min değeri **0.1 → 0.0** olarak düşürüldü (clipping önleme)
- Input Gain aralığı: **0% — 300%** (0.0x — 3.0x)

### 2026-02-19: Multiplayer Analizi & Kod Analizi
- Multiplayer ses sorunları analiz edildi
- Ses motorunun her client'ta bağımsız çalıştığı doğrulandı
- Kapsamlı codebase analizi yapıldı (ses motoru, DSP, akustik tarama, malzeme özellikleri)

### 2026-02-18 (yaklaşık): Stabil Yedek Alınmış
- `src_backup_stable_18022026/` klasörü — 18 Şubat 2026 tarihli stabil yedek mevcut

---

## ⚡ Bilinen Sorunlar / Açık Konular

| # | Konu | Durum | Detay |
|---|------|-------|-------|
| 1 | Build status | ❓ Kontrol gerekli | Son başarılı build durumu doğrulanmalı |
| 2 | Speaker GUI texture | ⚠️ Eksik | `SpeakerScreen` amplifier_gui.png texture'ını kullanıyor (fallback) |
| 3 | Subwoofer/MidRange/LineArray blocks | ⚠️ Minimal | Sadece `SpeakerBlock` extends, özel davranış yok |
| 4 | Build error logları | ℹ️ Mevcut | `build_error.txt`, `error.txt` proje kökünde var |

---

## 🔧 Potansiyel Gelecek Çalışmaları

- [ ] Speaker tipine özel blok davranışları (SubwooferBlock/MidRangeBlock/LineArrayBlock)
- [ ] Speaker GUI için özel texture
- [ ] Daha fazla müzik parçası desteği
- [ ] Equalizer GUI (grafik EQ)
- [ ] Volume meter görselleştirmesi
- [ ] Build hataları düzeltme (varsa)
- [x] Performans optimizasyonu (büyük hoparlör setleri) ✅ 2026-03-08
- [ ] Türkçe dil desteği (lang dosyası)

---

> **Not:** Bu dosya her değişiklikte güncellenecektir. Yeni sohbetlerde bu dosyayı okuyarak projenin son durumunu anlayabilirsiniz.

---

## 🌐 İnternet Üzerinden Ses Çalma (URL Desteği) — 2026-03-05

> **Durum:** ✅ TAMAMLANDI — Speed up sorunu çözüldü, normal hızda çalıyor
> **Son Güncelleme:** 2026-03-05 04:21 (UTC+3)

### 📦 Eklenen Dosyalar ve Bağımlılıklar

| Dosya | Açıklama |
|-------|----------|
| `build.gradle` | LavaPlayer `2.2.6` + youtube-source plugin `1.18.0` eklendi |
| `InternetAudioLoader.java` **(YENİ)** | LavaPlayer entegrasyonu — URL çözümleme, PCM decode, mono downmix |
| `AudioEngine.java` | `playFromUrl()` + `playFromPcmData()` metotları eklendi |
| `ModMessages.java` | `C2S_PLAY_URL` + `S2C_PLAY_URL` paketleri eklendi |
| `AmplifierScreen.java` | URL text field + Play / Stop butonları eklendi |

### ✅ Tamamlanan Özellikler

1. LavaPlayer + youtube-source entegrasyonu — YouTube linkleri başarıyla çözümleniyor
2. PCM decode — Stereo → mono downmix + doğru sample rate hesaplama
3. Ağ paketleri — C2S/S2C URL paketleri çalışıyor
4. GUI — URL text field, Play/Stop butonları çalışıyor
5. DSP pipeline — Şarkı hoparlör tiplerine göre filtreleniyor
6. Ses çıkışı — Normal hızda, noise yok, PCM format doğru

### 🐛 Çözülen Bug: Speed Up Sorunu

**Belirti:** YouTube'dan çalan şarkılar ~1.5-2x hızlı çalıyordu. OGG test track doğru hızdaydı.

**Kök Sebep:** LavaPlayer, `COMMON_PCM_S16_BE` (48kHz) olarak konfigüre edilse bile, kaynak sesi 48kHz'e **resample YAPMIYOR**. Frame'ler kaynağın orijinal sample rate'inde (genellikle ~22-24kHz) çıkıyor, ama biz hepsini 48000 Hz olarak etiketliyorduk → 2x hızlı çalıyordu.

**Çözüm:** Track'in bilinen süresinden gerçek sample rate hesaplama:
```java
int actualSampleRate = (int) Math.round(monoSamples / (durationMs / 1000.0));
```

**Tanı Yöntemi:** Decode edilen PCM veriyi WAV dosyasına kaydedip bağımsız test → WAV da hızlıydı → sorun streaming engine'de değil, PCM verisindeydi.

### 📁 Dosya Değişiklikleri

```
Yeni: sound/InternetAudioLoader.java (270 satır)
Mod:  sound/AudioEngine.java (+playFromUrl, +playFromPcmData — OGG yoluyla aynı pipeline)
Mod:  sound/StreamSource.java (temizlendi)
Mod:  network/ModMessages.java (+C2S_PLAY_URL, +S2C_PLAY_URL)
Mod:  client/screen/AmplifierScreen.java (+URL field, +Play/Stop)
Mod:  build.gradle (+lavaplayer, +youtube-source)
```

---

## 📍 Son Oturum Özeti — 2026-03-20 22:58

> **Son Güncelleme:** 2026-05-10 06:10 (UTC+3)

## 📍 Son Oturum Özeti — 2026-05-19 22:50

> **Son Güncelleme:** 2026-05-19 22:50 (UTC+3)

### 2026-05-19: Cluster Fizik Bugları — Donuk Cluster / Tiz Kaybı Düzeltmeleri

#### 🐛 Bulunan ve Çözülen Buglar (4 adet)

1. **`speakerCount` Global Hesaplanıyordu (Kritik)**
   - **Belirti:** Bazı cluster'lar "donuk" davranıyor, near-field aktive olmuyor, ses karakteri diğer cluster'lardan farklı
   - **Kök Sebep:** `buildSpeakerMetas()` içinde `count = countSub/countMid/countLine` TÜM cluster'lardaki toplam hoparlör sayısını veriyordu. 4 cluster × 8 line array = `speakerCount = 32` → `arrayMultiplier = √32 = 5.66x` → `effectiveRefDist` ve `dynamicMaxDist` aşırı şişiriliyordu
   - **Çözüm:** Per-cluster per-type sayım: `clusterCountSub`, `clusterCountMid`, `clusterCountLine` ayrı ayrı sayılıyor

2. **`clusterSize` Tüm Hoparlör Tiplerini Sayıyordu (Kritik)**
   - **Belirti:** Line array dikey beam çok dar, tizler az geliyor, ses yüze vurmuyor
   - **Kök Sebep:** `clusterSize = cluster.size()` sub+mid+line hepsini sayıyordu. 2 line + 4 sub + 4 mid = `clusterSize=10` → `vtExp = 1.5 + √10 × 0.34 = 2.57` (lazer beam). Doğru değer: `clusterSize=2` → `vtExp = 1.98`
   - **Çözüm:** `typeClusterSize` olarak sadece aynı tip hoparlör sayısı

3. **Unloaded Chunk'larda Oklüzyon Kalıcı Takılma**
   - **Belirti:** Duvar arkasından çıkınca ses karanlık kalıyor, kırıp koyunca düzeliyor
   - **Kök Sebep:** Raycast yolunda unloaded chunk varsa `transmissionProduct = -1.0` (abort) → `targetOcclusion` hiç güncellenmiyordu, eski (muffled) değerde kalıyordu
   - **Çözüm:** `lastSuccessfulOcclusionTick` ile timeout mekanizması: 100 tick (5s) boyunca başarılı raycast yoksa, `targetOcclusion` yavaşça 1.0'a (açık hava) doğru decay ediliyor

4. **Physics Distance Cluster Sync ile Karışıyordu**
   - **Belirti:** Follower hoparlörlerin attenuation/near-field/directivity hesabı tutarsız
   - **Kök Sebep:** `currentDistanceSnapshot` hem delay sync hem physics için kullanılıyordu. Cluster sync (satır 427) leader'ın mesafesiyle üst yazıyordu → sonraki tick'te physics yanlış mesafe okuyabiliyordu
   - **Çözüm:** Yeni `physicsDistanceSnapshot` field: audio thread tarafından yazılır, cluster sync tarafından ASLA dokunulmaz, tüm fizik hesapları bunu kullanır

5. **`playTrack()` Tilt Uygulamıyordu (Ek fix)**
   - **Belirti:** OGG playback'te directivity yanlış (URL playback etkilenmiyor)
   - **Kök Sebep:** `playTrack()` StreamSource'a ham `vec.getX(), vec.getY(), vec.getZ()` geçiyordu (tilt yok), `playFromPcmData()` ise doğru tilt-adjusted vektör geçiyordu
   - **Çözüm:** `playTrack()` da `vec.getX() * cosT, sinT, vec.getZ() * cosT` kullanıyor

#### 📁 Değiştirilen Dosyalar

| Dosya | Değişiklik |
|-------|------------|
| `sound/AudioEngine.java` | Per-cluster per-type `speakerCount` ve `clusterSize`, `playTrack()` tilt fix |
| `sound/StreamSource.java` | `physicsDistanceSnapshot` field, stuck occlusion timeout, `lastSuccessfulOcclusionTick` |

---

## 📍 Son Oturum Özeti — 2026-05-10 06:10

> Yarın buradan devam edeceksin. Early Reflection sistemi pipeline olarak çalışıyor (ses geliyor), gain/filtre tuning yapılacak.

### Mevcut Durum
- ✅ Early Reflection pipeline çalışıyor (nükleer testte %80 gain ile ses geliyor)
- ⚠️ Gain ve lowpass filtre tuning gerekiyor (şu an test modunda: gain=0.80, filtre bypass)
- ✅ Global Master Clock mimarisi stabil
- ✅ Dikey directivity tuning tamamlandı
- ✅ Oklüzyon HF ayarları tamamlandı

### Yarın yapılacak (Early Reflection Tuning)
- [ ] Gain formülünü kalibre et (0.80 nükleer test → fiziksel model)
- [ ] Lowpass filtreyi geri aç (lpAlpha=1.0 bypass → uygun cutoff)
- [ ] Diagnostic logları temizle
- [ ] Açık hava vs kapalı alan A/B testi
- [ ] Reverb + reflection ayrımını netleştir

---

### 2026-05-10: Early Reflection Engine — Tam İmplementasyon

#### 🎯 Mimari Evrim (3 aşama)

**Aşama 1: Source-Based (Terk Edildi)**
- 48 ayrı OpenAL source ile pooled reflection sistemi
- Sorun: "Ses çoğaltılmış hoparlör" algısı — beyin farklı kaynaklar duyuyordu
- ❌ Kaldırıldı

**Aşama 2: Tap-Based — Pure Audio Path Model (Aktif)**
- Sıfır ek OpenAL source
- Reflection'lar doğrudan `StreamSource.generatePcmBlock()` içinde PCM buffer'a karıştırılıyor
- Her tap = `streamBuffer.getSampleLagrange(directReadPos - delaySamples) * gain`
- Tek source → tek HRTF → beyin: "oda var, ses zenginleşiyor"
- Faz koheransı matematiksel olarak garanti (aynı timeline)

#### 📁 Yeni Dosya
| Dosya | Boyut | Açıklama |
|-------|-------|----------|
| `sound/EarlyReflectionEngine.java` | ~270 satır | Tap-based early reflection sistemi |

#### 📁 Değiştirilen Dosyalar
| Dosya | Değişiklik |
|-------|------------|
| `StreamSource.java` | `generatePcmBlock()` içine reflection tap mixing eklendi, `getStreamBuffer()` / `getOutputCursor()` accessor'ları |
| `AudioEngine.java` | `EarlyReflectionEngine` field, `getReflectionEngine()` accessor, tick'te raycast update, yaşam döngüsü entegrasyonu |
| `AdvancedAcousticScanner.java` | `getAbsorptionForReflection()` public accessor eklendi |

#### 🔧 EarlyReflectionEngine Özellikleri
- **6 ray per source:** ±X (duvarlar), ±Y (tavan/zemin), ±Z (ön/arka)
- **Dirty flag system:** Oyuncu 1+ blok hareket → anında raycast, yoksa 0.5s interval
- **Micro diffusion jitter:** ±3ms delay + ±5% gain (deterministik seed ile tutarlı)
- **Reflection → Listener oklüzyon:** Hit noktasından listener'a raycast (2+ blok = engellenir)
- **Minimum spatial separation:** Hit noktası source'a 3 bloktan yakınsa → skip
- **Hard minimum delay:** 12ms (comb filtering engeli)
- **Ray-type dependent delay ranges:** Floor: 15-30ms, Walls: 20-60ms, Ceiling: 30-80ms
- **Indoor/Outdoor adaptive:** `venueEnclosure` değerine göre 1-6 tap, %5-56 gain scale
- **ConcurrentHashMap:** Thread-safe tap storage (main thread yazıyor, audio thread okuyor)
- **Priority scoring:** `gain×0.5 + proximity×0.3 + bounce×0.2`

#### 🐛 Bulunan & Çözülen Buglar
1. **Read Position Hatası:** `reflReadPos = bufferStartSample - tapDelay` → **YANLIŞ** (propagation delay hesaba katılmamıştı). Düzeltme: `reflReadPos = (bufferStartSample - currentDelay) - tapDelay`
2. **lpAlpha = 0.10 (Sinyal Öldürme):** `airLoss = 100Hz/blok` → lowpass sinyalin %90'ını kesiyordu. Şu an bypass modunda, tuning gerekiyor.
3. **Gain zinciri çok agresif:** `1/r² × reflectivity × 0.60 × encScale(0.03-0.22)` → sonuç %1-2 = duyulamaz. Şu an test için sabit 0.80.

---

### 2026-05-09: Global Master Clock + Directivity + Occlusion Tuning

#### 🕐 Global Master Clock Mimarisi
- **Problem:** Buffer-based `samplesWritten` counter → inter-source drift, faz inkoheransı, 200+ hoparlörde crackling
- **Çözüm:** `AudioEngine.globalSampleTime = nanoTime × sampleRate`. Her `StreamSource`: `readPosition = globalSampleTime - propagationDelaySamples`
- **Sonuç:** 200+ hoparlör dizisi mükemmel faz koheransı

#### 🔊 Dikey Directivity (Line Array) Tuning
- **Problem:** 3 line array dikey olarak "lazer ışını" etkisi — 1 blok kayınca tizler tamamen kayboluyor
- **Çözüm:** `vtExp = 1.5 + Math.sqrt(clusterSize) * 0.4` — dikey dispersiyon genişletildi, hoparlör sayısına bağlı dinamik
- Eski: `vtExp = 2.0 + clusterSize * 0.5` → çok keskin

#### 🧱 Oklüzyon HF Tuning
- Duvar arkası "treble floor" %10 → %1 (tam tiz karartma)
- Malzeme geçirgenliği: base wall `0.15` → `0.35` (tek blok engel gerçekçi)
- Rolloff eğrileri yumuşatıldı

---

## ⚡ Bilinen Sorunlar / Açık Konular

| # | Konu | Durum | Detay |
|---|------|-------|-------|
| 1 | Early Reflection gain tuning | ⚠️ Devam ediyor | Şu an nükleer test modunda (gain=0.80, filtre bypass). Fiziksel model kalibrasyonu gerekiyor |
| 2 | Early Reflection lowpass | ⚠️ Bypass | lpAlpha=1.0 (filtre kapalı). Uygun cutoff hesaplanacak |
| 3 | Diagnostic loglar | ℹ️ Aktif | `reflectionLogDone` debug logları temizlenecek |
| 4 | Speaker GUI texture | ⚠️ Eksik | `SpeakerScreen` amplifier_gui.png texture'ını kullanıyor (fallback) |

