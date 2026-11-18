# Wireless connection v2 — ADB SMART

## Goal
Replace the notification-driven pairing implementation and its global mDNS state with one predictable, foreground UI flow based on the documented LADB workflow and Shizuku's local ADB service validation.

## New flow

1. The user opens Android's Wireless Debugging / Pair using pairing code screen.
2. ADB SMART stays visible beside Settings in split-screen or a floating window. No app notification, BroadcastReceiver, foreground pairing service, or background pairing discovery is used.
3. The user enters the pairing port and six-digit pairing code in ADB SMART. The app does not attempt to infer either value.
4. ADB SMART runs the bundled LADB \`adb pair localhost:<pairing-port>\` client directly from the connection screen.
5. After pairing completes, the pairing ADB server is shut down once, following LADB's pairing lifecycle.
6. A new, one-shot \`_adb-tls-connect._tcp\` mDNS scan is created. It has no singleton, cached endpoint, or scanner started by an Activity/ViewModel. It validates that the resolved IP belongs to this device and that the matching port is occupied on 127.0.0.1, following Shizuku's \`AdbMdns\` check.
7. ADB SMART starts the ADB server, connects to \`127.0.0.1:<fresh-connect-port>\`, verifies the exact serial in \`adb devices\`, runs \`adb shell id\`, and only then reports connected.
8. If the shell later dies, the app attempts one clean reconnect. If it fails, it stops and asks the user to reconnect; it does not endlessly restart ADB or toggle Wireless Debugging.

## Separate backends
- Wireless ADB uses the bundled LADB client and the flow above.
- Shizuku is an explicit, separate backend. When Shizuku's binder is alive, ADB SMART may connect to its UserService. A failed Shizuku bind must not silently start a competing local ADB server. The two backends are never started in parallel.

## Removed legacy components
- Pairing notifications and RemoteInput handling
- PairingReplyReceiver
- PairingService and PairingDiscoveryService
- Pairing-port NSD discovery
- Process-wide DnsDiscover singleton, pre-pair endpoint caching, and ViewModel-started discovery
- Repeated polling of pairing service state from the UI

## Timing and failure rules
- Pairing waits for the actual \`adb pair\` result, with a bounded timeout.
- The post-pair mDNS scan is independent and has a bounded 12-second discovery window. This is separate from the ADB server/connect/shell verification budget.
- Do not promise a universal five-second first connection: Android Settings, NSD and TLS pairing are asynchronous and can exceed that. Once a fresh port is found, local ADB connect and shell verification are bounded.
- Error messages identify the failed stage: pair, mDNS discovery, ADB server start, ADB connect, device authorization, or shell verification.

## Why this design
- LADB's published setup requires the pairing dialog to remain open while the user enters the pairing port and code, using split-screen or a floating window.
- Android's documented workflow separates \`adb pair <ip>:<port>\` from later device connection.
- Shizuku's source uses a distinct \`_adb-tls-connect._tcp\` discovery and validates the local port listener instead of reusing a previously discovered endpoint.
- No external settings state or notification lifecycle is required to keep the pairing flow alive; the Activity remains in the foreground alongside Settings.
