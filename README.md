# `:nfc-adapter` — Dual-Platform NFC Scanning Module

Delivers student-card UIDs to the host app through one unified callback on
both platforms:

| Platform | Engine | Hardware |
|---|---|---|
| Android | USB Host API (CCID class 11 / serial bridges / Brand A SDK hook) | External USB Type-C readers, plug & play |
| iOS | CoreNFC `NFCTagReaderSession` (ISO 14443) | Built-in antenna — card taps the iPhone |

## The output contract — `uid_dec_reversed`

Every engine on every platform delivers the **decimal representation of the
reversed raw UID byte array**, no padding:

```
raw bytes read from card :  FB 5D 82 29
reversed                 :  29 82 5D FB
onCardScanned() receives :  "696409595"
```

Pinned by `shared-test-vectors/uid_vectors.json` (values computed with an
independent .NET BigInteger oracle) across three test lanes: Kotlin/JVM
(`src/test`), Swift/XCTest (`ios/Tests`) and the toolchain-free Node harness
(`archive/verify.mjs`, 69 checks — run with `node archive/verify.mjs`).

## Layout

```
src/main/kotlin/com/school/nfcadapter/
  api/            NfcScannerPort + NfcScanListener (the ONLY public surface)
  core/           UidNormalizer, DeviceRouter, ConnectionManager (generation
                  tokens, attach debounce, storm/brownout detection), ListenerProxy
  usb/            attach trampoline, detach receiver, permission coordinator
                  (API 31+ FLAG_MUTABLE + API 34 explicit-intent compliant)
  transport/      UsbTransport seam + AndroidUsbTransport (bulkTransfer)
  handler/ccid/   pure CCID protocol + edge-triggered polling engine
  handler/serial/ pure frame assembler/profiles + usb-serial-for-android wrapper
  handler/branda/ Brand A SDK hook (integration contract in the file header)
ios/Sources/      Swift twin: NfcScannerPort, UidNormalizer, CoreNfcScannerPort
ios/Tests/        XCTest suites (same golden vectors)
```

## Capacitor usage (`capacitor-nfc-adapter`)

The package is an autolinkable Capacitor 3 plugin: `android/` (Gradle library +
`@CapacitorPlugin NfcAdapterPlugin`), `ios/Plugin/` (Swift `CAPPlugin` bridge
registered via the `CAP_PLUGIN` macro, `CapacitorNfcAdapter.podspec` at package
root), `index.js` entry point using `registerPlugin('NfcAdapter')` with a no-op
web fallback for `ionic serve`.

```ts
import { NfcAdapter } from 'capacitor-nfc-adapter';

const handle = NfcAdapter.addListener('onCardScanned', ({ uid }) => checkIn(uid)); // "696409595"
await NfcAdapter.startScanning();
// later: await NfcAdapter.stopScanning(); handle.remove();
```

Install in the host app, then `npx cap sync` to wire the native projects
(`capacitor.settings.gradle` on Android, Podfile on iOS/macOS machines).

## Android integration (native, non-RN)

```kotlin
val scanner: NfcScannerPort = NfcAdapterModule.create(context)  // Noop on non-OTG devices
scanner.setListener(object : NfcScanListener {
    override fun onCardScanned(uidDecReversed: String) { checkInGateway.submit(USB_NFC, uidDecReversed) }
    override fun onReaderStateChanged(state: ReaderState) { /* standby badge */ }
    override fun onReaderError(error: ReaderError) { /* driver-facing hint */ }
})
scanner.start()   // route screen enter / login
scanner.stop()    // route screen exit / logout
```

Gradle: add `maven { url = uri("https://jitpack.io") }` to repositories
(for `usb-serial-for-android`). The library manifest already declares
`<uses-feature android:name="android.hardware.usb.host" android:required="false" />`
— verify it survives manifest merge with `aapt dump badging` on the release APK.

## iOS integration

```swift
let scanner: NfcScannerPort = CoreNfcScannerPort()   // or NoopScannerPort() where unsupported
scanner.setListener(listener)
scanner.start()   // presents the system NFC sheet
scanner.stop()
```

Required target config: see `ios/INFO_PLIST_ADDITIONS.md`
(`NFCReaderUsageDescription` + Tag Reading entitlement).

**Honest platform limits (by Apple design):** iOS shows the system scan sheet
whenever scanning is active — there is no silent background standby. Sessions
are capped at ~60 s; the port auto-restarts them while `start()` is in effect
and uses `restartPolling()` so a whole line of students can tap within one
session. If the fleet workflow requires invisible always-on scanning, that is
Android-only; communicate this to operations before hardware purchasing.

## Testing without hardware

- `archive/verify.mjs` — runs anywhere Node exists; exercises normalizers,
  CCID validation chain, engine state machines, serial assembly, router, and
  the vibration-storm/generation-token model.
- Kotlin suite — pure classes have zero Android imports; runs as plain JVM
  unit tests in CI (`ScriptedUsbTransport` scripts taps/flicks/disconnects).
- Swift suite — `CoreNfcScannerPort.handleDiscoveredIdentifier(_:)` is the pure
  seam; tests inject mock `Data` identifiers, valid and corrupted.

## Open items before field rollout

1. Brand A `.aar`: follow the contract in `handler/branda/BrandAHandlerHook.kt`;
   set `brandAVendorId`/`brandAProductIds` in `NfcModuleConfig` and add the
   VID/PID to `res/xml/nfc_device_filter.xml`.
2. Confirm with the roster/backend owner that stored card numbers match
   `uid_dec_reversed` (this format was chosen to match keyboard-emulator
   output; verify endianness against a sample of real production cards).
3. Tier-4 on-device smoke: one CCID reader + one CH340 reader on the two most
   common fleet phones (cold plug, hot plug, "Always open" permission path,
   100-tap soak, mid-tap unplug).
