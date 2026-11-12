# app/libs

Matter library jars, built from connectedhomeip tag v1.6.1.0 (target `android-arm64-chip-tool`).
Do not edit by hand: run `tools/copy-matter-libs.sh <path to connectedhomeip checkout>`.

Files expected here:

- CHIPController.jar
- CHIPInteractionModel.jar
- CHIPClusters.jar
- CHIPClusterID.jar
- OnboardingPayload.jar
- libMatterTlv.jar
- libMatterJson.jar
- AndroidPlatform.jar
- MATTER_VERSION.txt (written by the script: output of `git describe --tags`)

The jars are the pinned library. Commit them.
