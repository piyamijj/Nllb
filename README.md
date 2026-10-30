# Sesli Tercüman — NLLB

Türkçe ⇄ Farsça, **tamamen çevrimdışı** sesli tercüman (Android).

Bu depo, **NLLB-200 distilled 600M (int8)** çeviri motorunu kullanan sürümdür.
Kardeş depo: [M2m](https://github.com/piyamijj/M2m)

## İndir

Son APK: [Releases → latest](https://github.com/piyamijj/Nllb/releases/latest)

Doğrudan bağlantı:

```
https://github.com/piyamijj/Nllb/releases/download/latest/SesliTercuman-NLLB-v0.1.0.apk
```

## Durum

| Bileşen | Durum |
|---|---|
| Arayüz (Compose) | ✅ |
| Mikrofon izni | ✅ |
| Konuşma tanıma (Whisper base) | ⏳ Faz 1 |
| Çeviri (NLLB) | ⏳ Faz 2 |
| Seslendirme (Piper) | ⏳ Faz 3 |

## Mimari (hedef)

```
Mikrofon → VAD → Whisper base (int8) → NLLB (int8, ONNX) → Piper TTS
```

Hepsi cihazda çalışır; uygulama internete ihtiyaç duymaz.

## Derleme

APK, GitHub Actions ile otomatik üretilir (`.github/workflows/android.yml`).
Her `main` push'unda yeni APK `latest` sürümüne eklenir.

Yerelde:

```bash
./gradlew :app:assembleRelease
```

Gereksinimler: JDK 17, Android SDK 35.

## Lisans

Uygulama kodu MIT. NLLB-200 model ağırlıkları **CC-BY-NC-4.0** (ticari olmayan kullanım).
