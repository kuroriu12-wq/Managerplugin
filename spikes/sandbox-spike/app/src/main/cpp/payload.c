/* Stand-in for a plugin's native library. It is built into the APK only so the
   test has a real .so to copy somewhere else and load from a file descriptor. */
__attribute__((visibility("default")))
int payload_add(int a, int b) {
    return a + b + 1000;
}
