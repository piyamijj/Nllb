# Sesli Tercüman — NLLB

Türkçe ⇄ Farsça, **tamamen çevrimdışı** sesli tercüman (Android).

Bu depo, **NLLB-200 distilled 600M (int8)** çeviri motorunu kullanan sürümdür.
Kardeş depo: [M2m](https://github.com/piyamijj/M2m)

## İndir

Son APK: [Releases → latest](https://github.com/piyamijj/Nllb/releases/latest)

Doğrudan bağlantı:

```
https://github.com/piyamijj/Nllb/releases/download/latest/SesliTercuman-NLLB-v0.2.0.apk
```

> APK ~190 MB. Model dosyaları (Whisper + VAD) paketin içinde gömülüdür;
> kurulumdan sonra **internet gerekmez**. Kurulum için ~400 MB boş alan ayır.

## Bu sürümde (Faz 1)

- Sürekli dinleme: "Başlat"a bir kez bas, mikrofon açık kalır
- **Silero VAD** ile cümle bölme (sessizlik + azami 15 sn)
- **Whisper base int8** ile Türkçe/Farsça yazıya dökme
- **Otomatik dil algılama** (Whisper LID) + isteğe bağlı TR/FA kilidi
- Sohbet gibi akan kayıt ekranı, dil rozeti ve çözümleme süresi

Çeviri (Faz 2) ve seslendirme (Faz 3) henüz yok.

## Kullanım

> **İlk geçiş:** eski v0.1.0 sürümünü kaldır (imza değişti). Bundan sonraki
> güncellemeler doğrudan üzerine kurulur — APK'lar depodaki sabit anahtarla imzalanır.

1. APK'yı kur (bilinmeyen kaynaklara izin vermen gerekebilir)
2. Uygulamayı aç, **Başlat**'a bas, mikrofon iznini ver
3. Normal konuş — cümle bitince yazı ekranda belirir
4. Dil yanlış algılanırsa üstteki **Türkçe** / **Farsça** düğmesiyle sabitle
5. Uygulamadan çıkınca veya ekran kapanınca dinleme durur

İlk "Başlat" modeli RAM'e yükler (birkaç saniye). Sonraki başlatmalar hızlıdır.

## Durum

| Bileşen | Durum |
|---|---|
| Arayüz (Compose) | ✅ |
| Mikrofon izni | ✅ |
| Konuşma tanıma (Whisper base int8) | ✅ Faz 1 |
| VAD (Silero) | ✅ Faz 1 |
| Dil algılama (TR/FA) | ✅ Faz 1 |
| Konuşmacı tanıma (sen / karşı) | ⏳ Faz 1.5 |
| Çeviri (NLLB-200 600M int8) | ⏳ Faz 2 |
| Seslendirme (Piper) | ⏳ Faz 3 |
| Yarı-dubleks konuşma döngüsü | ⏳ Faz 3 |

## Gereksinimler

- Android 8.0+ (API 26), `arm64-v8a`
- 4 GB+ RAM (8 GB önerilir)
- ~400 MB boş depolama

## Mimari (hedef)

```
Mikrofon → Silero VAD → Whisper base (int8) → NLLB (int8, ONNX) → Piper TTS
```

Hepsi cihazda çalışır; uygulama internete ihtiyaç duymaz ve `INTERNET` izni istemez.

## Derleme

APK, GitHub Actions ile otomatik üretilir (`.github/workflows/android.yml`).
Her `main` push'unda yeni APK `latest` sürümüne eklenir.

CI şunları indirir (önbelleğe alınır):

- `sherpa-onnx-2.0.1.aar` → `app/libs/`
- Whisper base int8 + tokens → `app/src/main/assets/models/whisper/`
- `silero_vad.onnx` → `app/src/main/assets/models/`

Yerelde derlemek için aynı dosyaları indirip aynı yerlere koy, sonra:

```bash
./gradlew :app:assembleRelease
```

Gereksinimler: JDK 17, Android SDK 35.

## Lisans

Uygulama kodu MIT. NLLB-200 model ağırlıkları **CC-BY-NC-4.0** (ticari olmayan kullanım).
Whisper MIT, Silero VAD MIT, sherpa-onnx Apache-2.0.
