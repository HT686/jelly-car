<h1 align="center">Jelly-Car</h1>
<h3 align="center">Android Auto Video Streaming Fork von Jellyfin</h3>

---

<p align="center">
<strong>Jelly-Car</strong> ist ein spezialisierter, privater Fork von <a href="https://github.com/jellyfin/jellyfin-android">Jellyfin for Android</a> für den <strong>Sideloading-Einsatz in Android Auto</strong> mit vollem Fokus auf <strong>echtes Videostreaming direkt auf dem Fahrzeug-Display</strong>.
</p>

---

## 🌟 Highlights von Jelly-Car

- 📺 **Hardwarebeschleunigtes Videostreaming:** Projiziert Videoframes hardwaredekodiert direkt auf das Armaturenbrett-Display des Autos (mittels `androidx.car.app` und nativem `Surface`-Rendering über ExoPlayer).
- 🎬 **Komplette Medienunterstützung:** Filme, TV-Serien (mit Staffeln & Episoden), Heimvideos, Musikvideos und "Weiter ansehen" (Resume).
- 🚗 **Fahrzeug-optimierte Touch-Oberfläche:**
  - **Dashboard:** Direkter Zugriff auf *Weiter ansehen*, *Filme*, *Serien*, *Neueste Videos* und *Suche*.
  - **Detailansicht:** Metadaten, Laufzeiten, Beschreibungen sowie 1-Klick-Fortsetzen oder Neustarten.
  - **Player-Steuerung:** Play/Pause, 10s-Rücklauf, 30s-Vorlauf, Formatumschaltung (16:9 Fit / Crop Fill), Audiospur-Auswahl und Touchscreen-Gesten.
- 🔄 **Jellyfin-Server Synchronisation:** Vollständiges Reporting des Wiedergabestatus (Start, 10-Sekunden-Fortschrittstick, Stop und "Als gesehen markieren"). Angefangene Videos im Auto können nahtlos zuhause am Fernseher oder PC fortgesetzt werden!
- 🔍 **In-Car Suche:** Schnelle Suche nach Filmen und Serien per Spracheingabe oder Tastatur im Fahrzeug.
- 📻 **Dual-Mode Kompatibilität:** Funktioniert sowohl im visuellen Video-Modus als auch über den Standard-MediaBrowser für Audioausgabe.

---

## 📖 Detaillierter Leitfaden & Sideloading

Eine ausführliche Anleitung zur Aktivierung von Android Auto Entwickleroptionen, Sideloading von unbekannten Quellen, technischen Details und Sicherheitsrichtlinien findest du in unserem Dokument:

👉 **[ANDROID_AUTO_GUIDE.md](ANDROID_AUTO_GUIDE.md)**

---

## 🛠️ Bauen & Installieren

### Voraussetzungen
- JDK 17
- Android SDK (Plattform 35 oder neuer)

### Befehle

```sh
# Repository klonen
git clone https://github.com/HT686/jelly-car.git
cd jelly-car

# Debug-APK für Android Auto Sideloading bauen
./gradlew assembleLibreDebug

# APK direkt auf dem Smartphone installieren (per ADB)
./gradlew installLibreDebug
```

Die fertige APK wird erzeugt unter:
`app/build/outputs/apk/libre/debug/jelly-car-v*-libre-debug.apk`

---

## 🔒 Privater Sideloading-Hinweis & Sicherheit

Dieser Fork ist ausschließlich für die **private Nutzung via Sideloading** gedacht.

> [!CAUTION]
> **Sicherheitshinweis:**
> Videowiedergabe im Fahrzeug darf **nur im geparkten / stehenden Zustand** (z. B. während Ladepausen bei Elektrofahrzeugen) genutzt werden. Die Nutzung während der Fahrt lenkt vom Verkehrsgeschehen ab und ist gesetzlich verboten.

---

## 📄 Lizenz

Jelly-Car basiert auf Jellyfin for Android und steht unter der GNU General Public License v2.0 (GPL-2.0). Siehe [LICENSE.md](LICENSE.md) für weitere Details.
