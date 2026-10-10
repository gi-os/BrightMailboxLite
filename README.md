# Mailbox Lite

> **This is the Lite version.** Mailbox Lite is built only with Light's official SDK, so it can go in the Light Tool Library. It does less than the full app. For the full app, get [Mailbox](https://github.com/gi-os/BrightMailbox) ([on BrightMarket](https://brightmarket.gzl.dev/app/com.gios.brightmailbox/)).

A mail tool for the Light Phone III, built as an official Light SDK tool. It is the
small version of [BrightMailbox](https://github.com/gi-os/BrightMailbox), Gio's
sideloaded mail app, cut down to what the SDK allows.

Mail is sorted into two piles:

- **Letters** are messages a person wrote to you. They get the whole screen, one at a
  time, set like a page. You get a ration of them per day: five, or unlimited. Once the
  five are read, the list says so and there is nothing more to scroll.
- **Notices** are everything a machine sent: receipts, shipping updates, codes,
  newsletters. One line each, grouped by day. They never make a sound (Lite cannot make
  sounds at all; see below).

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT). The SDK
modules (`sdk/`, `plugin/`, `lint-rules/`, and the rest) are upstream and untouched; the
tool lives in `tool/`.

## What it keeps from the full app

- **The sorter, verbatim.** `sort/Headers.kt`, `Sorter.kt`, `Learner.kt`, `Lessons.kt` and
  `Envelope.kt` are the full app's files with only the package renamed, and their tests
  came with them. Tier 0 decides the pile from headers (`List-Unsubscribe`, `Precedence`,
  `Auto-Submitted`, no-reply senders, VERP return paths, calendar invites, crowds of
  recipients). Above all of them: if you have written to an address, its mail is a
  Letter. Tier 1, the small hashed logistic-regression model, ranks the Letters that
  compete for the day's ration.
- **The Sent-folder signal.** On the first check, Lite reads the last year of your Sent
  folder (up to 1000 messages, To and Cc only) to learn who you write to. Replies sent
  from Lite count too.
- **The daily ration**, with the full app's rules: Letters read today stay on the list,
  the model picks which unread ones fill the rest, the list is shown in time order, and
  "Read one more" unlocks a sixth.
- **Inspectable sorting.** Every message says why it landed where it did, in words, and
  one tap moves its sender to the other pile. Every move is listed in Settings and can
  be undone there.
- **Quote and signature stripping** (`text/Clean.kt`, verbatim).
- **Gmail with an app password**, typed or scanned. The QR format is the full app's
  (`brightmailbox://signin?s=google&e=...&p=...`), so the same companion page at
  <https://gi-os.github.io/BrightMailbox/> works for both apps.
- **Any other IMAP mailbox**: address, user name, password, IMAP host and port, SMTP
  host and port. The SMTP host is guessed from the IMAP host.
- **Plain-text replies** over SMTP: implicit TLS on 465, or STARTTLS on 587 (Lite refuses
  to send a password over a connection that will not encrypt). AUTH PLAIN, falling back
  to AUTH LOGIN. Replies thread correctly (`In-Reply-To`, `References`) and quote the
  original below.
- **Checking while open and in the background**: on open and every 5 minutes while
  Mailbox Lite is on screen, and a LightWork periodic job every 15 minutes (the SDK
  minimum; Android may stretch it).
- **The scroll wheel** turns the page in the reader and scrolls every list.

## What it drops, and why

