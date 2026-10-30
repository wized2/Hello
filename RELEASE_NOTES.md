# Hello? 1.0.2

- **Voice path rewrite**: multicast + broadcast + unicast to discovered peer IPs
- Discovery beacons include local IPv4 so peers can reach each other without relying only on multicast
- Separate TX/RX sockets, Wi‑Fi + multicast locks, speakerphone / IN_COMMUNICATION mode
- AudioRecord tries VOICE_COMMUNICATION → MIC → DEFAULT
- **UI**: scrollable form, IME padding, PTT pinned above keyboard, smaller hold button
