package com.gios.brightmailboxlite.auth

/**
 * Where an account's mail lives and how to reach it.
 *
 * Ported from the full app's `Service` and `Servers`, minus OAuth. Lite signs in with a
 * password only: Gmail with an app password, anything else with whatever its IMAP server
 * accepts (usually an app password too).
 */
data class Servers(
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    /** True for implicit TLS on connect (465); false for STARTTLS (587). */
    val smtpSsl: Boolean,
) {
    val valid: Boolean get() = imapHost.isNotBlank() && imapPort in 1..65535 && smtpPort in 1..65535

    companion object {
        /**
         * A sensible guess from an IMAP host, as in the full app: `imap.x` pairs with
         * `smtp.x`, and 465 with implicit TLS is the modern default for submission.
         */
        fun guess(imapHost: String): Servers {
            val host = imapHost.trim().lowercase().removePrefix("imap://").removePrefix("imaps://")
            val smtp = when {
                host.startsWith("imap.") -> "smtp." + host.removePrefix("imap.")
                else -> host
            }
            return Servers(host, 993, smtp, 465, smtpSsl = true)
        }

        /** STARTTLS on 587, implicit TLS on everything else. */
        fun sslForPort(port: Int): Boolean = port != 587 && port != 25
    }
}

enum class Service(val key: String, val label: String, val supported: Boolean) {
    GOOGLE("google", "Gmail", true),

    /**
     * Known only so a QR code from the full app's page can be refused with a reason.
     * Outlook needs OAuth with a browser redirect back into the app, which a Light SDK
     * tool cannot receive.
     */
    MICROSOFT("microsoft", "Outlook", false),

    IMAP("imap", "Mail", true);

    companion object {
        fun of(key: String?): Service? = entries.firstOrNull { it.key == key }

        val GMAIL_SERVERS = Servers("imap.gmail.com", 993, "smtp.gmail.com", 465, smtpSsl = true)
    }
}
