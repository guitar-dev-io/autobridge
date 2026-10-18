package dev.autobridge.desktop.install

/**
 * Where the shared Linux runtime comes from, pinned to one exact image.
 *
 * The root filesystem is the official Debian 13 "trixie" slim image for arm64 (Docker Official
 * Image `arm64v8/debian:trixie-slim`, built by Debian's debuerreotype). It is fetched from the
 * Docker Hub registry by content digest, so the digest *is* the SHA-256 the download is checked
 * against: a different or tampered layer cannot have the same name.
 *
 * Cross-checked when pinned (2026-10-18): the image manifest digest below is the same one Debian's
 * own artifact repository records for trixie/slim on arm64v8
 * (github.com/debuerreotype/docker-debian-artifacts, branch dist-arm64v8, commit cf1f4a45),
 * and that image is Debian 13.7 from snapshot.debian.org 2026-10-05.
 *
 * To move to a newer image: read the new manifest and layer digests from the registry, check
 * them against the artifact repository the same way, and update all four values together.
 */
object RuntimeSource {
    const val REGISTRY_REPOSITORY = "arm64v8/debian"
    const val IMAGE_TAG = "trixie-slim"
    const val MANIFEST_DIGEST = "sha256:a2ca65377c251208de42e1a2722cb949211236c161b634f6f3663754e2d06850"
    const val LAYER_SHA256 = "bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba"
    const val LAYER_SIZE_BYTES = 30_200_282L
    const val DEBIAN_VERSION = "13.7"

    /** A rootfs is only as current as its packages; `apt-get update` runs right after extraction. */
    const val VERSION_LABEL = "Debian $DEBIAN_VERSION (trixie) arm64"

    val LAYER_URL = "https://registry-1.docker.io/v2/$REGISTRY_REPOSITORY/blobs/sha256:$LAYER_SHA256"

    /**
     * Packages every browser shares, installed once with the runtime. Xvfb is the display server,
     * PulseAudio the audio bridge's source; Thai fonts because the UI and most of this app's users
     * read Thai. Everything else a browser needs comes in as that browser's own dependencies.
     */
    val BASE_PACKAGES = listOf("xvfb", "pulseaudio", "ca-certificates", "fonts-thai-tlwg", "fonts-liberation")

    /** Measured in an arm64 container from this exact image: rootfs + base packages = 538 MB. */
    const val ESTIMATED_INSTALLED_BYTES = 540L * 1024 * 1024

    /** Free space required before starting: installed size plus room for apt's archive cache. */
    const val REQUIRED_FREE_BYTES = 900L * 1024 * 1024
}
