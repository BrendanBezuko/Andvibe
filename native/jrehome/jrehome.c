/*
 * LD_PRELOAD helper for on-device OpenJDK + Gradle on Android.
 *
 * 1) /proc/self/exe: return ANDVIBE_JAVA_EXE so jli TruncatePath finds the
 *    staged JDK tree (not the flat companion nativeLibraryDir).
 *
 * 2) execve/posix_spawn: Android W^X rejects ProcessBuilder starts of
 *    filesDir symlinks (…/jdk/bin/java) even when the target is an
 *    executable native lib. Rewrite those paths to ANDVIBE_JAVA_REAL
 *    (companion …/libjavaw.so) before the syscall.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern char **environ;

static ssize_t (*real_readlink)(const char *path, char *buf, size_t bufsiz);
static int (*real_execve)(const char *path, char *const argv[], char *const envp[]);
static int (*real_posix_spawn)(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *file_actions,
    const posix_spawnattr_t *attrp,
    char *const argv[], char *const envp[]);

static void dbg(const char *msg, const char *a, const char *b) {
    const char *log = getenv("ANDVIBE_JREHOME_LOG");
    if (log == NULL || log[0] == '\0') return;
    int fd = open(log, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    char line[512];
    int n = snprintf(line, sizeof(line), "%s %s -> %s\n", msg,
        a ? a : "(null)", b ? b : "(null)");
    if (n > 0) write(fd, line, (size_t)n < sizeof(line) ? (size_t)n : sizeof(line) - 1);
    close(fd);
}

static int ends_with(const char *path, const char *suffix) {
    size_t n = strlen(path), m = strlen(suffix);
    return n >= m && strcmp(path + n - m, suffix) == 0;
}

static const char *rewrite_path(const char *path) {
    if (path == NULL || path[0] == '\0') return path;

    const char *java_hint = getenv("ANDVIBE_JAVA_EXE");
    const char *java_real = getenv("ANDVIBE_JAVA_REAL");
    if (java_real != NULL && java_real[0] != '\0') {
        if ((java_hint != NULL && strcmp(path, java_hint) == 0) || ends_with(path, "/bin/java")) {
            dbg("java", path, java_real);
            return java_real;
        }
    }

    const char *helper_real = getenv("ANDVIBE_JSPAWNHELPER_REAL");
    if (helper_real != NULL && helper_real[0] != '\0' && ends_with(path, "/jspawnhelper")) {
        dbg("helper", path, helper_real);
        return helper_real;
    }

    dbg("pass", path, path);
    return path;
}

ssize_t readlink(const char *path, char *buf, size_t bufsiz) {
    if (real_readlink == NULL) {
        real_readlink = (ssize_t (*)(const char *, char *, size_t))dlsym(RTLD_NEXT, "readlink");
    }
    if (path != NULL && strcmp(path, "/proc/self/exe") == 0) {
        const char *fake = getenv("ANDVIBE_JAVA_EXE");
        if (fake != NULL && fake[0] != '\0') {
            size_t n = strlen(fake);
            if (n > bufsiz) {
                errno = ENAMETOOLONG;
                return -1;
            }
            memcpy(buf, fake, n);
            return (ssize_t)n;
        }
    }
    return real_readlink(path, buf, bufsiz);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    if (real_execve == NULL) {
        real_execve = (int (*)(const char *, char *const[], char *const[]))dlsym(RTLD_NEXT, "execve");
    }
    return real_execve(rewrite_path(path), argv, envp);
}

int posix_spawn(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *file_actions,
    const posix_spawnattr_t *attrp,
    char *const argv[], char *const envp[]) {
    if (real_posix_spawn == NULL) {
        real_posix_spawn = (int (*)(pid_t *, const char *,
            const posix_spawn_file_actions_t *,
            const posix_spawnattr_t *,
            char *const[], char *const[]))dlsym(RTLD_NEXT, "posix_spawn");
    }
    return real_posix_spawn(pid, rewrite_path(path), file_actions, attrp, argv, envp);
}
