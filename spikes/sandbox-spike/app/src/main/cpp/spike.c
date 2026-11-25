#include <jni.h>
#include <android/dlext.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

#define OUT_SIZE 4096

typedef int (*payload_add_fn)(int, int);

static void append(char* out, const char* fmt, const char* a, int rc, int err) {
    size_t len = strlen(out);
    if (rc >= 0) {
        snprintf(out + len, OUT_SIZE - len, "    %s: ALLOWED\n", a);
    } else {
        snprintf(out + len, OUT_SIZE - len, "    %s: blocked errno=%d (%s)\n", a, err, strerror(err));
    }
    (void)fmt;
}

#define CHECK(name, expr)        \
    do {                         \
        int rc_ = (expr);        \
        int err_ = errno;        \
        append(out, "", name, rc_, err_); \
    } while (0)

JNIEXPORT jstring JNICALL
Java_app_absentmotion_spike_Native_probe(JNIEnv* env, jobject self, jstring jdir) {
    (void)self;
    char out[OUT_SIZE];
    const char* dir = (*env)->GetStringUTFChars(env, jdir, NULL);
    snprintf(out, OUT_SIZE, "    uid=%d pid=%d\n", (int)getuid(), (int)getpid());

    int fd;
    fd = open(dir, O_RDONLY | O_DIRECTORY);
    CHECK("open app files dir", fd);
    if (fd >= 0) close(fd);

    fd = open("/storage/emulated/0", O_RDONLY | O_DIRECTORY);
    CHECK("open /storage/emulated/0", fd);
    if (fd >= 0) close(fd);

    fd = open("/data/local/tmp", O_RDONLY | O_DIRECTORY);
    CHECK("open /data/local/tmp", fd);
    if (fd >= 0) close(fd);

    fd = open("/proc/self/status", O_RDONLY);
    CHECK("open /proc/self/status", fd);
    if (fd >= 0) close(fd);

    CHECK("access /system/bin/sh (exec)", access("/system/bin/sh", X_OK));

    int s = socket(AF_INET, SOCK_STREAM, 0);
    CHECK("create TCP socket", s);
    if (s >= 0) {
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof addr);
        addr.sin_family = AF_INET;
        addr.sin_port = htons(53);
        inet_pton(AF_INET, "1.1.1.1", &addr.sin_addr);
        fcntl(s, F_SETFL, fcntl(s, F_GETFL, 0) | O_NONBLOCK);
        int rc = connect(s, (struct sockaddr*)&addr, sizeof addr);
        int err = errno;
        if (rc < 0 && err == EINPROGRESS) {
            rc = 0; /* connection attempt started: network is reachable from here */
        }
        append(out, "", "connect to 1.1.1.1:53", rc, err);
        close(s);
    }

    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    return (*env)->NewStringUTF(env, out);
}

static void try_open(char* out, const char* label, void* h) {
    size_t len = strlen(out);
    if (!h) {
        snprintf(out + len, OUT_SIZE - len, "    %s: FAILED %s\n", label, dlerror());
        return;
    }
    payload_add_fn f = (payload_add_fn)dlsym(h, "payload_add");
    snprintf(out + len, OUT_SIZE - len, "    %s: OK, payload_add(2,3)=%d (expected 1005)\n", label,
             f ? f(2, 3) : -1);
}

JNIEXPORT jstring JNICALL
Java_app_absentmotion_spike_Native_loadPayload(JNIEnv* env, jobject self, jint fd) {
    (void)self;
    char out[OUT_SIZE];
    out[0] = 0;

    android_dlextinfo info;
    memset(&info, 0, sizeof info);
    info.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    info.library_fd = fd;
    try_open(out, "android_dlopen_ext(library fd)", android_dlopen_ext("payload_from_fd.so", RTLD_NOW, &info));

    char path[64];
    snprintf(path, sizeof path, "/proc/self/fd/%d", fd);
    try_open(out, "dlopen(/proc/self/fd/N)", dlopen(path, RTLD_NOW));

    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT jstring JNICALL
Java_app_absentmotion_spike_Native_loadPath(JNIEnv* env, jobject self, jstring jpath) {
    (void)self;
    char out[OUT_SIZE];
    out[0] = 0;
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    try_open(out, "dlopen(path)", dlopen(path, RTLD_NOW));
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return (*env)->NewStringUTF(env, out);
}

JNIEXPORT void JNICALL
Java_app_absentmotion_spike_Native_spin(JNIEnv* env, jobject self) {
    (void)env;
    (void)self;
    volatile unsigned long x = 0;
    for (;;) {
        x++;
    }
}

JNIEXPORT void JNICALL
Java_app_absentmotion_spike_Native_invert(JNIEnv* env, jobject self, jobject buf, jint len) {
    (void)self;
    uint8_t* p = (uint8_t*)(*env)->GetDirectBufferAddress(env, buf);
    if (!p) return;
    uint64_t* q = (uint64_t*)p;
    for (jint i = 0; i < len / 8; i++) {
        q[i] ^= ~(uint64_t)0;
    }
}

/* Copies the bytes of srcFd into a memfd created in this process, then loads that. */
JNIEXPORT jstring JNICALL
Java_app_absentmotion_spike_Native_loadMemfd(JNIEnv* env, jobject self, jint srcFd) {
    (void)self;
    char out[OUT_SIZE];
    out[0] = 0;
    int mfd = (int)syscall(SYS_memfd_create, "payload", 0);
    if (mfd < 0) {
        snprintf(out, OUT_SIZE, "    memfd_create: blocked errno=%d (%s)\n", errno, strerror(errno));
        return (*env)->NewStringUTF(env, out);
    }
    char buf[8192];
    ssize_t n;
    long total = 0;
    lseek(srcFd, 0, SEEK_SET);
    while ((n = read(srcFd, buf, sizeof buf)) > 0) {
        if (write(mfd, buf, (size_t)n) != n) break;
        total += n;
    }
    snprintf(out, OUT_SIZE, "    memfd_create: OK, copied %ld bytes\n", total);

    android_dlextinfo info;
    memset(&info, 0, sizeof info);
    info.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    info.library_fd = mfd;
    try_open(out, "android_dlopen_ext(memfd)", android_dlopen_ext("payload_from_memfd.so", RTLD_NOW, &info));

    char path[64];
    snprintf(path, sizeof path, "/proc/self/fd/%d", mfd);
    try_open(out, "dlopen(/proc/self/fd/memfd)", dlopen(path, RTLD_NOW));
    return (*env)->NewStringUTF(env, out);
}
