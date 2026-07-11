# HANDOVER GUIDE — `capacitor-nfc-adapter` into `studentsafe-staff-app`

Monday-morning execution plan. Follow top to bottom; every step has a
verification command. Nothing here touches production systems — all local
dev-machine work until you choose to build a release.

---

## 0. What you are integrating

A Capacitor 3 plugin delivering student-card UIDs in the strict
`uid_dec_reversed` contract format on both platforms:

```
raw bytes read from card :  FB 5D 82 29
reversed                 :  29 82 5D FB
onCardScanned delivers   :  { uid: "696409595" }
```

- **Android**: external USB Type-C NFC readers (CCID class readers, CH340/CP210x/FTDI
  serial bridges) via USB Host API. Plug & play, background polling.
- **iOS**: built-in antenna via CoreNFC (system scan sheet; students tap the iPhone).
- **Web / `ionic serve`**: safe no-op fallback, `isSupported()` reports `false`.

Core logic is pinned by `shared-test-vectors/uid_vectors.json` and the
toolchain-free harness: `node verification/verify.mjs` (69 checks).

---

## 1. Swap the `file:` dependency for the private GitHub URL

The repo currently installs via a local path (`file:../nfc-adapter`), which
**breaks `npm install` on any machine without that sibling folder**. Replace it.

In `studentsafe-staff-app`:

```bash
npm uninstall capacitor-nfc-adapter
npm install git+https://github.com/Bankkittirat-voova/capacitor-nfc-adapter.git --legacy-peer-deps
```

To pin an exact commit/tag (recommended for CI reproducibility):

```bash
npm install git+https://github.com/Bankkittirat-voova/capacitor-nfc-adapter.git#main --legacy-peer-deps
```

Because the repo is **private**, each developer/CI machine needs GitHub
credentials that can read it: a logged-in Git Credential Manager (default on
Windows Git), or an HTTPS token URL in CI
(`git+https://<TOKEN>@github.com/Bankkittirat-voova/capacitor-nfc-adapter.git`
— keep the token in a CI secret, never commit it).

**Verify:**

```bash
node -e "console.log(require.resolve('capacitor-nfc-adapter'))"
```

Expect a path inside `node_modules/capacitor-nfc-adapter`.

---

## 2. Sync the native platforms

```bash
npm run build        # Angular build -> www/ (cap sync requires it)
npx cap sync
```

**Verify:** output must list the plugin for BOTH platforms:

```
[info] Found N Capacitor plugins for android:
       ...
       capacitor-nfc-adapter@0.1.0
[info] Found N Capacitor plugins for ios:
       ...
       capacitor-nfc-adapter@0.1.0
```

and `android/capacitor.settings.gradle` must contain:

```groovy
include ':capacitor-nfc-adapter'
```

### Android build

```bash
cd android
gradlew.bat assembleDebug        # macOS/Linux: ./gradlew assembleDebug
```

Requires JDK 17+ and the Android SDK (Android Studio's bundled JBR works:
set `JAVA_HOME` to `<Android Studio>/jbr`). No extra Gradle edits needed —
the plugin brings its own JitPack repository entry for the
`usb-serial-for-android` driver dependency.

After a release build, confirm Play-compliance survived manifest merge:

```bash
aapt dump badging app-release.apk | findstr usb.host
```

Expect `uses-feature: name='android.hardware.usb.host'` **without** `required`
(merged as `required="false"` — keeps the app installable on non-OTG devices).

### iOS build (Mac machine)

```bash
npx cap sync ios     # runs pod install; CapacitorNfcAdapter pod appears in Podfile.lock
npx cap open ios
```

Then one-time target config (details in `ios/INFO_PLIST_ADDITIONS.md`):

1. `Info.plist`: add `NFCReaderUsageDescription`.
2. Signing & Capabilities: add **Near Field Communication Tag Reading**.

---

## 3. Prove the bridge works — `echo()` diagnostic

Before touching NFC hardware, verify the JS <-> native channel end to end.
Drop this into any Angular page/service:

```typescript
import { Component } from '@angular/core';
import { NfcAdapter } from 'capacitor-nfc-adapter';

@Component({ selector: 'app-nfc-diagnostic', template: '' })
export class NfcDiagnosticComponent {

  async testBridge(): Promise<void> {
    // 1. Echo: round-trips a string through the native layer.
    const { value } = await NfcAdapter.echo({ value: 'ping-2026' });
    console.log('echo returned:', value);        // "ping-2026" = bridge alive

    // 2. Capability probe (false on web/ionic serve, non-OTG Androids, NFC-less iPads).
    const { supported } = await NfcAdapter.isSupported();
    console.log('NFC supported on this device:', supported);
  }
}
```

On a real device (`npx cap run android`), the console must print
`echo returned: ping-2026`. If instead you see
`"NfcAdapter" plugin is not implemented on android`, re-run `npx cap sync`
and rebuild — the native project is stale.

---

## 4. Receive card scans — `onCardScanned`

```typescript
import { Injectable, NgZone } from '@angular/core';
import { PluginListenerHandle } from '@capacitor/core';
import { NfcAdapter } from 'capacitor-nfc-adapter';

@Injectable({ providedIn: 'root' })
export class NfcCheckInService {

  private handles: PluginListenerHandle[] = [];

  constructor(private zone: NgZone) {}

  async startNfcCheckIn(onUid: (uid: string) => void): Promise<void> {
    this.handles.push(
      NfcAdapter.addListener('onCardScanned', ({ uid }) => {
        // uid is the strict uid_dec_reversed string, e.g. "696409595" —
        // feed it into the SAME check-in path the RFID keyboard reader uses.
        this.zone.run(() => onUid(uid));   // plugin events arrive outside Angular's zone
      }),
      NfcAdapter.addListener('onReaderStateChanged', ({ state }) => {
        console.log('reader state:', state);   // DISCONNECTED / STANDBY / READING / ...
      }),
      NfcAdapter.addListener('onReaderError', (err) => {
        console.warn(`reader error [${err.code}] recoverable=${err.recoverable}:`, err.message);
      }),
    );
    await NfcAdapter.startScanning();
  }

  async stopNfcCheckIn(): Promise<void> {
    await NfcAdapter.stopScanning();
    this.handles.forEach((h) => h.remove());
    this.handles = [];
  }
}
```

Call `startNfcCheckIn(uid => this.existingCheckInLogic(uid))` on route enter,
`stopNfcCheckIn()` on route leave / logout.

**Contract sanity check with a known card:** a card whose raw UID is
`FB5D8229` must arrive as `"696409595"`. If your roster numbers don't match
what arrives, compare against `shared-test-vectors/uid_vectors.json` before
suspecting the plugin — the roster may store a different endianness.

---

## 5. Known limits / open items

| Item | Status |
|---|---|
| Brand A SDK readers | Hook present (`handler/branda/`), inert until VID/PID configured in `NfcModuleConfig` + `nfc_device_filter.xml` |
| iOS silent background scanning | Impossible by Apple design — system sheet shows, sessions ~60 s (auto-restarted). Communicate to operations. |
| On-device soak test | Pending: cold plug, hot plug, 100-tap soak, mid-tap unplug on the two most common fleet phones |
| Roster endianness confirmation | Pending: verify a sample of real production cards against stored numbers |

---

## 6. Rollback

Everything is additive. To back the integration out of the staff app:

```bash
npm uninstall capacitor-nfc-adapter
npx cap sync
```

No other files in the app change.
