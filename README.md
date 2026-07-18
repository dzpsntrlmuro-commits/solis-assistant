# SafTube

Reklamsız YouTube arama ve izleme uygulaması (Android).

## Özellikler

- YouTube video arama
- Trend / keşfet listesi (TR)
- Piped API üzerinden **reklamsız** video akışı
- YouTube reklam alanlarını engelleyen filtre
- ExoPlayer ile HLS / DASH / progressive oynatma

## Kurulum

1. Android Studio ile projeyi açın
2. Bir cihaz veya emülatör seçin
3. Run ile `com.saftube.app` uygulamasını çalıştırın

Debug APK:

```bash
./gradlew assembleDebug
```

APK yolu: `app/build/outputs/apk/debug/app-debug.apk`

## Not

Uygulama YouTube’un resmi uygulaması değildir. İçerik Piped açık kaynak ön yüzleri üzerinden alınır; reklam enjeksiyonu yapılmaz.
