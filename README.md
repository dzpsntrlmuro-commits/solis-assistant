# Hello Kittiy

Pembe Hello Kitty tarzı Android **ana ekran (launcher)** uygulaması.

## Ne yapar?

- Telefonun ana ekranını tamamen pembe / kedi temalı arayüze çevirir
- Uygulama simgelerini pembe çerçeve + fiyonk ile gösterir
- Yukarıdan çekince **pembe bildirim paneli** açılır
- Aynı panelde **ses (medya / zil / alarm)** kontrolleri vardır
- Dock: Telefon, Mesaj, Kamera, Ayarlar

## Kurulum

1. `HelloKittiy.apk` dosyasını telefona atın
2. Bilinmeyen kaynaklardan kurulum iznini açın ve APK’yı kurun
3. Ana ekran tuşuna basın → **Hello Kittiy** seçin → **Her zaman**
4. Bildirimleri görmek için: paneldeki izin yazısına dokunun → Hello Kittiy’i açın

## Derleme

```bash
export JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

APK yolu: `app/build/outputs/apk/debug/app-debug.apk`
