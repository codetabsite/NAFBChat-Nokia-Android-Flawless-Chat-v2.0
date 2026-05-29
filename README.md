# NAFBChat
**Nokia Android Flawless Bluetooth Chat**

Bluetooth mesh zinciri üzerinden çalışan cross-platform chat uygulaması.
- **Nokia 6300** (J2ME / MIDP 2.0) ↔ **Android** (5.0+) arası doğrudan iletişim
- A→B→C mesh zinciri: her cihaz mesajı ileriye iletir, mesafe artar
- Siyah minimal arayüz, AI slop yok
- Oturum içi mesajlar, kayıt yok

---

## Otomatik Build (GitHub Actions)

Her `push` sonrası Actions sekmesine git → en son workflow çalıştırması → **Artifacts**:

| Dosya | Platform |
|-------|----------|
| `NAFBChat-Nokia/NAFBChat.jar` | Nokia 6300 (J2ME) |
| `NAFBChat-Nokia/NAFBChat.jad` | Nokia descriptor |
| `NAFBChat-Android/NAFBChat-debug.apk` | Android (debug) |

---

## Proje Yapısı

```
NAFBChat/
├── .github/workflows/build.yml   ← GitHub Actions
├── nokia/
│   ├── src/nafbchat/
│   │   ├── NAFBChat.java         ← MIDlet giriş noktası
│   │   ├── ChatScreen.java       ← UI
│   │   └── BluetoothManager.java ← BT mesh (JSR-82)
│   ├── MANIFEST.MF
│   ├── NAFBChat.jad
│   └── build.xml                 ← Ant build
└── android/
    ├── app/src/main/
    │   ├── java/com/nafbchat/
    │   │   ├── NameActivity.kt
    │   │   ├── ChatActivity.kt
    │   │   └── AndroidBluetoothManager.kt
    │   ├── res/layout/
    │   │   ├── activity_name.xml
    │   │   └── activity_chat.xml
    │   └── AndroidManifest.xml
    └── build.gradle
```

---

## Protokol

Her iki platform **aynı** SPP UUID kullanır:
```
4E414642-4368-6174-0000-000000ABCD00
```

**Mesh zinciri:**
1. Cihaz A → mesaj gönderir
2. B alır → UI'a yazar → C ve D'ye iletir
3. C alır → UI'a yazar → D'ye iletir
4. Tekrar yayın önleme: her mesajın ilk 32 karakteri ID olarak saklanır

**Bağlantı:**
- Nokia: Bluetooth keşfi (GIAC inquiry) + kendi servisini açar
- Android: Eşleşmiş cihazlara bağlanır + gelen bağlantı kabul eder

---

## Nokia'ya Yükleme

1. `NAFBChat.jar` ve `NAFBChat.jad` dosyalarını telefona aktar (Bluetooth veya USB)
2. `.jad` dosyasını aç → Yükle
3. Bluetooth iznini kabul et

## Android'e Yükleme

```bash
adb install NAFBChat-debug.apk
```
veya doğrudan dosya yöneticisinden aç (Bilinmeyen kaynaklar açık olmalı).

---

## Notlar

- Nokia 6300 ilk önce Bluetooth'u **görünür** yapmalısın (Ayarlar → Bağlantı → Bluetooth → Benim telefonum görünürlüğü)
- Android 12+ için **Yakındaki cihazlar** izni gerekli
- Cihazların **önceden eşleşmiş** (paired) olması en güvenilir bağlantıyı sağlar
