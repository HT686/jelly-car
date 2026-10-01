package org.jellyfin.mobile.auto

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import timber.log.Timber
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Zentraler Netzwerk- und URL-Hilfsdienst für Jelly-Car Android Auto.
 *
 * Behandelt:
 * - Redigierung sensibler Anmeldedaten (Tokens, API-Keys) in Logs.
 * - Host- und Scheme-Konsistenzprüfungen zwischen Server-Base-URL und Stream/Image-URLs.
 * - DNS-Diagnose (IPv4 vs. IPv6) zur Aufdeckung von WireGuard-Routing-Problemen.
 * - Einheitliche OkHttpClient-Konfiguration mit sicherem System-TLS und strukturierter HTTP-Diagnose.
 * - Normierte MIME-Type-Ermittlung für Video- und Audio-Container.
 */
object CarUrlHelper {

    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 20L

    private val SENSITIVE_QUERY_PARAMS = setOf(
        "api_key",
        "apikey",
        "token",
        "access_token",
        "x-emby-token",
        "password",
        "pw",
    )

    private val SENSITIVE_PARAM_REGEX = Regex(
        "(?i)(${SENSITIVE_QUERY_PARAMS.joinToString("|")})=([^&\\s]+)"
    )

    @Volatile
    private var sharedOkHttpClient: OkHttpClient? = null

