# SmsForwarder Controller

An Android app that acts as a **Telegram Bot controller** for [SmsForwarder](https://github.com/pppscn/SmsForwarder), allowing you to remotely control your SmsForwarder device via Telegram.

## Features

| Command | Description |
|---------|-------------|
| `/config` | View remote device configuration and enabled features |
| `/battery` | Query battery level, voltage, temperature and status |
| `/location` | Query GPS location with address and map pin |
| `/sms_send` | Send SMS interactively (select SIM → enter number → enter content → confirm) |
| `/sms_query` | Query received or sent SMS records with pagination |
| `/call_query` | Query incoming / outgoing / missed call records with pagination |
| `/contact_query` | Search contacts by name or phone number |
| `/contact_add` | Add a new contact |
| `/wol` | Send a Wake-on-LAN magic packet |
| `/clone` | Pull clone configuration from the remote device |
| `/check` | Check connectivity and security configuration |
| `/cancel` | Cancel the current interactive operation |

## Requirements

- Android 7.0 (API 24) or higher
- [SmsForwarder](https://github.com/pppscn/SmsForwarder) running on the target device with the HTTP API enabled
- A Telegram Bot token (create one via [@BotFather](https://t.me/BotFather))

## Setup

1. Install the APK on the **same device** running SmsForwarder (or any Android device on the same network if you adjust the host)
2. Open the app and fill in **Basic Settings**:
   - **Telegram Bot Token** — the token from BotFather
   - **SmsForwarder Port** — the HTTP API port configured in SmsForwarder (default: `5000`)
   - **Allowed User IDs** — comma-separated Telegram user IDs that are allowed to control the bot (leave empty to allow everyone)
   - **Bot Language** — `中文` or `English`
3. Fill in **Security Settings** to match what is configured in SmsForwarder:
   - `none` — no authentication
   - `sign` — HMAC-SHA256 signature; enter the shared secret
   - `rsa` — RSA encryption; paste the PEM public and private keys
4. Use **Test Connection** to verify the port and security settings are correct before saving
5. Tap **Save Settings**, then tap **Start** to launch the foreground service
6. Open Telegram and send `/start` to your bot

## Security Modes

| Mode | Description |
|------|-------------|
| `none` | No authentication — suitable for local-only use |
| `sign` | Each request is signed with HMAC-SHA256 using a shared secret |
| `rsa` | Request body is RSA-encrypted (PKCS#1 v1.5) |

## Architecture

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material3
- **Networking**: OkHttp (Telegram long-polling + SmsForwarder API)
- **Storage**: DataStore Preferences
- **Background**: Foreground Service with WakeLock

## Building

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## CI/CD

GitHub Actions automatically builds a debug APK on every push to `main`.  
Pushing a `v*` tag (e.g. `v1.0.0`) triggers a release build and creates a GitHub Release with the APK attached.
