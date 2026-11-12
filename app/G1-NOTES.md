# G1 notes: Battery Guard logic package

## Files created (all under app/, no existing file touched)

Main (`app/src/main/java/app/matterplug/guard/logic/`): GuardSettings.kt, GuardPolicy.kt (GuardAction, GuardPolicy, BatteryMath), AlertTypes.kt, AlertPolicy.kt, AlertMessages.kt, AlertJson.kt, AlertAddress.kt, Codecs.kt (AlertStateCodec, QueueCodec).

Tests (`app/src/test/java/app/matterplug/guard/logic/`): GuardPolicyTest, BatteryMathTest, GuardSettingsTest, AlertAddressTest, AlertMessagesTest, AlertJsonTest, CodecsTest, AlertPolicyTest (scenarios 1-17, one test each).

No file in guard/logic imports android.*. No library added; JUnit 4 only.

## Tests run

Not run through Gradle (the Android Gradle plugin and its dependencies are not available offline here). Instead I compiled the 8 main and 8 test files with the Kotlin compiler 2.0.21 found inside Gradle 8.14.3's lib directory, and ran them with JUnitCore (JUnit 4.13.2, same jar). Result: **75 tests, 0 failures**. The project itself uses Kotlin 2.1.10, JDK 17; I did not run with those, and `:app:testDebugUnitTest` itself was NOT run.

## Deviations and findings

1. Package statements and imports were added to every file (the document's snippets omit them). `Codecs.kt` imports `java.util.Base64`.
2. `AlertPolicy`: the `for ... when` with `if` branches was written with braces; behaviour as in the document. The returned `active` list is a copy (`toList()`), not the mutable list.
3. `AlertJson.q` and the codecs completed as the document describes. Control characters below 0x20 use `\u00xx` (lower-case hex).
4. `AlertJson.isoTime` prints `Z` (not `+00:00`) when the zone is UTC, because that is what `ISO_OFFSET_DATE_TIME` does. Valid RFC 3339, and the Go receiver accepts it. A test records this. Not an issue on the phone unless it is set to UTC.
5. `AlertStateCodec`: a detail of `-` (the null marker) cannot clash with an encoded detail because Base64 never gives a one-character result; a test covers a detail whose text is `-`. `decode` also skips an `A` line with an unknown kind, a bad number or a bad `wantedOn` field, and an `F` line with negative or non-numeric counts. If the `F` line is missing, counts are 0 and `A` lines are still read.
6. `QueueCodec.decode` skips empty lines and entries that are not valid Base64. An empty body would be encoded as an empty line and lost on decode; bodies are JSON, so this cannot happen in practice.
7. `AlertAddress` relies on `java.net.URI`, so a host with an underscore (for example `http://my_host/`) has a null `host` and is refused. Left as the document has it.
8. Behaviour noted, not changed: if `PowerWait(false)` is in the events but the final `pluggedIn` is true, `AlertPolicy` emits `Started` and `Recovered` for NotCharging in the same call. The G4 caller should pass the plugged-in state read after the wait (the document's `final` reading does).
9. Scenario 12 ("no settings parameter") is tested by reflection: `apply` has five parameters and none is `GuardSettings`.
10. Scenarios 9 and 17 use timings I chose (alerts started at different minutes, so reminders fall at different calls); the document gives none.

## Command for the build container

Same container, mounts and environment as step 3 of `tools/first-build.sh`, with the test task. Requires that step 2 has run (app/libs has the Matter jars), because the whole app must compile. Run on the host in the app root (`/home/aliu/work/matter_plug/app`), after `first-build.sh` has run once (so the image, Gradle cache and wrapper exist):

```
cd /home/aliu/work/matter_plug/app
C=${MATTER_BUILD_CACHE:-$HOME/.cache/matter_plug_build}
docker run --rm -i --user "$(id -u):$(id -g)" \
  -e HOME=/home/builder -e GRADLE_USER_HOME=/gradle-home -e ANDROID_USER_HOME=/home/builder/.android \
  -e ANDROID_HOME=/sdk-cache/sdk -e ANDROID_SDK_ROOT=/sdk-cache/sdk \
  -v "$PWD:/work/app" -v "$C/gradle:/gradle-home" -v "$C/home:/home/builder" -v "$C/android-sdk:/sdk-cache" \
  -w /work/app \
  ghcr.io/project-chip/chip-build-android:<IMAGE_TAG> \
  ./gradlew --no-daemon --console=plain :app:testDebugUnitTest --tests 'app.matterplug.guard.logic.*'
```

Use `podman` with `--userns=keep-id` added if you use podman. `<IMAGE_TAG>` is the tag `first-build.sh` printed (the chip-build-android version in connectedhomeip's .github/workflows). If you set MATTER_SDK_MODE=licenses, drop the two ANDROID_HOME flags and the android-sdk mount as the script does. Reports appear in `app/build/reports/tests/testDebugUnitTest/index.html`.

If you only want to check the logic without the Matter jars, the 16 files compile with plain kotlinc and run with JUnit 4 (that is how the 75 tests above were run).
