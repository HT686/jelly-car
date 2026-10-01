package org.jellyfin.mobile.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException

/**
 * Hilfsklasse zum Laden, Skalieren und Zwischenspeichern von Film-, Serien-
 * und Episoden-Coverbildern für die Android Auto Anzeige.
 *
 * Verwendet den standardkonformen [CarUrlHelper]-OkHttpClient (System-TLS, saubere
 * Zertifikatsprüfung, Redirect- und DNS-Diagnose) und bietet detaillierte Fehlereinstufung
 * für WireGuard- und Netzwerkprobleme.
 */
class CarImageHelper(
    private val context: Context,
    private val apiClient: ApiClient,
) {
    private val httpClient: OkHttpClient by lazy {
        CarUrlHelper.getOkHttpClient(context, "CarImageHelper")
    }

    // Cache für bis zu 100 Cover-Bitmaps im Arbeitsspeicher
    private val memoryCache = object : LruCache<String, Bitmap>(100 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * Lädt das primäre Bild eines Jellyfin-Elements und konvertiert es in ein [CarIcon].
     * Gibt bei Fehlern ein Standard-Ersatzsymbol zurück.
     */
    suspend fun loadCarIcon(
        itemId: UUID,
        imageType: ImageType = ImageType.PRIMARY,
        fallbackResId: Int = R.drawable.ic_local_movies_white_64,
        maxHeight: Int = 200,
        maxWidth: Int = 200,
    ): CarIcon = withContext(Dispatchers.IO) {
        val cacheKey = "${itemId}_${imageType.name}_${maxWidth}x${maxHeight}"
        val cached = memoryCache.get(cacheKey)
        if (cached != null) {
            return@withContext CarIcon.Builder(IconCompat.createWithBitmap(cached)).build()
        }

        var requestedUrl: String? = null
        try {
            val imageUrl = apiClient.imageApi.getItemImageUrl(
                itemId = itemId,
                imageType = imageType,
                maxHeight = maxHeight,
                maxWidth = maxWidth,
                quality = 80,
            )

            val fullUrl = if (apiClient.accessToken != null && !imageUrl.contains("api_key", ignoreCase = true)) {
                if (imageUrl.contains("?")) "$imageUrl&api_key=${apiClient.accessToken}"
                else "$imageUrl?api_key=${apiClient.accessToken}"
            } else {
                imageUrl
            }
            requestedUrl = fullUrl

            // Host-Konsistenz prüfen (z.B. base URL vs Image URL)
            CarUrlHelper.checkHostConsistency(apiClient.baseUrl, fullUrl, "CarImageHelper")

            val requestBuilder = Request.Builder().url(fullUrl)

            if (apiClient.accessToken != null) {
                try {
                    val authHeader = AuthorizationHeaderBuilder.buildHeader(
                        clientName = apiClient.clientInfo.name,
                        clientVersion = apiClient.clientInfo.version,
                        deviceId = apiClient.deviceInfo.id,
                        deviceName = apiClient.deviceInfo.name,
                        accessToken = apiClient.accessToken,
                    )
                    requestBuilder.header("Authorization", authHeader)
                    requestBuilder.header("X-Emby-Token", apiClient.accessToken ?: "")
                } catch (e: Exception) {
                    Timber.w(e, "CarImageHelper: Konnte AuthHeader nicht erstellen")
                }
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                when {
                    resp.isSuccessful -> {
                        val body = resp.body
                        if (body == null) {
                            Timber.w("CarImageHelper: Antwortkörper ist leer für Item $itemId (HTTP ${resp.code})")
                            return@withContext CarIcon.Builder(IconCompat.createWithResource(context, fallbackResId)).build()
                        }

                        val bitmap = body.byteStream().use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }

                        if (bitmap != null) {
                            memoryCache.put(cacheKey, bitmap)
                            return@withContext CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
                        } else {
                            Timber.w("CarImageHelper: Bitmap-Dekodierung fehlgeschlagen für Item $itemId (ungültige Bilddaten)")
                        }
                    }
                    resp.code == 401 -> {
                        Timber.e("CarImageHelper: HTTP 401 Nicht autorisiert beim Laden von Item $itemId (Token ungültig/abgelaufen)")
                    }
                    resp.code == 403 -> {
                        Timber.e("CarImageHelper: HTTP 403 Zugriff verweigert für Item $itemId")
                    }
                    resp.code == 404 -> {
                        Timber.d("CarImageHelper: HTTP 404 Kein Bild vorhanden für Item $itemId")
                    }
                    resp.code >= 500 -> {
                        Timber.e("CarImageHelper: HTTP ${resp.code} Serverfehler beim Laden von Item $itemId")
                    }
                    else -> {
                        Timber.w("CarImageHelper: Bildabruf fehlgeschlagen für Item $itemId: HTTP ${resp.code} ${resp.message}")
                    }
                }
            }
        } catch (e: UnknownHostException) {
            Timber.e(
                e,
                "CarImageHelper: UnknownHostException für Item %s (URL=%s). " +
                    "DNS-Auflösung über WireGuard fehlgeschlagen! DNS-Einstellungen der VPN-Verbindung prüfen.",
                itemId,
                CarUrlHelper.redactUrl(requestedUrl),
            )
        } catch (e: ConnectException) {
            Timber.e(
                e,
                "CarImageHelper: ConnectException für Item %s (URL=%s). " +
                    "Ziel nicht erreichbar! WireGuard AllowedIPs, Routing oder Serverstatus prüfen.",
                itemId,
                CarUrlHelper.redactUrl(requestedUrl),
            )
        } catch (e: SocketTimeoutException) {
            Timber.e(
                e,
                "CarImageHelper: SocketTimeoutException für Item %s (URL=%s). " +
                    "Zeitüberschreitung! Mögliche Ursachen: WireGuard MTU, Paketverlust oder Firewall.",
                itemId,
                CarUrlHelper.redactUrl(requestedUrl),
            )
        } catch (e: SSLException) {
            Timber.e(
                e,
                "CarImageHelper: SSLException für Item %s (URL=%s). " +
                    "TLS/Zertifikats-Handshake fehlgeschlagen! Gültigkeit des Server-Zertifikats und Hostnamen prüfen.",
                itemId,
                CarUrlHelper.redactUrl(requestedUrl),
            )
        } catch (e: IOException) {
            Timber.w(
                e,
                "CarImageHelper: Netzwerk-IO-Fehler für Item %s (URL=%s): %s",
                itemId,
                CarUrlHelper.redactUrl(requestedUrl),
                e.message,
            )
        } catch (e: Exception) {
            Timber.w(
                e,
                "CarImageHelper: Unerwarteter Fehler beim Bildladen für Item %s (URL=%s): %s",
                itemId,
                CarUrlHelper.redactUrl(requestedUrl),
                e.message,
            )
        }

        // Fallback-Icon aus Android-Ressourcen
        CarIcon.Builder(IconCompat.createWithResource(context, fallbackResId)).build()
    }

    /**
     * Erstellt ein CarIcon aus einer lokalen Drawable-Ressource.
     */
    fun createResourceIcon(resId: Int): CarIcon {
        return CarIcon.Builder(IconCompat.createWithResource(context, resId)).build()
    }
}
