/*
 * Thin java launcher for the AndVibe companion APK.
 *
 * Gradle forks a single-use daemon and strips -Dsun.jnu.encoding from
 * org.gradle.jvmargs. Without it, Termux OpenJDK DNS fails with
 * "platform encoding not initialized". This wrapper always injects the
 * encoding flags, then execs libjavabin.so beside itself.
 *
 * Also drops Gradle's -javaagent:…gradle-instrumentation-agent…. On this
 * OpenJDK build, loading ANY javaagent leaves native JNU encoding
 * uninitialized, so InetAddress/DNS throws InternalError even when
 * -Dsun.jnu.encoding=UTF-8 is set. Gradle then falls back to legacy
 * classpath instrumentation (Agent.isApplied() == false).
 */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void dbg(const char *msg) {
    const char *log = getenv("ANDVIBE_JAVAW_LOG");
    if (log == NULL || log[0] == '\0') return;
    int fd = open(log, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    write(fd, msg, strlen(msg));
    write(fd, "\n", 1);
    close(fd);
}

static int read_self(char *out, size_t out_len) {
    ssize_t n = readlink("/proc/self/exe", out, out_len - 1);
    if (n < 0) return -1;
    out[n] = '\0';
    /* Android may report the symlink (…/jdk/bin/java); resolve to the .so. */
    char resolved[PATH_MAX];
    if (realpath(out, resolved) != NULL) {
        size_t m = strlen(resolved);
        if (m >= out_len) {
            errno = ENAMETOOLONG;
            return -1;
        }
        memcpy(out, resolved, m + 1);
    }
    return 0;
}

int main(int argc, char **argv) {
    char self[PATH_MAX];
    if (read_self(self, sizeof(self)) != 0) {
        fprintf(stderr, "andvibe-javaw: resolve self path failed\n");
        return 127;
    }
    char *slash = strrchr(self, '/');
    if (slash == NULL) {
        fprintf(stderr, "andvibe-javaw: bad self path\n");
        return 127;
    }
    /* libjavabin.so next to this wrapper in companion nativeLibraryDir */
    size_t dir_len = (size_t)(slash - self);
    char target[PATH_MAX];
    if (dir_len + 1 + strlen("libjavabin.so") >= sizeof(target)) {
        fprintf(stderr, "andvibe-javaw: path too long\n");
        return 127;
    }
    memcpy(target, self, dir_len);
    target[dir_len] = '\0';
    snprintf(target + dir_len, sizeof(target) - dir_len, "/libjavabin.so");

    /* argv[0]=target, then injected -D flags, then original argv[1..] */
    static const char *inject[] = {
        "-Dfile.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8",
        "-Duser.language=en",
        "-Duser.country=US",
    };
    const int n_inject = (int)(sizeof(inject) / sizeof(inject[0]));
    int new_argc = 1 + n_inject + (argc > 0 ? argc - 1 : 0);
    char **new_argv = calloc((size_t)new_argc + 1, sizeof(char *));
    if (new_argv == NULL) return 127;
    new_argv[0] = target;
    for (int i = 0; i < n_inject; i++) {
        new_argv[1 + i] = (char *)inject[i];
    }
    int out = 1 + n_inject;
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (a != NULL &&
            strncmp(a, "-javaagent:", 11) == 0 &&
            strstr(a, "gradle-instrumentation-agent") != NULL) {
            dbg("strip");
            dbg(a);
            continue;
        }
        new_argv[out++] = argv[i];
    }
    new_argv[out] = NULL;
    dbg(self);
    dbg(target);
    for (int i = 0; i < out && i < 12; i++) {
        char line[320];
        snprintf(line, sizeof(line), "argv[%d]=%s", i, new_argv[i] ? new_argv[i] : "(null)");
        dbg(line);
    }
    setenv("LANG", "C.UTF-8", 1);
    setenv("LC_ALL", "C.UTF-8", 1);
    setenv("JAVA_TOOL_OPTIONS",
        "-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Duser.language=en -Duser.country=US", 1);
    execv(target, new_argv);
    fprintf(stderr, "andvibe-javaw: execv(%s) failed: %s\n", target, strerror(errno));
    free(new_argv);
    return 127;
}
