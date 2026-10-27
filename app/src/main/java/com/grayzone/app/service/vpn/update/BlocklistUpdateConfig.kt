package com.grayzone.app.service.vpn.update

/** Where blocklist updates come from and how they are trusted. */
object BlocklistUpdateConfig {

    /** Published daily by .github/workflows/blocklists.yml. */
    const val BASE_URL = "https://github.com/vishalpa8/grayzone/releases/download/blocklists"

    const val MANIFEST_NAME = "blocklist_manifest.txt"
    const val SIGNATURE_NAME = "blocklist_manifest.sig"

    /**
     * P-256 public key (X.509 SubjectPublicKeyInfo, base64) matching the BLOCKLIST_SIGNING_KEY
     * GitHub secret. Only manifests signed by it are installed, so a hijacked download URL or a
     * tampered file can never change what the VPN blocks.
     */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa8X7m43EHhJH99x+QWwKiF2OyviiE1xyk8mFHJiaEF+wcn0EnHZSrgkDi1MFY4+9QXHZbJlnoLWeSvXWl/QhEQ=="

    /** Every list the VPN loads. A set is installed only when all of them are present and valid. */
    val FILES = listOf(
        "adblock_bloom.bin",
        "adult_bloom.bin",
        "security_bloom.bin",
        "abused_tlds.txt",
        "doh_domains.txt",
        "telemetry_domains.txt",
        "critical_domains.txt",
    )
}
