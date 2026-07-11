#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Registers the Swift NfcAdapterPlugin with the Capacitor runtime as "NfcAdapter".
CAP_PLUGIN(NfcAdapterPlugin, "NfcAdapter",
    CAP_PLUGIN_METHOD(echo, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(startScanning, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(stopScanning, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(isSupported, CAPPluginReturnPromise);
)
