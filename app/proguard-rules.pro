# Native AirPlay receiver calls back into these by name.
-keepclasseswithmembernames class com.tvkamar.launcher.airplay.RaopServer {
    native <methods>;
}
-keepclassmembers class com.tvkamar.launcher.airplay.RaopServer {
    private void onVideo(byte[], int, long);
    private void onVideoStop();
    private void onAudio(short[], long);
    private void onVolume(float);
}
