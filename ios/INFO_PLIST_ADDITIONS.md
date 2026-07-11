# iOS Target — Required Configuration for CoreNFC

Apply these to the **iOS app target** (not a framework target). Without all
three, `NFCTagReaderSession` either fails to compile-link or crashes at
session start.

## 1. Info.plist key (mandatory — missing key = runtime crash)

```xml
<key>NFCReaderUsageDescription</key>
<string>The app scans student ID cards to record bus check-ins.</string>
```

## 2. Entitlement (mandatory — missing entitlement = session error -1)

In Xcode: *Signing & Capabilities → + Capability → Near Field Communication
Tag Reading*. This writes into the `.entitlements` file:

```xml
<key>com.apple.developer.nfc.readersession.formats</key>
<array>
    <string>TAG</string>
</array>
```

The matching capability must also be enabled on the App ID in the Apple
Developer portal (Certificates, Identifiers & Profiles → your App ID →
Near Field Communication Tag Reading), then regenerate provisioning profiles.

## 3. Linked framework

`CoreNFC.framework` — link as **Optional** (weak) if the app also targets
devices without NFC hardware; `CoreNfcScannerPort.isSupported` gates usage at
runtime via `NFCTagReaderSession.readingAvailable`.

## NOT needed

- `com.apple.developer.nfc.readersession.iso7816.select-identifiers` — only
  required to send APDUs to ISO 7816 applications. We read `tag.identifier`
  (the UID) directly from tag discovery, which needs no AID list.
- MFi certification — that applies to external accessories. This
  implementation uses the built-in antenna, which is exactly why the iOS
  engine is CoreNFC and not USB.

## Store review notes

- Devices: NFC tag reading requires iPhone 7 or later; `isSupported` returns
  false elsewhere (including iPads and the simulator) and the app must hide
  the scan feature accordingly — never show a button that dead-ends.
- The system NFC sheet is Apple UI: do not overlay, imitate, or auto-dismiss
  it. The ~60-second session limit and re-presented sheet on restart are
  platform behavior, not bugs to work around.
