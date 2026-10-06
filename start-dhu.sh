#!/usr/bin/env bash
# ==============================================================================
# Jelly-Car: Android Auto Desktop Head Unit (DHU) Starter
# ==============================================================================
# Startet den offiziellen Google Android Auto Emulator auf dem PC.
# ==============================================================================

set -e

ADB="/home/ht/Android/Sdk/platform-tools/adb"
DHU_DIR="/home/ht/Android/Sdk/extras/google/auto"
DHU_BIN="${DHU_DIR}/desktop-head-unit"
DHU_LIB="${DHU_DIR}/lib"

echo "🚗 ======================================================="
echo "    Jelly-Car: Android Auto Desktop Head Unit (DHU)       "
echo "=========================================================="

# 1. Prüfen, ob ein Android-Gerät per ADB verbunden ist
DEVICE_COUNT=$("$ADB" devices | grep -v "List of devices" | grep "device$" | wc -l)
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌ Kein Smartphone per USB/ADB verbunden!"
    echo "   Bitte schließe dein Pixel 9 Pro per USB an und aktiviere USB-Debugging."
    exit 1
fi

DEVICE_NAME=$("$ADB" devices -l | grep "device " | awk '{print $1, $4, $5}')
echo "📱 Verbundenes Smartphone: $DEVICE_NAME"

# 2. Portweiterleitung für Android Auto einrichten
echo "🔌 Richte ADB-Port-Weiterleitung ein (Port 5277)..."
"$ADB" forward tcp:5277 tcp:5277
echo "✅ Port-Weiterleitung aktiv (tcp:5277 -> tcp:5277)"

# 3. Android Auto Einstellungen auf dem Smartphone automatisch öffnen
echo "📱 Öffne Android Auto Einstellungen auf dem Smartphone..."
"$ADB" shell am start -n com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity 2>/dev/null || true

echo ""
echo "ℹ️  SCHRITTE AUF DEM SMARTPHONE (Pixel 9 Pro):"
echo "   1. Die Android Auto Einstellungen wurden soeben auf deinem Display geöffnet."
echo "   2. Falls noch nicht geschehen: Nach unten scrollen, 10x auf 'Version' tippen (Entwicklermodus)."
echo "   3. Oben rechts auf die 3 Punkte tippen -> 'Head-Unit-Server starten'."
echo "----------------------------------------------------------"

export LD_LIBRARY_PATH="${DHU_LIB}:${LD_LIBRARY_PATH:-}"
# GUI-Fenster auf den aktuellen Desktop bringen (Fallbacks, falls nicht gesetzt)
export DISPLAY="${DISPLAY:-:0}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"

# Falls ein Konfigurationsprofil übergeben wurde (z.B. -c config/default_wide.ini)
if [ $# -eq 0 ]; then
    # Standard: 720p Touchscreen
    set -- -c "${DHU_DIR}/config/default_720p.ini"
fi

# 4. Warten, bis der Head-Unit-Server am Telefon wirklich lauscht.
#    Port 5277 == hex 149D; der Server bindet auf IPv4 ODER IPv6 (/proc/net/tcp[6]).
#    Kein Reconnect-Hämmern: eine einzelne Server-Sitzung ist einmalig nutzbar.
echo "⏳ Warte auf Head-Unit-Server am Telefon (Port 5277, max. 120s)..."
SERVER_UP=0
for _ in $(seq 1 120); do
    if "$ADB" shell "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '{print \$2}' | grep -qi ':149D'"; then
        SERVER_UP=1
        break
    fi
    sleep 1
done

if [ "$SERVER_UP" -ne 1 ]; then
    echo "❌ Timeout: Innerhalb 120s kein Head-Unit-Server auf Port 5277 erkannt."
    echo "   Bitte am Telefon in Android Auto: 3-Punkte-Menü -> 'Head-Unit-Server starten' und Skript neu starten."
    exit 1
fi

echo "✅ Server lauscht. 🚀 Starte Desktop Head Unit (genau eine Sitzung)..."

# Der DHU ist ein interaktives Konsolenprogramm. Ohne TTY auf stdin (z.B. im
# Hintergrund/aus einem anderen Prozess gestartet) beendet er sich sofort mit
# EOF direkt nach dem Verbinden. Darum: bei TTY normal/interaktiv starten,
# sonst stdin via 'tail -f /dev/null' offen halten.
if [ -t 0 ]; then
    exec "${DHU_BIN}" "$@"
else
    echo "ℹ️  Kein interaktives TTY erkannt - halte stdin offen (nicht-interaktiver Modus)."
    exec tail -f /dev/null | "${DHU_BIN}" "$@"
fi
