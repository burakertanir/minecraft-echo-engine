# ECHO Sound Engine

ECHO Sound Engine, Minecraft 1.20.1 için geliştirilmiş Fabric tabanlı bir fiziksel
hoparlör ve uzamsal ses modudur. Hoparlör dizilimlerini, yönlülüğü, mesafeyi,
duvar arkasındaki frekans kaybını ve mekân akustiğini özel bir OpenAL ses motorunda
işler.

## Gereksinimler

- Minecraft 1.20.1
- Fabric Loader 0.15.7 veya üzeri
- Fabric API
- Java 17 veya üzeri

Multiplayer kullanımında modun hem sunucuda hem de bağlanan istemcilerde kurulu
olması gerekir.

## Özellikler

### Hoparlör sistemi

- Subwoofer, Studio Monitor ve Line Array Module blokları
- Yakın hoparlörleri fiziksel emitter grupları hâlinde birleştiren kümeleme
- Büyük dizilimlerde güç ve menzil ölçekleme
- Line Array için yatay/dikey yönlülük ve ayarlanabilir dikey açı
- `BOTH`, `LEFT` ve `RIGHT` kanal seçimi
- Hoparlör başına 0-30 ms sample shift
- Sub, mid, line ve normal kanallar için bağımsız mixer ve parametrik EQ

### Fiziksel ses motoru

- OpenAL tabanlı uzamsal kaynaklar ve HRTF uyumlu konumlandırma
- Mesafe zayıflaması ve sesin yayılma süresi
- Duvar kalınlığına ve blok geçirgenliğine bağlı occlusion
- Yüksek frekans yönlülüğü ve hava emilimi
- Hoparlör tipine ve gücüne bağlı kontrollü harmonikler
- Pause, seek ve resume sırasında zaman/propagation senkronizasyonu

### Mekân akustiği

- Her prob için 1000 yönlü Spherical Fibonacci ışın taraması
- Blok malzemelerine göre yansıma ve emilim hesabı
- Hacim, yüzey alanı, açıklık ve enclosure analizi
- Sabine tabanlı decay hesabı ve Tier 1-10 venue profilleri
- Açık ve yarı açık mekânlarda dinamik reverb azaltma
- Birden fazla emitter grubu ve iki fiziksel room bus arasında yumuşak geçiş
- Sound Tablet üzerinde reverb heatmap ve acoustic zone görünümü

### Oynatma ve dayanıklılık

- Yerel OGG/PCM ve internet ses kaynakları
- YouTube, SoundCloud ve doğrudan HTTP(S) kaynakları için LavaPlayer entegrasyonu
- Birden fazla session ve multiplayer senkronizasyonu
- Ses cihazı çıkarılıp takıldığında oyun yeniden başlatılmadan motoru kurtarma
- Replay Mod için opsiyonel pause/resume ve cleanup entegrasyonu
- Eski decode görevlerini ve stale callback'leri engelleyen request/cancellation sistemi

## Kurulum

1. Fabric Loader ve Fabric API'yi Minecraft 1.20.1 için kurun.
2. Dağıtım JAR'ını `.minecraft/mods` klasörüne yerleştirin.
3. Oyunu Fabric profiliyle başlatın.

İnternet ses kütüphaneleri mod JAR'ına gömülüdür; ayrıca LavaPlayer kurmanız
gerekmez.

## Kullanım

1. Creative envanterindeki `ECHO Sound Engine` sekmesinden hoparlörleri ve
   `Sound Tablet` öğesini alın.
2. Subwoofer, Studio Monitor ve Line Array bloklarını istediğiniz düzende
   yerleştirin.
3. Bir hoparlöre sağ tıklayarak kanal, sample shift ve desteklenen bloklarda
   dikey açı ayarlarını yapın.
4. Sound Tablet'i kullanarak yakındaki kurulumları açın.
5. Bir parça adı arayın veya desteklenen bir URL yapıştırın; güç, input gain,
   mixer ve EQ ayarlarını yaptıktan sonra oynatmayı başlatın.

Tablet, oyuncunun 500 blok çevresindeki uygun hoparlörleri tarar. Heatmap görünümü
gerçek venue taramasında kullanılan akustik sonuçları gösterir.

## Canlı tuning

İlk çalıştırmada aşağıdaki dosya otomatik oluşturulur:

```text
.minecraft/config/audiophilecraft_tuning.json
```

Dosya oyun açıkken düzenlenebilir ve yaklaşık bir saniye içinde yeniden yüklenir.
Config migration sistemi eski varsayılanları günceller, kullanıcının değiştirdiği
değerleri korur ve sürüm geçişinde `.bak` yedeği oluşturur.

## Geliştirme

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat test
.\gradlew.bat build
```

Test paketi; stream buffer sınırlarını, speaker gruplamayı, reverb profil
benzerliğini, venue tier eşiklerini ve config migration/yedek davranışını kapsar.
Detaylı sahiplik ve lifecycle açıklamaları [ARCHITECTURE.md](ARCHITECTURE.md)
dosyasındadır.

## Lisans

ECHO Sound Engine, [MIT Lisansı](LICENSE) ile yayımlanır.

Copyright (c) 2026 Burak
