#!/data/data/com.termux/files/usr/bin/bash
cd ~/PocketMC || exit 1
export JAVA_HOME=$PREFIX/lib/jvm/java-17-openjdk
export ANDROID_HOME=$HOME/android-sdk
# Kaldırılmış eski kaynak dosyalarını temizle (unzip silmiyor)
for f in Archive Skin; do rm -f app/src/main/java/com/pocketmc/app/$f.kt; done
rm -f /sdcard/Download/pocketmc.apk /sdcard/Download/pocketmc-forplayers.apk
~/gradle-8.9/bin/gradle --no-daemon assembleDebug > ~/derleme.txt 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  echo "DERLEME BASARISIZ. Hatalar:"
  grep -E "^e: |error:|FAILED|What went wrong" -A2 ~/derleme.txt | head -40
  exit 1
fi
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/pocketmc.apk && ls -lh /sdcard/Download/pocketmc.apk
cp player/build/outputs/apk/debug/player-debug.apk /sdcard/Download/pocketmc-forplayers.apk && ls -lh /sdcard/Download/pocketmc-forplayers.apk
