# SignalR 9.x uses Gson reflection for handshake and hub protocol messages but does not
# publish consumer R8 rules. Preserve its protocol model classes and serialized field names.
-keep class com.microsoft.signalr.** { *; }
