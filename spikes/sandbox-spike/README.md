# Sandbox spike

A debug-only test app. It answers the questions the plugin plans left open:

- **Button 1, JS tests:** runs the Task 1 sandbox and budget checks of `manager-plugin-scripts-plan.md` on two JavaScript engines, Rhino and QuickJS, and prints PASS, FAIL, INFO or HANG for each.
- **Button 2, native tests:** N0 to N4 of `manager-plugin-native-sandbox.md`: what an isolated process can reach, whether a `.so` that is not in the APK can be loaded into it, how fast a 1080p frame goes through shared memory, and whether a spinning plugin can be killed.
- **Button 3, stress tests:** allocate far too much memory on purpose. It may close the app. The log is saved to a file and shows again when the app reopens.

It is not part of AbsentMotion and no user data is touched. The app has the INTERNET permission on purpose, so a blocked network inside the isolated process is a real result.

## Use

1. Install the APK from the latest `spike-*` prerelease of this repository.
2. Open it, tap button 1, wait until the log ends with `-- js finished`.
3. Tap button 2, wait for `-- native finished`.
4. Tap `Copy log` and send the text.
5. Only then tap button 3.

If a line says HANG, the engine did not stop the script: force-stop the app from Android settings before continuing.

## Build

GitHub Actions builds it on every push to a `spike/*` branch (`.github/workflows/spike-apk.yml`) and attaches the debug APK to a prerelease. There is no Gradle wrapper: the workflow installs Gradle 8.11.1.
