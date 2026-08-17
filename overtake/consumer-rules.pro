# Consumer ProGuard/R8 rules shipped to apps that depend on Overtake.
# The NotificationListenerService is referenced from the merged AndroidManifest; keep it so R8 in the
# host app never strips the class the platform instantiates by name.
-keep class dev.overtake.NowPlayingListener { *; }
