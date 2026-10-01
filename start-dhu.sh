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
echo "🚀 Starte Desktop Head Unit (wartet auf Server)..."

export LD_LIBRARY_PATH="${DHU_LIB}:${LD_LIBRARY_PATH}"

# Falls ein Konfigurationsprofil übergeben wurde (z.B. -c config/default_wide.ini)
CONFIG_ARG=""
if [ $# -eq 0 ]; then
    # Standard: 720p Touchscreen
    CONFIG_ARG="-c ${DHU_DIR}/config/default_720p.ini"
fi

while true; do
    echo "Verbinde mit Head-Unit-Server auf Pixel 9 Pro..."
    "${DHU_BIN}" $CONFIG_ARG "$@" || true
    echo "⚠️  Verbindung noch nicht hergestellt oder getrennt."
    echo "   Bitte auf dem Smartphone 'Head-Unit-Server starten' antippen. Neuer Versuch in 3s..."
    sleep 3
done
