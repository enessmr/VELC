# vgmstream Android decoder

The `libvgmstream_jni.so` binaries in `application/app/src/main/jniLibs` and
`VgmNative.kt` in `application/vlc-android/src/me/ayra/vgmstream` come from
[AyraHikari/vgmstream-android v0.1.0](https://github.com/AyraHikari/vgmstream-android/releases/tag/v0.1.0).
The binaries were extracted from that release's `sample.apk`. They cover
arm64-v8a, armeabi-v7a, and x86_64. The wrapper is licensed under Apache 2.0;
see `APACHE-2.0.txt`.

The native library includes [vgmstream](https://github.com/vgmstream/vgmstream),
whose copyright and permission notice is in `VGMSTREAM-COPYING.txt`.
