import { registerPlugin, WebPlugin } from '@capacitor/core';

/**
 * Browser / `ionic serve` fallback: NFC hardware never exists on web, so
 * every method is a safe no-op and isSupported() reports false. WebPlugin
 * supplies addListener/removeAllListeners so listener wiring never throws.
 */
class NfcAdapterWeb extends WebPlugin {
  async isSupported() {
    return { supported: false };
  }
  async startScanning() {}
  async stopScanning() {}
  async echo(options) {
    return { value: (options && options.value) || '' };
  }
}

/**
 * Unified dual-platform NFC scanner (Capacitor 3 plugin).
 *
 * Every card scan delivers the strict `uid_dec_reversed` contract string:
 * decimal of the reversed raw UID bytes (raw FB5D8229 -> "696409595").
 *
 * Android: external USB Type-C readers (plug & play, background polling).
 * iOS: built-in antenna via CoreNFC (system scan sheet; students tap the phone).
 *
 * Usage:
 *   import { NfcAdapter } from '@voova/capacitor-nfc-adapter';
 *
 *   const handle = NfcAdapter.addListener('onCardScanned', ({ uid }) => checkIn(uid));
 *   await NfcAdapter.startScanning();
 *   // later: await NfcAdapter.stopScanning(); handle.remove();
 *
 * Events:
 *   "onCardScanned"        -> { uid: string }            (uid_dec_reversed)
 *   "onReaderStateChanged" -> { state: string }          (DISCONNECTED/.../STANDBY/READING)
 *   "onReaderError"        -> { code, message, recoverable }
 */
export const NfcAdapter = registerPlugin('NfcAdapter', {
  web: () => new NfcAdapterWeb(),
});

export default NfcAdapter;
