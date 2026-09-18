# Dev1Fonzz — Production Clean Mode proguard rules
-keepattributes JavascriptInterface
-keepclassmembers class com.dev1fonzz.fareezonzzmms.* {
   public *;
}
-keep class com.dev1fonzz.fareezonzzmms.DiagnosticsBridge { *; }
-keep class com.dev1fonzz.fareezonzzmms.AnnouncementPollWorker { *; }
