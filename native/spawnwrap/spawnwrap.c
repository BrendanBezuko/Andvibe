/*
 * libandvibe-spawn.so — interposed posix_spawn for Termux OpenJDK on Android.
 *
 * libjava.so DT_NEEDED is rewritten to this library so ProcessBuilder starts go
 * through us. We rewrite …/jdk/bin/java → ANDVIBE_JAVA_REAL (companion
 * libjavaw.so) because Android W^X rejects exec of filesDir symlinks.
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

static int (*real_posix_spawn)(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *file_actions,
    const posix_spawnattr_t *attrp,
    char *const argv[], char *const envp[]);
static int (*real_posix_spawnp)(pid_t *pid, const char *file,
    const posix_spawn_file_actions_t *file_actions,
    const posix_spawnattr_t *attrp,
    char *const argv[], char *const envp[]);

static void dbg(const char *msg) {
    const char *log = getenv("ANDVIBE_SPAWN_LOG");
    if (log == NULL || log[0] == '\0') return;
    int fd = open(log, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    write(fd, msg, strlen(msg));
    write(fd, "\n", 1);
    close(fd);
}

__attribute__((constructor))
static void on_load(void) {
    dbg("libandvibe-spawn loaded");
}

static void ensure_real(void) {
    if (real_posix_spawn != NULL) return;
    /* Prefer the copy beside this .so (companion nativeLibraryDir). */
    char self[PATH_MAX], beside[PATH_MAX];
    void *h = NULL;
    Dl_info info;
    if (dladdr((void *)ensure_real, &info) && info.dli_fname != NULL) {
        strncpy(self, info.dli_fname, sizeof(self) - 1);
        self[sizeof(self) - 1] = '\0';
        char *slash = strrchr(self, '/');
        if (slash != NULL) {
            *slash = '\0';
            snprintf(beside, sizeof(beside), "%s/libandroid-spawn.so", self);
            h = dlopen(beside, RTLD_NOW);
            if (h != NULL) dbg(beside);
        }
    }
    if (h == NULL) h = dlopen("libandroid-spawn.so", RTLD_NOW);
    if (h == NULL) {
        dbg(dlerror() ? dlerror() : "dlopen failed");
        return;
    }
    real_posix_spawn = (int (*)(pid_t *, const char *,
        const posix_spawn_file_actions_t *,
        const posix_spawnattr_t *,
        char *const *, char *const *))dlsym(h, "posix_spawn");
    real_posix_spawnp = (int (*)(pid_t *, const char *,
        const posix_spawn_file_actions_t *,
        const posix_spawnattr_t *,
        char *const *, char *const *))dlsym(h, "posix_spawnp");
    if (real_posix_spawn == NULL) dbg("posix_spawn missing");
}

static int ends_with(const char *path, const char *suffix) {
    size_t n = strlen(path), m = strlen(suffix);
    return n >= m && strcmp(path + n - m, suffix) == 0;
}

static const char *env_or_null(const char *name) {
    const char *v = getenv(name);
    return (v != NULL && v[0] != '\0') ? v : NULL;
}

static const char *rewrite_path(const char *path) {
    if (path == NULL || path[0] == '\0') return path;

    static const struct { const char *suffix; const char *env; } tools[] = {
        { "/bin/java", "ANDVIBE_JAVA_REAL" },
        { "/bin/jlink", "ANDVIBE_JLINK_REAL" },
        { "/bin/javac", "ANDVIBE_JAVAC_REAL" },
        { "/bin/jar", "ANDVIBE_JAR_REAL" },
        { "/jspawnhelper", "ANDVIBE_JSPAWNHELPER_REAL" },
    };

    const char *java_hint = env_or_null("ANDVIBE_JAVA_EXE");
    const char *java_real = env_or_null("ANDVIBE_JAVA_REAL");
    if (java_real != NULL && java_hint != NULL && strcmp(path, java_hint) == 0) {
        char buf[256];
        snprintf(buf, sizeof(buf), "rewrite java-hint %s -> %s", path, java_real);
        dbg(buf);
        return java_real;
    }

    for (unsigned i = 0; i < sizeof(tools) / sizeof(tools[0]); i++) {
        if (!ends_with(path, tools[i].suffix)) continue;
        const char *real = env_or_null(tools[i].env);
        if (real == NULL) return path;
        char buf[256];
        snprintf(buf, sizeof(buf), "rewrite %s %s -> %s", tools[i].suffix, path, real);
        dbg(buf);
        return real;
    }

    return path;
}

int posix_spawn(pid_t *pid, const char *path,
    const posix_spawn_file_actions_t *file_actions,
    const posix_spawnattr_t *attrp,
    char *const argv[], char *const envp[]) {
    ensure_real();
    if (real_posix_spawn == NULL) {
        dbg("posix_spawn ENOSYS");
        errno = ENOSYS;
        return ENOSYS;
    }
    const char *use = rewrite_path(path);
    int rc = real_posix_spawn(pid, use, file_actions, attrp, argv, envp);
    if (rc != 0) {
        char buf[128];
        snprintf(buf, sizeof(buf), "posix_spawn rc=%d errno=%d", rc, errno);
        dbg(buf);
    }
    return rc;
}

int posix_spawnp(pid_t *pid, const char *file,
    const posix_spawn_file_actions_t *file_actions,
    const posix_spawnattr_t *attrp,
    char *const argv[], char *const envp[]) {
    ensure_real();
    if (real_posix_spawnp == NULL) {
        errno = ENOSYS;
        return ENOSYS;
    }
    return real_posix_spawnp(pid, rewrite_path(file), file_actions, attrp, argv, envp);
}
