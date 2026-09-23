# Set up BigFatFish

BigFatFish shows an animated character near the mouse pointer on Android 16. It needs an accessibility service and a helper that you start with Android Debug Bridge (ADB).

## What you need

- An Android 16 device with a mouse or touchpad.
- A computer with Java Development Kit (JDK) 17, ADB, Android SDK platform 36, and Android SDK Build Tools 36.0.0.
- USB debugging enabled on the device. Connect the device to the computer and accept its debugging prompt.

The Android build must allow passive mouse observation. BigFatFish does not need root access.

## Build the app and helper

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your Android SDK path. You can also set `sdk.dir` in `local.properties`.

On Linux or macOS, run:

```sh
./gradlew :app:assembleDebug :helper:helperJar
```

On Windows, run:

```powershell
.\gradlew.bat :app:assembleDebug :helper:helperJar
```

These commands create `app/build/outputs/apk/debug/app-debug.apk` and `helper/build/outputs/helper.jar`.

## Start BigFatFish

1. Run `adb devices`. Check that your device is listed and authorized.
2. Install the app:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Open BigFatFish on the device.
4. Tap **Accessibility setup**. Enable the BigFatFish service in Android Accessibility settings.
5. Start the helper from the repository root. Use the command for your computer:

   Linux or macOS:

   ```sh
   HELPER_JAR=helper/build/outputs/helper.jar ./scripts/start-helper.sh
   ```

   Windows PowerShell:

   ```powershell
   $env:HELPER_JAR = (Resolve-Path .\helper\build\outputs\helper.jar).Path
   .\scripts\start-helper.ps1
   ```

6. Wait for the `READY` message. Return to the app and tap **Enable BigFatFish**.
7. Move the mouse. The character follows the pointer.

Start the helper again after each full device reboot. For multiple devices, add `-s SERIAL` to both the `adb install` command and the launcher command. For example, run `./scripts/start-helper.sh -s SERIAL` with `HELPER_JAR` set.

## Change the character

Use **Appearance**, **Motion**, and **Idle** in the app to change the character. Use **Stop BigFatFish** to stop observation. The app saves your settings.

To use your own artwork, tap **Import artwork** and select a ZIP file. Put `pack.json` and the PNG images at the root of the ZIP file. For example:

```json
{
  "version": 1,
  "name": "My character",
  "author": "Your name",
  "license": "Your artwork terms",
  "attachment": [0.5, 0.1],
  "states": {
    "active": [{"file": "active.png", "durationMs": 100}],
    "sleep": [{"file": "sleep.png", "durationMs": 100}],
    "react": [{"file": "react.png", "durationMs": 100}]
  }
}
```

`active` and `sleep` are required. `react` is optional. Each frame needs a PNG file and a duration from 16 to 2000 ms. All images must have the same dimensions, from 8 to 512 pixels on each side. Neither side can be more than four times the other. Use `attachment` to set the point that connects the character to the pointer. Each value must be from 0 to 1. The first value sets the horizontal position. The second value sets the vertical position.

Tap **Use built-in** to return to the included artwork. Imported artwork stays on your device.

## If setup fails

- If the app asks for accessibility setup, enable its service in Android Accessibility settings.
- If the app asks you to start the helper, run the launcher again. Do this after each full reboot.
- If the app says the service is not running, turn its service off and on in Android Accessibility settings.
- If the launcher reports an error, open the app first. Then check the helper log with `adb shell cat /data/local/tmp/bigfatfish-helper.log`.
- If the helper reports that passive mouse observation is disabled, the Android build does not support this feature.

BigFatFish does not need network access. It does not read screen content or keyboard input, and it does not inject clicks. See [NOTICE](NOTICE) for artwork ownership and third-party notices.
