package org.jellyfin.mobile.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.media3.common.util.UnstableApi

/**
 * Service-Einstiegspunkt für Android Auto in Jelly-Car.
 *
 * Registriert als [androidx.car.app.category.NAVIGATION] Service, um vollständigen
 * Zugriff auf das Auto-Display Hardware-Surface über den Car AppManager zu erhalten.
 * Dies ermöglicht flüssiges, natives Video-Streaming über ExoPlayer im Fahrzeug.
 */
@UnstableApi
class CarVideoAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Erlaubt alle Car-Hosts für Sideloading (Unknown Sources), DHU und Wireless-Adapter (z.B. AAWireless)
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return CarVideoSession()
    }
}
