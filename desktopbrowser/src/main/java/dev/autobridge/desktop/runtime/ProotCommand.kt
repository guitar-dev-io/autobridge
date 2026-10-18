package dev.autobridge.desktop.runtime

/**
 * The PRoot command line for one guest command. Pure, so the exact flags are unit-tested.
 *
 * - `-0` fakes uid 0: dpkg and apt need to believe they are root. Nothing gains real privileges;
 *   every process still runs as this app's Android uid inside its sandbox.
 * - `--link2symlink` and `--sysvipc` emulate hard links and SysV IPC, which Android's SELinux
 *   policy and seccomp filter deny to apps (dpkg hard-links, Xvfb/Chromium use SysV shm).
 * - `--kill-on-exit` ties every guest process to the PRoot process, so stopping that one Java
 *   [Process] always leaves nothing behind.
 * - `/dev/shm` is bound to the rootfs `/tmp`: Android has no `/dev/shm`.
 * - `fakeProcFiles` are guest paths under `/proc` that Android hides from apps (`/proc/stat` …);
 *   each is bound to a static file so programs that read them at startup do not fail.
 * - The guest environment is built from scratch with `env -i`: nothing from Android's environment
 *   (`LD_PRELOAD`, `ANDROID_*`, `BOOTCLASSPATH`…) leaks into glibc programs.
 */
data class ProotCommand(val argv: List<String>, val environment: Map<String, String>) {
    companion object {
        val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        fun build(
            prootPath: String,
            loaderPath: String,
            prootTmpDir: String,
            rootfsPath: String,
            command: List<String>,
            guestEnvironment: Map<String, String> = emptyMap(),
            workingDirectory: String = "/root",
            fakeProcFiles: Map<String, String> = emptyMap(),
            seccompAcceleration: Boolean = true,
        ): ProotCommand {
            require(command.isNotEmpty()) { "empty command" }
            val argv = ArrayList<String>()
            argv += prootPath
            argv += listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "-0")
            argv += listOf("-r", rootfsPath)
            argv += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
            argv += listOf("-b", "$rootfsPath/tmp:/dev/shm")
            for ((guest, host) in fakeProcFiles.toSortedMap()) argv += listOf("-b", "$host:$guest")
            argv += listOf("-w", workingDirectory)
            argv += listOf("/usr/bin/env", "-i")
            val env = linkedMapOf(
                "HOME" to "/root",
                "PATH" to GUEST_PATH,
                "LANG" to "C.UTF-8",
                "TMPDIR" to "/tmp",
                "TERM" to "dumb",
                // No D-Bus in the runtime; without this Chromium retries the bus for every feature.
                "DBUS_SESSION_BUS_ADDRESS" to "disabled:",
            )
            env.putAll(guestEnvironment)
            for ((k, v) in env) argv += "$k=$v"
            argv += command
            val hostEnv = linkedMapOf(
                "PROOT_LOADER" to loaderPath,
                "PROOT_TMP_DIR" to prootTmpDir,
            )
            // Seccomp lets PRoot trace only the syscalls it rewrites, which is what makes a
            // browser usable under it. A kernel that mishandles it can be worked around from the
            // diagnostics screen; the pure-ptrace path is slower but always correct.
            if (!seccompAcceleration) hostEnv["PROOT_NO_SECCOMP"] = "1"
            return ProotCommand(argv, hostEnv)
        }
    }
}
