# Consumer ProGuard rules for :nfc-adapter
# Capacitor plugin classes are reached reflectively by the Capacitor runtime.
-keep class com.school.nfcadapter.capacitor.** { *; }
