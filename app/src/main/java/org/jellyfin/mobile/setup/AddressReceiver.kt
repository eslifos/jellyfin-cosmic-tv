package org.jellyfin.mobile.setup

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * TV: the address of the server, typed on a phone instead of with the remote. While the connect screen is on view,
 * a small page is served in the local network (its address is the QR code of the screen): the phone opens it, types
 * the address and sends it, and the TV connects as if it had been typed there. It only takes a server address, which
 * is checked like any other before connecting.
 */
class AddressReceiver(private val context: Context, private val onAddress: (String) -> Unit) {
    private var socket: ServerSocket? = null
    private var job: Job? = null

    /** Serves the page; its address (http://<this TV>:<port>/), or null when the TV has no local network address. */
    fun start(scope: CoroutineScope): String? {
        val ip = localAddress() ?: return null
        val server = try {
            ServerSocket(0)
        } catch (e: java.io.IOException) {
            Timber.w(e, "Address page: could not open a port")
            return null
        }
        socket = server
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                try {
                    server.accept().use { client -> handle(client) }
                } catch (e: java.io.IOException) {
                    if (!server.isClosed) Timber.w(e, "Address page: a request failed")
                }
            }
        }
        return "http://${ip.hostAddress}:${server.localPort}/"
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket?.close()
        } catch (_: java.io.IOException) {
            // Closing anyway
        }
        socket = null
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = REQUEST_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = try {
            reader.readLine()
        } catch (_: SocketTimeoutException) {
            null
        } ?: return
        var contentLength = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            }
        }
        val page = when {
            requestLine.startsWith("POST ") -> {
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val address = formValue(String(body, 0, read), FIELD)
                if (address.isNullOrBlank()) {
                    formPage(error = true)
                } else {
                    withContext(Dispatchers.Main) { onAddress(address) }
                    sentPage()
                }
            }
            requestLine.startsWith("GET / ") || requestLine.startsWith("GET /?") -> formPage(error = false)
            else -> null
        }
        val status = if (page != null) "200 OK" else "404 Not Found"
        val bytes = (page ?: "").toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8),
            )
            write(bytes)
            flush()
        }
    }

    private fun formPage(error: Boolean): String {
        val errorLine = if (error) "<p class=\"e\">${html(R.string.connect_phone_page_error)}</p>" else ""
        return page(
            """
            <form method="post" action="/">
              <label for="a">${html(R.string.connect_phone_page_label)}</label>
              <input id="a" name="$FIELD" type="url" inputmode="url" autocapitalize="off" autocorrect="off"
                spellcheck="false" placeholder="https://" maxlength="$MAX_ADDRESS" required autofocus>
              $errorLine
              <button type="submit">${html(R.string.connect_phone_page_send)}</button>
            </form>
            """.trimIndent(),
        )
    }

    private fun sentPage() = page("<p class=\"ok\">${html(R.string.connect_phone_page_sent)}</p>")

    private fun page(content: String) = PAGE.replace("{title}", html(R.string.connect_phone_page_title)).replace("{content}", content)

    private val PAGE = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>{title}</title>
        <style>
          body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#080810;
            color:#fff;font-family:system-ui,sans-serif;padding:16px;box-sizing:border-box}
          main{width:100%;max-width:420px}
          h1{font-size:24px;margin:0 0 24px;background:linear-gradient(90deg,#a463d6,#00a4dc);-webkit-background-clip:text;
            background-clip:text;color:transparent}
          label{display:block;font-size:16px;margin-bottom:8px;color:rgba(255,255,255,.8)}
          input{width:100%;box-sizing:border-box;font-size:18px;padding:14px;border-radius:12px;border:1px solid #2c2c44;
            background:#12121f;color:#fff}
          button{margin-top:16px;width:100%;font-size:18px;font-weight:600;padding:14px;border:0;border-radius:12px;
            background:#00a4dc;color:#fff}
          .e{color:#ff6b6b;margin:8px 0 0}.ok{font-size:20px;line-height:1.4}
        </style></head>
        <body><main><h1>{title}</h1>{content}</main></body></html>
    """.trimIndent()

    private fun html(id: Int) = context.getString(id)
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val FIELD = "address"
        private const val MAX_ADDRESS = 300
        private const val MAX_BODY = 2048
        private const val REQUEST_TIMEOUT_MS = 5000

        /** The value of [name] in a form body (application/x-www-form-urlencoded), trimmed and limited. */
        fun formValue(body: String, name: String): String? = body.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.let { URLDecoder.decode(it[1], "UTF-8").trim().take(MAX_ADDRESS) }

        /** The IPv4 address of this device in the local network (Wi-Fi or Ethernet). */
        private fun localAddress(): Inet4Address? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
        } catch (e: java.net.SocketException) {
            Timber.w(e, "Address page: no network interfaces")
            null
        }
    }
}
