<h1 align="center">Hello?</h1>

<p align="center">
  <b>Offline push-to-talk for Android</b><br/>
  Same Wi‑Fi or hotspot · No accounts · No servers
</p>

<p align="center">
  <a href="https://github.com/wized2/Hello/actions/workflows/build.yml"><img src="https://github.com/wized2/Hello/actions/workflows/build.yml/badge.svg" alt="Build"/></a>
  <a href="https://github.com/wized2/Hello/releases/latest"><img src="https://img.shields.io/github/v/release/wized2/Hello?label=release" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT"/></a>
  <img src="https://img.shields.io/badge/Android-8%2B-green.svg" alt="Android 8+"/>
</p>

<p align="center">
  <a href="https://github.com/wized2/Hello/releases/latest"><b>Download APK</b></a>
</p>

## What it is

**Hello?** is a walkie-talkie style app: hold the mic button to talk; release to listen.  
Everyone on the **same room code** and **same local network** hears the transmission.

| Link type | Supported | Notes |
|-----------|-----------|--------|
| Same Wi‑Fi | Yes | UDP broadcast + NSD discovery + unicast PCM audio |
| Phone hotspot | Yes | Clients on the hotspot join the same room |
| Bluetooth | Optional 1:1 | Host mode for a nearby peer when Wi‑Fi is down |
| Internet / cellular | **No** | Needs a signaling or relay server — out of scope |

Inspired by classic LAN walkie-talkie designs (UDP multicast group PTT), not a VoIP account service.

## How to use

1. Install on two or more phones.  
2. Connect them to the **same Wi‑Fi** or one phone’s **hotspot**.  
3. Set the **same room code**.  
4. Grant microphone (and notifications on Android 13+).  
5. Hold **Hold to talk**.

## Privacy

- No accounts, analytics, or cloud backend.  
- Audio stays on the local link (TTL=1 multicast).  
- Room code only filters who you hear on that LAN.

## Build

```bash
./gradlew assembleRelease
```

CI builds debug + release APKs and publishes a GitHub Release on `main`.

## Related

| Project | What |
|---------|------|
| [Fern](https://github.com/wized2/Fern) | System monitor |
| [Latch](https://github.com/wized2/Latch) | Local automation |

## License

MIT
