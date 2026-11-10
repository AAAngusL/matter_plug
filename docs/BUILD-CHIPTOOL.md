# Step 1: Build CHIPTool for Android

Goal: produce `app-debug.apk` for CHIPTool plus the Matter controller libraries, install the APK on the phone, and use it to pair and toggle the plug.

These steps follow the Matter project's Android build guide. Version numbers differ between release tags, so **the guide inside the tag you check out is the authority**: `docs/guides/android_building.md`. Where this file and that guide disagree, follow the guide.

Items marked (verify) are from memory and were not checked against the repository.

## 0. What you need

- A Linux x86-64 machine (Ubuntu or Debian), about 30 GB of free disk, 8 GB of RAM or more.
- An Android phone with an arm64 CPU, USB debugging enabled, and a USB cable.
- One to two hours for the first build, most of it downloading.

## 1. Install system packages

```bash
sudo apt-get update
sudo apt-get install -y git gcc g++ pkg-config libssl-dev libdbus-1-dev \
  libglib2.0-dev libavahi-client-dev ninja-build python3-venv python3-dev \
  python3-pip unzip curl libgirepository1.0-dev libcairo2-dev libreadline-dev \
  default-jdk adb
```

On very new releases some package names have changed (for example `libgirepository1.0-dev` may be `libgirepository-2.0-dev`). Install the nearest equivalent.

## 2. Install the Android SDK and NDK

The guide asks for Android SDK platform 26 and NDK 23.2.8568313 (check the numbers in your tag's guide).

Easiest: install Android Studio, then in **Tools > SDK Manager** install:

- SDK Platforms: Android 8.0 (API 26)
- SDK Tools: Android SDK Command-line Tools, NDK (Side by side) 23.2.8568313 (tick "Show package details" to pick the version)

Without Android Studio, download the "command line tools only" package from https://developer.android.com/studio and run:

```bash
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-26" "ndk;23.2.8568313"
```

Then set the environment variables (add them to `~/.bashrc`):

```bash
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/23.2.8568313
```

## 3. Install the Kotlin compiler

The build needs `kotlinc` on the `PATH`. The apt package is usually too old; use the release zip (1.8.10 or newer):

```bash
cd /usr/lib
sudo curl -LO https://github.com/JetBrains/kotlin/releases/download/v1.8.10/kotlin-compiler-1.8.10.zip
sudo unzip kotlin-compiler-1.8.10.zip && sudo rm kotlin-compiler-1.8.10.zip
export PATH=$PATH:/usr/lib/kotlinc/bin     # also add to ~/.bashrc
kotlinc -version
```

## 4. Check the Java version

Older tags used a Gradle version that does not run on Java 17; newer tags may require 17. Read the "Gradle & JDK Version" section of your tag's guide and point `JAVA_HOME` at a matching JDK. The JDK bundled with Android Studio (`<studio>/jbr`) is a safe choice.

```bash
java -version
export JAVA_HOME=/path/to/matching/jdk
```

## 5. Get the source at a release tag

Pick the newest stable tag from https://github.com/project-chip/connectedhomeip/releases (the v1.5 line at the time of writing; `v1.5.1.0` is used below as an example).

```bash
mkdir -p ~/src && cd ~/src
git clone --depth 1 --branch v1.5.1.0 https://github.com/project-chip/connectedhomeip.git
cd connectedhomeip
./scripts/checkout_submodules.py --shallow --platform android
```

The submodule script fetches only what Android needs. A plain `git submodule update --init --recursive` also works but downloads many gigabytes for other platforms.

## 6. Bootstrap the build environment

```bash
source scripts/bootstrap.sh
```

This downloads the project's own toolchain (Python environment, GN, Ninja) and takes a while the first time. In later shell sessions use the faster `source scripts/activate.sh`.

## 7. Build

```bash
./scripts/build/build_examples.py --target android-arm64-chip-tool build
```

Outputs:

- APK: `out/android-arm64-chip-tool/outputs/apk/debug/app-debug.apk`
- Controller libraries for your own app later (verify paths): `out/android-arm64-chip-tool/lib/*.jar` and `out/android-arm64-chip-tool/lib/jni/arm64-v8a/*.so`

## 8. Install on the phone

1. On the phone: **Settings > About phone**, tap **Build number** seven times, then enable **USB debugging** under Developer options.
2. Connect the cable and accept the prompt on the phone.

```bash
adb devices      # the phone should be listed as "device"
adb install out/android-arm64-chip-tool/outputs/apk/debug/app-debug.apk
```

## 9. Go/no-go test with the plug (plan step 2)

1. Factory-reset the plug with its button (usually a long press until the LED blinks fast; check its manual). It is currently paired to Apple Home, so the printed QR code is inactive until the reset.
2. Put the phone on the 2.4 GHz Wi-Fi network; turn on Bluetooth and Location.
3. Open CHIPTool and grant the camera, Bluetooth and location permissions.
4. Choose the option to provision a device with Wi-Fi (verify the label), scan the plug's QR code, and enter the Wi-Fi name and password.
5. Wait for commissioning to complete.
6. Open the on/off cluster screen (verify the label) and send On, Off and Toggle.

**Go:** the plug switches. Record the tag, the node ID CHIPTool assigned, and anything odd in the log.

**No-go:** capture the log and note the stage that failed:

```bash
adb logcat -s chip:V CHIPTool:V '*:E' > chiptool-log.txt
```

## Fallback: build in the project's container

If the bootstrap or build fails on a very new distribution (new Python and compiler versions are the usual cause), use the project's Android build image, which already contains the SDK, NDK, Kotlin and JDK at the right versions:

```bash
docker run --rm -it -v ~/src/connectedhomeip:/workspace -w /workspace \
  ghcr.io/project-chip/chip-build-android:latest bash
# inside the container:
source scripts/bootstrap.sh
./scripts/build/build_examples.py --target android-arm64-chip-tool build
```

Use the image version that matches the tag (verify: the tag's `.github/workflows` files name the image version CI used). The APK appears in the same `out/` folder on the host.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `kotlinc: command not found` | Step 3 `PATH` not set in this shell |
| Gradle fails with "Unsupported class file major version" | Wrong JDK; see step 4 |
| `ANDROID_HOME` or NDK not found | Step 2 variables not set, or a different NDK version installed |
| Missing files under `third_party/` | Submodules incomplete; rerun the checkout script in step 5 |
| `adb devices` shows "unauthorized" | Accept the USB debugging prompt on the phone |
| CHIPTool never finds the plug | Plug not reset or not in pairing mode; Bluetooth or Location off |
| Pairing fails at the network step | Wrong Wi-Fi password, or a 5 GHz-only network |
| Pairing fails at attestation | The app is rejecting the plug's certificate; note the tag and log, this needs a code-level look |
