# Sesli Tercüman — NLLB

Türkçe ⇄ Farsça, **tamamen çevrimdışı** sesli tercüman (Android).

Bu depo, **NLLB-200 distilled 600M (int8)** çeviri motorunu kullanan sürümdür.
Kardeş depo: [M2m](https://github.com/piyamijj/M2m)

## İndir

Son APK: [Releases → latest](https://github.com/piyamijj/Nllb/releases/latest)

Doğrudan bağlantı:

```
https://github.com/piyamijj/Nllb/releases/download/latest/SesliTercuman-NLLB-v0.3.0.apk
```

> APK ~1.2 GB. Tüm modeller paketin içinde gömülüdür; kurulumdan sonra
> **internet gerekmez**. Kurulum için ~4 GB boş alan ayır. İlk açılışta
> çeviri modeli hazırlanır (tek seferlik, ~1 dk sürebilir).

## Bu sürümde (Faz 3)

- Sürekli dinleme: "Başlat"a bir kez bas, mikrofon açık kalır
- **Silero VAD** ile cümle bölme (sessizlik + azami 15 sn)
- **Whisper base int8** ile Türkçe/Farsça yazıya dökme
- **Otomatik dil algılama** (yalnızca TR/FA arası) + isteğe bağlı dil kilidi
- **NLLB-200 600M int8** ile çeviri (diğer dile otomatik)
- **Piper** ile çeviriyi seslendirme (tr: dfki, fa: ganji)
- Yarı-dubleks konuşma: seslendirme bitmeden dinleme kapalı tutulur
- **Konuşmacı tanıma**: "Ben'i tanıt" / "Karşı'yı tanıt" ile 4 sn kayıt,
  cümleler Ben/Karşı rozetiyle etiketlenir
- Sohbet gibi akan kayıt ekranı, dil rozeti ve süre bilgisi

## Kullanım

1. APK'yı kur (bilinmeyen kaynaklara izin vermen gerekebilir)
2. İstersen önce **Ben'i tanıt** / **Karşı'yı tanıt** ile sesleri kaydet
3. **Başlat**'a bas, mikrofon iznini ver
4. Normal konuş — cümle bitince yazı, çevirisi ve seslendirmesi gelir
5. Dil yanlış algılanırsa üstteki **Türkçe** / **Farsça** düğmesiyle sabitle
6. Uygulamadan çıkınca veya ekran kapanınca dinleme durur

İlk "Başlat" modelleri RAM'e yükler (biraz sürer). Sonraki başlatmalar hızlıdır.

## Durum

| Bileşen | Durum |
|---|---|
| Arayüz (Compose) | ✅ |
| Mikrofon izni | ✅ |
| Konuşma tanıma (Whisper base int8) | ✅ Faz 1 |
| VAD (Silero) | ✅ Faz 1 |
| Dil algılama (TR/FA) | ✅ Faz 1.1 |
| Konuşmacı tanıma (Ben / Karşı) | ✅ Faz 1.5 |
| Çeviri (NLLB-200 600M int8) | ✅ Faz 2 |
| Seslendirme (Piper) | ✅ Faz 3 |
| Yarı-dubleks konuşma döngüsü | ✅ Faz 3 |

## Gereksinimler

- Android 8.0+ (API 26), `arm64-v8a`
- 6 GB+ RAM (8 GB önerilir)
- ~4 GB boş depolama

## Mimari

```
Mikrofon → Silero VAD → Whisper base (int8) → NLLB (int8, ONNX) → Piper TTS
                 \-> CAM++ konuşmacı tanıma (kayıtlıysa etiketler)
```

Hepsi cihazda çalışır; uygulama internete ihtiyaç duymaz ve `INTERNET` izni istemez.
Seslendirme sırasında dinleme duraklatılır (yarı-dubleks).

## Derleme

APK, GitHub Actions ile otomatik üretilir (`.github/workflows/android.yml`).
Her `main` push'unda yeni APK `latest` sürümüne eklenir.

CI şunları indirir (önbelleğe alınır):

- `sherpa-onnx-1.13.8.aar` → `app/libs/` (espeak gömülü son sürüm)
- Whisper base int8 + tokens → `app/src/main/assets/models/whisper/`
- `silero_vad.onnx` → `app/src/main/assets/models/`
- NLLB int8 encoder + decoder → `app/src/main/assets/models/mt/`
- Piper tr/fa sesleri + `tokens.txt` + `espeak-ng-data` → `app/src/main/assets/models/tts/`
- CAM++ konuşmacı modeli → `app/src/main/assets/models/spk/`

Sözlük (`app/src/main/assets/mt/tokenizer.bin`) depoda durur.

Yerelde derlemek için aynı dosyaları indirip aynı yerlere koy, sonra:

```bash
./gradlew :app:assembleRelease
```

Gereksinimler: JDK 17, Android SDK 35.

## Lisans

Uygulama kodu MIT. NLLB-200 model ağırlıkları **CC-BY-NC-4.0** (ticari olmayan kullanım).
Whisper MIT, Silero VAD MIT, sherpa-onnx Apache-2.0, ONNX Runtime MIT,
3D-Speaker CAM++ Apache-2.0.
Piper seslerinin lisansı rhasspy/piper-voices deposundaki MODEL_CARD dosyalarındadır.
Bu uygulama espeak-ng (GPL-3.0) içerir: [kaynak](https://github.com/espeak-ng/espeak-ng).
