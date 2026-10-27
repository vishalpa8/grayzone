package com.grayzone.app.service.vpn.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class BlocklistUpdateTest {

    private val base = "https://example.test/blocklists"
    private lateinit var root: File
    private lateinit var keys: KeyPair
    private lateinit var publicKey: String

    /** What the fake server publishes: URL -> bytes. */
    private val published = HashMap<String, ByteArray>()
    private val requested = ArrayList<String>()
    private var networkDown = false

    private val fetcher = BlocklistUpdater.Fetcher { url ->
        if (networkDown) throw IOException("offline")
        requested += url
        published[url]?.let { ByteArrayInputStream(it) }
    }

    // ─── Fixtures ────────────────────────────────────────────────────────────

    private fun bloom(seed: Int): ByteArray {
        val bits = ByteArray(64) { (it * 31 + seed).toByte() }
        val header = byteArrayOf(0x47, 0x5A, 0x42, 0x4C, 0, 0, 0, 1, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 2, 0)
        return header + bits
    }

    private fun domains(prefix: String, n: Int) =
        (1..n).joinToString("\n") { "$prefix$it.example" }.toByteArray()

    private fun tlds(n: Int) = (1..n).joinToString("\n") { "t" + "x".repeat(it % 5 + 1) + it }.toByteArray()

    private fun set(seed: Int): Map<String, ByteArray> = mapOf(
        "adblock_bloom.bin" to bloom(seed),
        "adult_bloom.bin" to bloom(seed + 1),
        "security_bloom.bin" to bloom(seed + 2),
        "abused_tlds.txt" to tlds(150),
        "doh_domains.txt" to domains("doh$seed-", 150),
        "telemetry_domains.txt" to domains("tel$seed-", 80),
        "critical_domains.txt" to domains("crit", 30),
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestText(version: Long, files: Map<String, ByteArray>) = buildString {
        append("format=1\nversion=$version\n")
        files.forEach { (name, bytes) -> append("file $name ${sha(bytes)} ${bytes.size}\n") }
    }

    private fun sign(bytes: ByteArray, pair: KeyPair = keys): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(bytes); sign() }

    private fun publish(version: Long, files: Map<String, ByteArray>, signWith: KeyPair = keys) {
        val manifest = manifestText(version, files).toByteArray()
        published["$base/blocklist_manifest.txt"] = manifest
        published["$base/blocklist_manifest.sig"] = sign(manifest, signWith)
        files.forEach { (name, bytes) -> published["$base/$version-$name"] = bytes }
    }

    private var bundledFiles = set(0)
    private var bundledVersion = 202601010000L

    private fun store() = BlocklistStore(root, object : BlocklistStore.Bundled {
        override fun manifestText(): String = manifestText(bundledVersion, bundledFiles)
        override fun open(name: String): InputStream = ByteArrayInputStream(bundledFiles.getValue(name))
    })

    private fun updater(store: BlocklistStore = store()) =
        BlocklistUpdater(store, fetcher, base, publicKey)

    private fun newKeys() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("blocklists").toFile()
        keys = newKeys()
        publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun readActive(store: BlocklistStore, name: String) = store.open(name).use { it.readBytes() }

    // ─── Manifest ────────────────────────────────────────────────────────────

    @Test
    fun `manifest parses and round trips`() {
        val text = manifestText(202610271200, set(1))
        val parsed = BlocklistManifest.parse(text, BlocklistUpdateConfig.FILES)!!
        assertEquals(202610271200, parsed.version)
        assertEquals(parsed, BlocklistManifest.parse(parsed.serialize(), BlocklistUpdateConfig.FILES))
    }

    @Test
    fun `manifest missing a file, with a bad hash or unknown format is refused`() {
        val files = set(1)
        assertNull(BlocklistManifest.parse(manifestText(202610271200, files - "doh_domains.txt"), BlocklistUpdateConfig.FILES))
        assertNull(BlocklistManifest.parse(manifestText(202610271200, files).replace(sha(files.getValue("adult_bloom.bin")), "zz"), BlocklistUpdateConfig.FILES))
        assertNull(BlocklistManifest.parse(manifestText(202610271200, files).replace("format=1", "format=2"), BlocklistUpdateConfig.FILES))
        assertNull(BlocklistManifest.parse(manifestText(202610271200, files).replace("version=202610271200", "version=12"), BlocklistUpdateConfig.FILES))
    }

    @Test
    fun `unknown files in a newer manifest are ignored`() {
        val text = manifestText(202610271200, set(1)) + "file future_list.bin ${"a".repeat(64)} 10\n"
        val parsed = BlocklistManifest.parse(text, BlocklistUpdateConfig.FILES)!!
        assertFalse("future_list.bin" in parsed.files)
    }

    @Test
    fun `signature must come from the pinned key`() {
        val bytes = "format=1".toByteArray()
        assertTrue(BlocklistManifest.verifySignature(bytes, sign(bytes), publicKey))
        assertFalse(BlocklistManifest.verifySignature(bytes, sign(bytes, newKeys()), publicKey))
        assertFalse(BlocklistManifest.verifySignature("format=2".toByteArray(), sign(bytes), publicKey))
        assertFalse(BlocklistManifest.verifySignature(bytes, byteArrayOf(1, 2, 3), publicKey))
    }

    @Test
    fun `version converts to build time`() {
        assertEquals(
            java.time.Instant.parse("2026-10-27T08:00:00Z").toEpochMilli(),
            BlocklistManifest.versionToEpochMs(202610270800)
        )
        assertEquals(0L, BlocklistManifest.versionToEpochMs(202613990000))
    }

    @Test
    fun `the shipped config key decodes as a P-256 key`() {
        // Any signature check against the real key must run (and fail) rather than throw.
        assertFalse(BlocklistManifest.verifySignature(byteArrayOf(1), byteArrayOf(1), BlocklistUpdateConfig.PUBLIC_KEY))
        val spec = java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(BlocklistUpdateConfig.PUBLIC_KEY))
        assertNotNull(java.security.KeyFactory.getInstance("EC").generatePublic(spec))
    }

    // ─── Updater ─────────────────────────────────────────────────────────────

    @Test
    fun `newer signed set is installed and becomes active`() {
        val next = set(7)
        publish(202610271200, next)
        val store = store()
        val outcome = updater(store).run()

        assertTrue(outcome is BlocklistUpdater.Outcome.Updated)
        assertTrue(store.usesDownloadedSet())
        assertEquals(202610271200, store.activeManifest()!!.version)
        next.forEach { (name, bytes) -> assertTrue(name, bytes.contentEquals(readActive(store, name))) }
    }

    @Test
    fun `unchanged files are reused instead of downloaded`() {
        val next = set(0).toMutableMap().apply { put("security_bloom.bin", bloom(99)) }
        publish(202610271200, next)
        val outcome = updater().run() as BlocklistUpdater.Outcome.Updated

        assertEquals(1, outcome.downloaded)
        assertEquals(6, outcome.reused)
        assertEquals(listOf("$base/202610271200-security_bloom.bin"), requested.filter { it.contains("-") })
    }

    @Test
    fun `a local copy that does not match its manifest is downloaded instead of blocking updates`() {
        val drifted = set(0).toMutableMap().apply { put("abused_tlds.txt", tlds(160)) }
        bundledFiles = drifted // shipped file changed, built-in manifest describes the old one
        val store = BlocklistStore(root, object : BlocklistStore.Bundled {
            override fun manifestText(): String = manifestText(bundledVersion, set(0))
            override fun open(name: String): InputStream = ByteArrayInputStream(drifted.getValue(name))
        })
        publish(202610271200, set(0))
        assertTrue(BlocklistUpdater(store, fetcher, base, publicKey).run() is BlocklistUpdater.Outcome.Updated)
        assertTrue(set(0).getValue("abused_tlds.txt").contentEquals(readActive(store, "abused_tlds.txt")))
    }

    @Test
    fun `same or older version is up to date and downloads nothing`() {
        publish(bundledVersion, set(5))
        assertTrue(updater().run() is BlocklistUpdater.Outcome.UpToDate)
        publish(bundledVersion - 1, set(5))
        assertTrue(updater().run() is BlocklistUpdater.Outcome.UpToDate)
        assertTrue(requested.none { it.contains("-") })
    }

    @Test
    fun `a set signed by another key is rejected and nothing changes`() {
        publish(202610271200, set(7), signWith = newKeys())
        val store = store()
        assertTrue(updater(store).run() is BlocklistUpdater.Outcome.Rejected)
        assertFalse(store.usesDownloadedSet())
    }

    @Test
    fun `a tampered file is rejected and nothing changes`() {
        publish(202610271200, set(7))
        published["$base/202610271200-adult_bloom.bin"] = bloom(42)
        val store = store()
        assertTrue(updater(store).run() is BlocklistUpdater.Outcome.Rejected)
        assertFalse(store.usesDownloadedSet())
        assertTrue(root.listFiles()!!.none { it.name.startsWith("staging") })
    }

    @Test
    fun `an oversized file is cut off and rejected`() {
        val next = set(7)
        publish(202610271200, next)
        published["$base/202610271200-adblock_bloom.bin"] = next.getValue("adblock_bloom.bin") + ByteArray(10_000)
        assertTrue(updater().run() is BlocklistUpdater.Outcome.Rejected)
    }

    @Test
    fun `a signed but corrupt list is rejected by validation`() {
        val next = set(7).toMutableMap().apply { put("security_bloom.bin", "not a bloom filter".toByteArray()) }
        publish(202610271200, next)
        val store = store()
        assertTrue(updater(store).run() is BlocklistUpdater.Outcome.Rejected)
        assertFalse(store.usesDownloadedSet())
    }

    @Test
    fun `a suspiciously small list is rejected`() {
        val next = set(7).toMutableMap().apply { put("doh_domains.txt", domains("d", 3)) }
        publish(202610271200, next)
        assertTrue(updater().run() is BlocklistUpdater.Outcome.Rejected)
    }

    @Test
    fun `offline or partially published is a retryable failure`() {
        networkDown = true
        assertTrue(updater().run() is BlocklistUpdater.Outcome.Failed)
        networkDown = false
        assertTrue(updater().run() is BlocklistUpdater.Outcome.Failed) // nothing published yet

        publish(202610271200, set(7))
        published.remove("$base/202610271200-adult_bloom.bin")
        val store = store()
        assertTrue(updater(store).run() is BlocklistUpdater.Outcome.Failed)
        assertFalse(store.usesDownloadedSet())
    }

    @Test
    fun `successive updates keep only the active set on disk`() {
        publish(202610271200, set(7))
        updater().run()
        publish(202610281200, set(8))
        val store = store()
        assertTrue(updater(store).run() is BlocklistUpdater.Outcome.Updated)
        assertEquals(202610281200, store.activeManifest()!!.version)
        assertEquals(listOf("current", "v202610281200"), root.list()!!.sorted())
    }

    // ─── Store ───────────────────────────────────────────────────────────────

    @Test
    fun `an app update with newer built-in lists wins over an older download`() {
        publish(202610271200, set(7))
        updater().run()
        bundledVersion = 202611010000
        bundledFiles = set(3)
        val store = store()
        assertFalse(store.usesDownloadedSet())
        assertTrue(set(3).getValue("adblock_bloom.bin").contentEquals(readActive(store, "adblock_bloom.bin")))
    }

    @Test
    fun `a damaged downloaded set falls back to the built-in lists`() {
        publish(202610271200, set(7))
        updater().run()
        File(root, "v202610271200/telemetry_domains.txt").delete()
        val store = store()
        assertFalse(store.usesDownloadedSet())
        assertEquals(bundledVersion, store.activeManifest()!!.version)
    }

    @Test
    fun `discard removes every downloaded set`() {
        publish(202610271200, set(7))
        updater().run()
        val store = store()
        store.discard()
        assertFalse(store.usesDownloadedSet())
        assertTrue(root.list()!!.isEmpty())
    }
}
