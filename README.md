# Maç Tahmin Pro

Dünya genelindeki **gerçek futbol maçlarını** takip eden ve kazanma oranlarını analiz eden Android uygulaması.

## Veri Kaynakları (Gerçek Veri)

| Kaynak | Veri |
|--------|------|
| [TheSportsDB](https://www.thesportsdb.com/) | Gerçek maçlar, skorlar, takım formları |
| [Open-Meteo](https://open-meteo.com/) | Stadyum konumuna göre gerçek hava durumu |

## Özellikler

- **Günlük Maçlar**: Bugünün gerçek maçları (Premier League, La Liga, Süper Lig, MLS vb.)
- **Canlı Maçlar**: Devam eden maçlar (60 sn otomatik yenileme)
- **Kazanma Oranı**: Ev sahibi / Beraberlik / Deplasman yüzdeleri
- **Hava Durumu Etkisi**: Yağış, rüzgar, sıcaklık analizi
- **Duygusal Durum**: Takımların son 5 gerçek maç sonucuna göre moral analizi
- **Detaylı Analiz**: Her maç için faktör bazlı açıklama

## APK İndirme

GitHub Actions > Build APK workflow > `match-predictor-apk` artifact'ından indirin.

Veya yerel derleme:
```bash
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Kurulum

1. APK'yı Android telefonunuza indirin
2. "Bilinmeyen kaynaklardan yükleme" iznini verin
3. APK'yı kurun
4. İnternet bağlantısı gerekli (gerçek veri çekmek için)