| Feature in BrightMailbox | In Lite | Why |
| --- | --- | --- |
| Outlook one-tap sign-in (OAuth) | Dropped | Microsoft's flow opens a browser and returns through a custom-scheme redirect into the app. That needs an Intent and a manifest intent filter, which SDK tools cannot have. Microsoft retired IMAP Basic auth, so a password fallback would be refused. An Outlook QR code is rejected with a sentence saying so. |
| HTML mail "as the sender built it" | Text only | No WebView and no jsoup in the SDK. Lite picks the `text/plain` part when it is real, and otherwise converts HTML to text with its own small converter (`text/Html.kt`). Images and layout are left out, and the reader says so. |
| Angus Mail for MIME | Replaced | Not on the SDK allowlist. Lite has its own minimal IMAP client (`imap/`) and MIME reader (`mail/Mime.kt`): multipart trees, quoted-printable, base64, RFC 2047 headers, RFC 2231 file names, `format=flowed`, declared and undeclared charsets. |
| CameraX QR scanning | Replaced | Not allowed. Lite uses the SDK's `LightQrCodeScanner`, which is the only camera access tools get. |
| Notifications and sounds ("Letters notify, Notices never do") | Dropped | The SDK exposes no notification API to tools (`NotificationManager` needs `getSystemService`, which is blocked). New Letters are simply there the next time you open the tool. |
| Attachments (open, save, send) | Names only | No file sharing to other apps without Intents. The reader lists attachment names; open them on a computer. |
| Archive, star, search, threads, compose-new, forward, calendar RSVP, parcels tracking, PDF viewer | Dropped | Scope. Lite keeps the sorting and the ration solid rather than many half-features. The ration counts messages, not conversations. |
| Room database | JSON files | Simpler for a few thousand rows; Lite keeps six weeks of headers (max 1500 messages) in the tool's private storage. |

## Security notes

- IMAP and SMTP run over TLS with the certificate chain **and** the hostname checked
  (`imap/Tls.kt`). A raw `SSLSocket` does not check the hostname by itself; Lite enables
  endpoint identification and also runs the platform hostname verifier.
- Passwords are encrypted with AES-256-GCM under a key in the Android Keystore and the
  ciphertext is stored in the tool's DataStore. The SDK has no keystore wrapper of its
  own; this follows the SDK's Authenticator example, which uses the platform keystore
  directly.
- Reading uses `BODY.PEEK`, so nothing is marked read by accident. Opening a message
  marks it `\Seen` on the server; if that fails, it is retried on the next check.
- Bodies are fetched with a 256 KB cap (`BODY.PEEK[]<0.262144>`), so one large
  attachment cannot exhaust memory. The parser copes with the cut.

## Setup

**Gmail.** Turn on 2-Step Verification, make an app password at
<https://myaccount.google.com/apppasswords>, then either:

- open <https://gi-os.github.io/BrightMailbox/> on a computer, enter the address and app
  password, and in Lite tap **ADD GMAIL** then **SCAN**; or
- tap **ADD GMAIL**, type the address and the sixteen letters (spaces do not matter), and
  tap **SIGN IN**.

**Other mail.** Tap **OTHER** (or Settings, **Add other mail**). Enter the address, the
password (usually an app password: Fastmail, iCloud, Zoho and Yahoo all require one),
and the IMAP server. Typical values: `imap.fastmail.com` / `smtp.fastmail.com` 465,
`imap.mail.me.com` / `smtp.mail.me.com` 587, `imap.zoho.com` / `smtp.zoho.com` 465.

Either way, Lite tries the credential against the server before keeping it. The first
check reads the last two weeks of the inbox (up to 300 messages) and the Sent folder.

Sent replies: Gmail files them in Sent automatically. Many other servers do not, and
Lite does not append a copy to Sent.

## Build

```
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug
```

Tests (the sorter, ration, MIME, HTML-to-text, IMAP parser and client, SMTP client, QR
parser):

```
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest
```

`.github/workflows/build-tool.yml` runs tests, lint and assemble on every push, with the
same flags Light's tool builder uses.

## Tool Library submission notes

- Tool id `com.gios.brightmailboxlite`, label "Mailbox Lite", portrait.
- Permissions: `INTERNET` (IMAP and SMTP) and `CAMERA` (the SDK QR scanner, only when
  scanning a sign-in code). No capabilities.
- Dependencies: only what `:sdk:client` already provides (kotlinx-serialization,
  coroutines, DataStore, LightWork). No third-party mail or HTML libraries.
- No server of any kind: the phone talks to the user's mail provider directly. Nothing
  is collected or sent anywhere else.
- Background work: one LightWork periodic job, `mail-sync`, every 15 minutes.

## What was not verified on a device

The build, unit tests and lint run in CI. The IMAP and SMTP clients are tested against
scripted servers, not live ones, and the UI has not been run on a Light Phone III from
this repository. In particular the wheel key codes are taken from the full app, which
reads them the same way.

## License

MIT, as upstream. See [LICENSE](LICENSE).
