import type { PluginListenerHandle } from '@capacitor/core';

export type ReaderStateName =
  | 'DISCONNECTED'
  | 'PERMISSION_PENDING'
  | 'INITIALIZING'
  | 'STANDBY'
  | 'READING'
  | 'ERROR';

export interface NfcReaderError {
  code: string;
  message: string;
  recoverable: boolean;
}

/** Diagnostic snapshot of a freshly attached USB reader (fired before the
 *  permission prompt). For a debug/pilot "which reader, which path" indicator —
 *  not part of the scan contract. */
export interface NfcReaderAttached {
  vid: number;
  pid: number;
  vidHex: string;
  pidHex: string;
  /** Protocol path the router chose, e.g. "CCID", "PN532_SERIAL", "SERIAL:CH340". */
  route: string;
  product: string | null;
  manufacturer: string | null;
}

export interface NfcAdapterPlugin {
  /** { supported: false } on devices with no USB Host (Android) / no NFC (iOS) and on web. */
  isSupported(): Promise<{ supported: boolean }>;
  startScanning(): Promise<void>;
  stopScanning(): Promise<void>;

  /** Bridge diagnostic: resolves with the same value it was called with. */
  echo(options: { value: string }): Promise<{ value: string }>;

  /** uid: decimal of the reversed raw UID bytes ("uid_dec_reversed"), e.g. "696409595". */
  addListener(
    eventName: 'onCardScanned',
    listener: (event: { uid: string }) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
    eventName: 'onReaderStateChanged',
    listener: (event: { state: ReaderStateName }) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
    eventName: 'onReaderError',
    listener: (event: NfcReaderError) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  /** A USB reader was enumerated and routed. Diagnostic only. */
  addListener(
    eventName: 'onReaderAttached',
    listener: (event: NfcReaderAttached) => void,
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  removeAllListeners(): Promise<void>;
}

export declare const NfcAdapter: NfcAdapterPlugin;
export default NfcAdapter;
