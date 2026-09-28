# Jelly-Car: Video-Streaming in Android Auto (Technischer Leitfaden & Sideloading-Guide)

Willkommen bei **Jelly-Car**, einem spezialisierten Fork von Jellyfin für Android, der speziell für die hardwarebeschleunigte **Videowiedergabe auf Android Auto-Displays** per Sideloading entwickelt wurde.

---

## 🚗 Inhaltsverzeichnis

1. [Überblick & Funktionsweise](#überblick--funktionsweise)
2. [Technische Architektur](#technische-architektur)
   - [CarAppService & Navigation Category](#carappservice--navigation-category)
   - [Hardware Surface Video Rendering mit ExoPlayer](#hardware-surface-video-rendering-mit-exoplayer)
   - [Jellyfin Server-Synchronisation (PlayState)](#jellyfin-server-synchronisation-playstate)
   - [Duale Medienunterstützung (Video & Audio)](#duale-medienunterstützung-video--audio)
3. [Schritt-für-Schritt Sideloading-Anleitung](#schritt-für-schritt-sideloading-anleitung)
   - [Voraussetzungen](#voraussetzungen)
   - [Schritt 1: Android Auto Entwickleroptionen freischalten](#schritt-1-android-auto-entwickleroptionen-freischalten)
   - [Schritt 2: „Unbekannte Quellen“ aktivieren](#schritt-2-unbekannte-quellen-aktivieren)
   - [Schritt 3: Jelly-Car APK installieren](#schritt-3-jelly-car-apk-installieren)
   - [Schritt 4: Verbindung mit dem Fahrzeug](#schritt-4-verbindung-mit-dem-fahrzeug)
4. [Bedienung im Fahrzeug](#bedienung-im-fahrzeug)
5. [Bauen aus dem Quellcode](#bauen-aus-dem-quellcode)
6. [Sicherheitshinweis](#sicherheitshinweis)

---

## 1. Überblick & Funktionsweise

Standardmäßig erlaubt Google in Android Auto für offizielle Play Store Apps nur reine Audio-Apps (MediaBrowserService), Navigation, Messaging und Parkplatz-/Ladeplatz-Apps. Video-Wiedergabe wird standardmäßig unterbunden.

**Jelly-Car** umgeht diese Beschränkung für den **privaten Sideloading-Einsatz**:
- **Echte Videoprojektion auf das Car-Display:** Filme, TV-Serien, Episoden und Videos werden direkt auf dem Fahrzeugdisplay wiedergegeben.
- **Vollwertige Touch-Bedienoberfläche:** Übersicht für *Weiter ansehen*, *Filme*, *Serien*, *Staffeln*, *Episoden*, *Neueste Videos* und *Suche*.
- **Vollständige Player-Steuerung:** Play/Pause, 10s Rücklauf, 30s Vorlauf, Format-Umschaltung (16:9 Fit / Crop Fill), Audiospur-Auswahl und Touchscreen-Gesten.
- **Server-Synchronisation:** Nahtlose Synchronisation mit dem heimischen Jellyfin-Server (Start, 10s-Fortschritts-Ticks, Stop, Status „Gesehen“ und Resume-Punkte).

---

## 2. Technische Architektur

### CarAppService & Navigation Category
In Android Auto erhalten reguläre Medien-Apps keinen Zugriff auf den Bildschirm zur Videodarstellung. Um auf das echte Hardware-Display des Autos zugreifen zu können, deklariert Jelly-Car in `AndroidManifest.xml` einen `CarAppService` mit der Kategorie `androidx.car.app.category.NAVIGATION`:

```xml
<service
    android:name=".auto.CarVideoAppService"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <category android:name="androidx.car.app.category.NAVIGATION" />
    </intent-filter>
</service>
```

Durch diese Deklaration gewährt die Android Car App Library (`androidx.car.app:app` und `androidx.car.app:app-projected`) über das `NavigationTemplate` Zugriff auf den `AppManager.setSurfaceCallback(surfaceCallback)`.

### Hardware Surface Video Rendering mit ExoPlayer
Sobald der Car Host bereit ist, ruft er `SurfaceCallback.onSurfaceAvailable(surfaceContainer)` auf. Das darin enthaltene, native Android `Surface` wird direkt an den Media3 `ExoPlayer` übergeben:

```kotlin
override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
    playerManager.setSurface(surfaceContainer.surface)
}
```

Der `CarVideoPlayerManager` weist `ExoPlayer` das Surface zu:
```kotlin
exoPlayer.setVideoSurface(surface)
```
Dadurch dekodieren die Hardware-Videodecoder des Smartphones die Videoframes und übertragen sie mit minimaler Latenz und nativer Auflösung direkt auf das Armaturenbrett-Display.

### Jellyfin Server-Synchronisation (PlayState)
Jelly-Car synchronisiert den Wiedergabestatus in Echtzeit mit deinem Jellyfin Server:
1. **Start:** `apiClient.playStateApi.reportPlaybackStart(...)` meldet den Beginn der Wiedergabe an den Server.
2. **Laufzeit:** Eine periodische Coroutine sendet alle 10 Sekunden `apiClient.playStateApi.reportPlaybackProgress(...)`.
3. **Stop / Pause:** `apiClient.playStateApi.reportPlaybackStopped(...)` speichert die genaue Wiedergabeposition.
4. **Ende:** Wenn der Film oder die Folge endet (`Player.STATE_ENDED`), wird `apiClient.playStateApi.markPlayedItem(...)` aufgerufen, sodass der Inhalt als gesehen markiert wird.

### Duale Medienunterstützung (Video & Audio)
Neben der visuellen Car-App unterstützt Jelly-Car auch den klassischen Android Auto Media Browser (`LibraryService`). Auch dort stehen Film- und Serienbibliotheken zur Verfügung, sodass Videos bei Bedarf auch als reine Audio-Streams (z. B. Konzerte, Video-Podcasts) über das normale Media-Interface des Autos gehört werden können.

---

## 3. Schritt-für-Schritt Sideloading-Anleitung

### Voraussetzungen
- Android-Smartphone mit Android 9 oder neuer
- Android Auto App auf dem Smartphone installiert
- Jellyfin-Server (lokal oder per Fernzugriff erreichbar)

### Schritt 1: Android Auto Entwickleroptionen freischalten
1. Öffne die **Einstellungen** auf deinem Smartphone.
2. Suche nach **Android Auto** und öffne das Einstellungsmenü von Android Auto.
3. Scrolle ganz nach unten bis zum Eintrag **Version**.
4. Tippe **10-mal hintereinander** schnell auf das Feld **Version**.
5. Bestätige die Abfrage, um die Entwicklereinstellungen zu aktivieren.

### Schritt 2: „Unbekannte Quellen“ aktivieren
1. Tippe oben rechts auf die **drei Punkte (Menü)** in den Android Auto Einstellungen.
2. Wähle **Entwicklereinstellungen**.
3. Scrolle nach unten und setze einen Haken bei **Unbekannte Quellen** (*Unknown sources*).
4. *(Optional bei Bedarf)* Setze unter **Anwendungsmodus** den Wert auf **Entwickler**.

### Schritt 3: Jelly-Car APK installieren
Installiere die erstellte `jelly-car-v...-libre-debug.apk` auf deinem Smartphone:

**Option A: Per ADB (Empfohlen für Entwickler)**
```bash
adb install -r app/build/outputs/apk/libre/debug/jelly-car-v0.0.0-dev.1-libre-debug.apk
```

**Option B: Direkte APK-Installation**
Übertrage die APK auf dein Smartphone und installiere sie mit einem Dateimanager. 
*(Hinweis für Android 14+: Falls die App im Auto nicht direkt im Launcher auftaucht, kann ein Installer wie KingInstaller oder AAAD genutzt werden, der der App den Install-Source "com.android.vending" zuweist).*

### Schritt 4: Verbindung mit dem Fahrzeug
1. Öffne **Jelly-Car** auf deinem Smartphone und melde dich bei deinem Jellyfin-Server an.
2. Schließe das Smartphone per USB-Kabel an das Auto an oder verbinde dich kabellos über Android Auto (oder Wireless-Adapter wie AAWireless).
3. Öffne im Android Auto App-Menü auf deinem Fahrzeug-Display das Icon **Jelly-Car**.
4. Wähle dein gewünschtes Video aus und genieße das Streaming!

---

## 4. Bedienung im Fahrzeug

Auf dem Auto-Display stehen dir folgende Ansichten zur Verfügung:
- **Dashboard (Hauptmenü):**
  - **Weiter ansehen:** Schneller Zugriff auf angefangene Filme & Serien mit Prozentanzeige.
  - **Filme:** Komplette Spielfilmsammlung, sortiert nach Name.
  - **Serien:** Alle TV-Serien mit Unterteilung in Staffeln und Episoden.
  - **Neueste Videos:** Kürzlich zur Mediathek hinzugefügte Inhalte.
  - **Suche:** Spracheingabe oder Bildschirmtastatur zur Suche in der Mediathek.
- **Video-Player-Display:**
  - **Bildschirm antippen:** Blendet Play/Pause-Status und Zeitstempel ein oder schaltet Pause um.
  - **Steuerleiste unten:**
    - `⏪ -10s`: 10 Sekunden zurückspringen
    - `⏯`: Pause / Weiter
    - `⏩ +30s`: 30 Sekunden vorspringen
  - **Sekundäre Leiste:**
    - **16:9 Fit / Crop Fill:** Wechselt das Bildseitenverhältnis für Breitbild oder Vollbild.
    - **Audiospuren:** Wählt zwischen verschiedenen Sprachen und Tonspuren (Stereo, 5.1).
    - **Stop:** Beendet die Wiedergabe und kehrt zur Mediathek zurück.

---

## 5. Bauen aus dem Quellcode

Zum Kompilieren des Projekts auf deinem Computer:

```bash
# Repository klonen
git clone https://github.com/HT686/jelly-car.git
cd jelly-car

# Umgebungsvariablen setzen (Java 17 & Android SDK)
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# Debug-APK für Sideloading bauen
./gradlew assembleLibreDebug

# Die fertige APK befindet sich unter:
# app/build/outputs/apk/libre/debug/jelly-car-v*-libre-debug.apk
```

---

## 6. Sicherheitshinweis

> [!CAUTION]
> **Wichtiger Sicherheits- und Rechtshinweis:**
> 
> Die Videowiedergabe im Fahrzeug darf **ausschließlich im stehenden Zustand (bei geparktem Fahrzeug)** genutzt werden – beispielsweise während Ladepausen von Elektrofahrzeugen oder bei Rastpausen. Die Nutzung von Videofunktionen während der Fahrt gefährdet die Verkehrssicherheit und verstößt in den meisten Ländern gegen geltende Gesetze (z.B. § 23 StVO in Deutschland). Nutzen Sie diese Funktion verantwortungsvoll.
