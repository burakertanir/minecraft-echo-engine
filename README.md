<div align="center">
  <h1>🔊 ECHO Sound Engine</h1>
  <p><b>Dünyanın En Gelişmiş Minecraft Akustik ve Ses İşleme Modu</b></p>
  <p>
    <a href="#özellikler">Özellikler</a> • 
    <a href="#nasıl-çalışır">Nasıl Çalışır?</a> • 
    <a href="#teknik-detaylar">Teknik Detaylar</a>
  </p>
</div>

---

## 🎵 Mod Hakkında

**ECHO Sound Engine**, standart Minecraft ses motorunun sınırlarını tamamen yok eden, baştan aşağı özel yazılmış devasa bir ses ve DSP (Dijital Sinyal İşleme) altyapısıdır. Bu mod sadece oyuna bir "Hoparlör" eklemekle kalmaz; gerçek hayattaki **fiziksel ses yayılımını**, **oda akustiğini** ve **materyal yansımalarını** birebir Minecraft evrenine taşır.

Bu mod alanında iddialıdır: Büyük ihtimalle **dünyada bu seviyede bir akustik hesaplama ve canlı yayın entegrasyonu sunan tek Minecraft modudur.**

---

## 🚀 En Vurucu Özellikler

### 🧮 26-Ray Temporal Slicing & Sabine Akustiği
Minecraft'ın bloklu dünyasında gerçek zamanlı **Işın İzlemeli (Ray-Traced) Ses!**
Mod, dinleyicinin etrafına "Spherical Fibonacci" yöntemiyle 26 farklı akustik ışın (ray) gönderir. Bu ışınlar odanın geometrisini çıkarır ve meşhur **Sabine Denklemi**'ni kullanarak bulunduğunuz odanın akustik yankı (Reverb) değerlerini saniyesinde, sıfır gecikme ile hesaplar.

### 🧱 Blok Bazlı Gerçekçi Ses Emilimi (Material Absorption)
Odanızın neyden yapıldığı sesi doğrudan etkiler. Tıpkı gerçek hayattaki gibi:
* **Taş, Obsidyen, Cam:** Sesi yansıtır, güçlü yankı (Reverb) yapar.
* **Yün, Odun, Sünger:** Sesi emer (absorbe eder), yankıyı öldürür ve sesi boğar.
Kendi stüdyonuzu akustik süngerlerle (veya yünlerle) yalıtabilirsiniz!

### 🌐 LavaPlayer Entegrasyonu ile İnternet Yayını
Sadece bilgisayarınızdaki veya oyundaki sesleri değil, **internetteki sesleri doğrudan oyun içine aktarır.**
* **YouTube**, **SoundCloud** ve doğrudan **HTTP(S)** linklerinden canlı müzik veya radyo çalabilme.
* Müzik sunucudan istemciye milisaniyelik senkronizasyonlarla aktarılır.
* Tamamen ham PCM (Pulse-Code Modulation) sinyaline dönüştürülüp modun özel OpenAL motoruna beslenir.

### 🎛️ Hardcore DSP (Dijital Sinyal İşleme) ve EFX/EAX
* **OpenAL Soft HRTF:** Kulaklık kullananlar için 3 Boyutlu (Binaural) kusursuz yönsel ses deneyimi. Sesi sadece sağdan/soldan değil, yukarıdan veya aşağıdan da hissedersiniz.
* **EAX Reverb & Dinamik Tıkanma (Occlusion):** Ses kaynağı ile aranıza bir duvar girdiğinde ses aniden kesilmez, frekansları boğuklaşarak (low-pass filter) size ulaşmaya devam eder.

### 🔊 Dinamik Hoparlör Kümeleme (Speaker Clustering)
Birden fazla hoparlörü yan yana koyduğunuzda mod bunu algılar ve bir **"Line Array" (Konser Hoparlör Dizilimi)** olarak çalıştırır. Sesi tek bir kaynaktan değil, birleştirilmiş akustik bir güç alanından duyarsınız.

### 📊 Gerçek Zamanlı Peak Meter (Ses Dalga Analizi)
Çalınan müziğin frekans ve şiddet analizlerini (Peak Metering) saniyesi saniyesine yaparak, ekranınızdaki GUI'lere (Amplifikatör ekranlarına) veya oyun içi led bloklarına veri aktarır.

---

## ⚙️ Nasıl Çalışır?

ECHO Sound Engine, oyunun standart arka plan döngüsünden (main thread) bağımsız olarak, kendi `ScheduledExecutorService` altyapısında eşzamanlı (async) olarak çalışır.

1. **Ses Kaynağı:** LavaPlayer veya yerel OGG dosyası üzerinden ses yakalanır (`InternetAudioLoader` / `OggDecoder`).
2. **Bufferlama:** Sinyal `AudioStreamBuffer` içine alınarak paket kayıpları (lag) engellenir.
3. **Tarama:** `AdvancedAcousticScanner` saniyede yüzlerce hesaplama yaparak odanızın hacmini (Volume), yüzey alanını (Surface Area) ve ortalama ses emilim katsayısını hesaplar.
4. **DSP Boru Hattı:** `StreamDSPPipeline` ve `AudioEngine` sesin üzerine HRTF, Occlusion (Duvar arkası boğuklaşma) ve hesaplanan Reverb efektlerini uygular.
5. **Çıkış:** Kusursuz, stüdyo kalitesinde ve Minecraft ortamıyla fiziksel olarak %100 uyumlu bir ses duyarsınız!

---

## 🛠️ Teknik Altyapı ve Bağımlılıklar

* **Platform:** Fabric Modloader
* **Ses Kütüphaneleri:** OpenAL (AL10, AL11, EXTEfx), LavaPlayer, JLayer.
* **Network:** Özel C2S ve S2C (Client-to-Server, Server-to-Client) paket yapısı, boyutlar arası (Dimension) stabil senkronizasyon.

---

## 💡 Kurulum ve Kullanım

*(Bu bölüme GitHub yayınından sonra indirme linkleri ve oyun içi komut/blok kullanımları eklenebilir)*

---

<p align="center">
  <i>ECHO Sound Engine - Sesin fiziksel sınırlarını aşın.</i>
</p>
