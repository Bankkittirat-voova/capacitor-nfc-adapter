# Consumer ProGuard rules for :nfc-adapter
# Capacitor plugin classes are reached reflectively by the Capacitor runtime.
-keep class com.school.nfcadapter.capacitor.** { *; }

# usb-serial-for-android's UsbSerialProber discovers drivers via reflection:
# it calls the static getSupportedDevices() and the UsbDevice constructor on
# each driver class. R8 in a minified host app strips both unless kept —
# serial-bridge readers would then fail ONLY in release builds.
-keepclassmembers class * extends com.hoho.android.usbserial.driver.UsbSerialDriver {
    public static java.util.Map getSupportedDevices();
    public <init>(android.hardware.usb.UsbDevice);
}
-keep class * extends com.hoho.android.usbserial.driver.UsbSerialDriver
