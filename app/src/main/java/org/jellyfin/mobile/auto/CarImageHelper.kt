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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Hilfsklasse zum Laden, Skalieren und Zwischenspeichern von Film-, Serien-
 * und Episoden-Coverbildern für die Android Auto Anzeige.
 *
 * Unterstützt sowohl HTTP als auch HTTPS (inkl. selbstsignierter Zertifikate
 * und Reverse-Proxies) sowie automatische Authorization-Header.
 */
class CarImageHelper(
    private val context: Context,
    private val apiClient: ApiClient,
) {
    private val httpClient: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            Timber.e(e, "Jelly-Car: Fehler bei Initialisierung des SSL-toleranten OkHttpClients")
            OkHttpClient.Builder().build()
        }
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
                    Timber.w(e, "Konnte AuthHeader nicht erstellen")
                }
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        memoryCache.put(cacheKey, bitmap)
                        return@withContext CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
                    }
                }
            } else {
                Timber.w("Bildabruf fehlgeschlagen für Item $itemId: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Konnte Bild für Item $itemId nicht laden (URL evtl. HTTPS/Zertifikatsfehler)")
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
