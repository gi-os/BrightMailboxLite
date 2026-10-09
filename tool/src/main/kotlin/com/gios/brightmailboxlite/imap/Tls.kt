package com.gios.brightmailboxlite.imap

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * TLS sockets for IMAP and SMTP, with the hostname actually checked.
 *
 * `SSLSocketFactory.getDefault()` validates the certificate chain but, unlike an HTTPS
 * client, does not check that the certificate names the host — a raw socket has no idea
 * what a "host" is. Without the explicit check below, any valid certificate for any
 * domain would be accepted for imap.gmail.com. Both mechanisms are applied: endpoint
 * identification during the handshake, and the platform hostname verifier after it.
 */
object Tls {

    private const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 60_000

    /** Implicit TLS: the socket is encrypted from the first byte (IMAPS 993, SMTPS 465). */
    fun connect(host: String, port: Int): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return wrap(raw, host, port)
    }

    /** Plain TCP, for SMTP submission on 587 before STARTTLS. */
    fun connectPlain(host: String, port: Int): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        raw.soTimeout = READ_TIMEOUT_MS
        return raw
    }

    /** Upgrade an open plain socket in place (STARTTLS). */
    fun wrap(raw: Socket, host: String, port: Int): SSLSocket {
        raw.soTimeout = READ_TIMEOUT_MS
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket(raw, host, port, true) as SSLSocket
        socket.sslParameters = socket.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
        socket.soTimeout = READ_TIMEOUT_MS
        socket.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) {
            socket.close()
            throw SSLPeerUnverifiedException("certificate does not match $host")
        }
        return socket
    }

    fun imap(host: String, port: Int): ImapClient {
        val socket = connect(host, port)
        val client = ImapClient(socket.getInputStream().buffered(), socket.getOutputStream().buffered()) {
            runCatching { socket.close() }
        }
        try {
            client.greeting()
        } catch (e: Exception) {
            client.close()
            throw e
        }
        return client
    }
}