    /**
     * Entfernt sensible Anmeldedaten (wie api_key oder Tokens) aus URLs für Log-Ausgaben.
     */
    fun redactUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            val withoutParams = SENSITIVE_PARAM_REGEX.replace(url) { matchResult ->
                "${matchResult.groupValues[1]}=<redacted>"
            }
            // Auch embedded HTTP basic auth user:password@host redigieren
            withoutParams.replace(Regex("://([^:@]+):([^@]+)@"), "://$1:<redacted>@")
        } catch (e: Exception) {
            "<url-redaction-failed>"
        }
    }

    /**
     * Prüft und loggt Konsistenz zwischen Server-BaseURL und Ziel-URL (z. B. Bild- oder Video-Stream).
     */
    fun checkHostConsistency(baseUrlStr: String?, targetUrlStr: String?, contextTag: String = "Jelly-Car") {
        if (baseUrlStr.isNullOrBlank() || targetUrlStr.isNullOrBlank()) return

        try {
            val baseUri = baseUrlStr.toUri()
            val targetUri = targetUrlStr.toUri()

            val baseHost = baseUri.host.orEmpty()
            val targetHost = targetUri.host.orEmpty()
            val baseScheme = baseUri.scheme.orEmpty()
            val targetScheme = targetUri.scheme.orEmpty()
            val basePort = if (baseUri.port != -1) {
                baseUri.port
            } else if (baseScheme.equals("https", ignoreCase = true)) {
                DEFAULT_HTTPS_PORT
            } else {
                DEFAULT_HTTP_PORT
            }
            val targetPort = if (targetUri.port != -1) {
                targetUri.port
            } else if (targetScheme.equals("https", ignoreCase = true)) {
                DEFAULT_HTTPS_PORT
            } else {
                DEFAULT_HTTP_PORT
            }

            val hostMismatch = !baseHost.equals(targetHost, ignoreCase = true)
            val schemeMismatch = !baseScheme.equals(targetScheme, ignoreCase = true)
            val portMismatch = basePort != targetPort

            if (hostMismatch || schemeMismatch || portMismatch) {
                Timber.w(
                    "[%s] Host-Diskrepanz: baseUrl=%s vs targetUrl=%s " +
                        "(Host: '%s' vs '%s' [%b], Scheme: '%s' vs '%s' [%b], Port: %d vs %d [%b])",
                    contextTag,
                    redactUrl(baseUrlStr),
                    redactUrl(targetUrlStr),
                    baseHost,
                    targetHost,
                    hostMismatch,
                    baseScheme,
                    targetScheme,
                    schemeMismatch,
                    basePort,
                    targetPort,
                    portMismatch,
                )
            } else {
                Timber.d(
                    "[%s] Host-Konsistenz OK: host=%s, scheme=%s, port=%d",
                    contextTag,
                    baseHost,
                    baseScheme,
                    basePort,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "[%s] Konnte Host-Konsistenz nicht prüfen", contextTag)
        }
    }

    /**
     * Löst den Hostnamen asynchron auf und protokolliert IPv4- und IPv6-Adressen strukturiert.
     * Dies ist essenziell für die Diagnose von WireGuard-Tunneln (z.B. IPv4-only Tunnel bei vorhandenem AAAA Record).
     */
    suspend fun logDnsResolution(host: String?, contextTag: String = "Jelly-Car") = withContext(Dispatchers.IO) {
        if (host.isNullOrBlank()) return@withContext

        // Falls host bereits eine IP-Adresse ist, kein DNS nötig
        if (host.matches(Regex("^[0-9.]+$")) || host.contains(":")) {
            Timber.d("[%s] DNS: Host '%s' ist bereits eine direkte IP-Adresse", contextTag, host)
            return@withContext
        }

        try {
            val addresses = InetAddress.getAllByName(host)
            val ipv4List = addresses.filterIsInstance<Inet4Address>()
            val ipv6List = addresses.filterIsInstance<Inet6Address>()

            val details = buildString {
                append("DNS Auflösung für '").append(host).append("': ")
                append(addresses.size).append(" Adressen gefunden. ")
                ipv4List.forEach { append("\n  -> ").append(it.hostAddress).append(" (IPv4)") }
                ipv6List.forEach { append("\n  -> ").append(it.hostAddress).append(" (IPv6)") }
            }
            Timber.i("[%s] %s", contextTag, details)

            if (ipv4List.isNotEmpty() && ipv6List.isNotEmpty()) {
                Timber.i(
                    "[%s] Hinweis: Host '%s' besitzt sowohl IPv4- als auch IPv6-Records. " +
                        "Falls der WireGuard-Tunnel nur IPv4 routet (AllowedIPs 0.0.0.0/0 ohne ::/0), " +
                        "kann ein Android-Versuch über IPv6 zu Timeouts führen!",
                    contextTag,
                    host,
                )
            }
        } catch (e: UnknownHostException) {
            Timber.e(
                e,
                "[%s] DNS-Fehler: Host '%s' konnte nicht aufgelöst werden (UnknownHostException). " +
                    "Prüfe DNS-Server in WireGuard-Konfiguration!",
                contextTag,
                host,
            )
        } catch (e: Exception) {
            Timber.w(e, "[%s] Unerwarteter Fehler bei DNS-Auflösung für Host '%s'", contextTag, host)
        }
    }

    /**
     * Erstellt oder liefert einen sicheren, für Android Auto optimierten [OkHttpClient] mit
     * strukturiertem Logging für HTTP-Status, Redirects, Latenz und Ausnahmearten.
     * Verwendet reguläre Android System-Zertifikate ohne unsicheres globales Trust-All.
     */
    fun getOkHttpClient(context: Context? = null, contextTag: String = "Jelly-Car"): OkHttpClient {
        return sharedOkHttpClient ?: synchronized(this) {
            sharedOkHttpClient ?: buildOkHttpClient(contextTag).also { sharedOkHttpClient = it }
        }
    }

    private fun buildOkHttpClient(contextTag: String): OkHttpClient {
        val loggingInterceptor = Interceptor { chain ->
            val request = chain.request()
            val startTime = System.nanoTime()
            val redactedReqUrl = redactUrl(request.url.toString())

            Timber.d(
                "[%s] HTTP Request: %s %s (Host: %s, Port: %d, Scheme: %s)",
                contextTag,
                request.method,
                redactedReqUrl,
                request.url.host,
                request.url.port,
                request.url.scheme,
            )

            val response: Response
            try {
                response = chain.proceed(request)
            } catch (e: Exception) {
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
                when (e) {
                    is UnknownHostException -> Timber.e(
                        e,
                        "[%s] HTTP DNS-Fehler: UnknownHostException für Host %s",
                        contextTag,
                        request.url.host,
                    )
                    is ConnectException -> Timber.e(
                        e,
                        "[%s] HTTP Verbindungsfehler: ConnectException zu %s:%d",
                        contextTag,
                        request.url.host,
                        request.url.port,
                    )
                    is SocketTimeoutException -> Timber.e(
                        e,
                        "[%s] HTTP SocketTimeout nach %dms zu %s:%d (MTU/Paketverlust?)",
                        contextTag,
                        elapsedMs,
                        request.url.host,
                        request.url.port,
                    )
                    is SSLException -> Timber.e(
                        e,
                        "[%s] HTTP TLS/SSL-Fehler zu %s:%d: %s",
                        contextTag,
                        request.url.host,
                        request.url.port,
                        e.message,
                    )
                    else -> Timber.e(e, "[%s] HTTP IO-Fehler nach %dms: %s", contextTag, elapsedMs, e.message)
                }
                throw e
            }

            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

            // Redirects protokollieren (z. B. von externer Domain auf interne IP)
            val prior = response.priorResponse
            if (prior != null) {
                Timber.w(
                    "[%s] HTTP Redirect: von %s -> zu Host '%s' (Code %d)",
                    contextTag,
                    redactUrl(prior.request.url.toString()),
                    response.request.url.host,
                    prior.code,
                )
            }

            val contentType = response.header("Content-Type") ?: "unknown"
            val contentLength = response.header("Content-Length") ?: "unknown"
            val contentRange = response.header("Content-Range")
            val acceptRanges = response.header("Accept-Ranges")

            Timber.d(
                "[%s] HTTP Response: %d %s für %s in %dms (Type: %s, Len: %s, Range: %s, Accept-Ranges: %s)",
                contextTag,
                response.code,
                response.message,
                redactedReqUrl,
                elapsedMs,
                contentType,
                contentLength,
                contentRange ?: "none",
                acceptRanges ?: "none",
            )

            response
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Mappt Container-Bezeichnungen sauber auf Standard Media3 MimeTypes.
     */
    fun containerToMimeType(container: String?): String? = when (container?.lowercase()?.trim()) {
        "m3u8" -> MimeTypes.APPLICATION_M3U8
        "ts" -> MimeTypes.VIDEO_MP2T
        "mp4", "m4v" -> MimeTypes.VIDEO_MP4
        "mkv", "matroska" -> MimeTypes.VIDEO_MATROSKA
        "webm" -> MimeTypes.VIDEO_WEBM
        "mpd" -> MimeTypes.APPLICATION_MPD
        "avi" -> MimeTypes.VIDEO_AVI
        "flv" -> MimeTypes.VIDEO_FLV
        "mov" -> MimeTypes.VIDEO_MP4
        "mp3" -> MimeTypes.AUDIO_MPEG
        "aac" -> MimeTypes.AUDIO_AAC
        "flac" -> MimeTypes.AUDIO_FLAC
        "ogg", "oga" -> MimeTypes.AUDIO_OGG
        "opus" -> MimeTypes.AUDIO_OPUS
        else -> null
    }
}
