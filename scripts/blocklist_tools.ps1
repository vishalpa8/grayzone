<#
.SYNOPSIS
    Grayzone Blocklist Tools

.DESCRIPTION
    Builds every list the VPN uses and a manifest describing them:
      adblock_bloom.bin, adult_bloom.bin, security_bloom.bin  (Bloom filters)
      abused_tlds.txt, doh_domains.txt, telemetry_domains.txt, critical_domains.txt  (exact lists)
      blocklist_manifest.txt  (version + SHA-256 of each file)

    Runs daily in GitHub Actions (.github/workflows/blocklists.yml), which signs the manifest and
    publishes the set; the app downloads it by itself. Running it by hand is never required.

    Modes:
      (default)               Build everything into -OutputDir
      -TolerateSourceFailures Skip a dead or degraded feed with a warning instead of failing,
                              as long as each list still meets its total-size floor (used by CI)
      -Probe                  Health-check all source URLs and DoH bypass domains
#>
param(
    [string]$OutputDir = "app/src/main/res/raw",
    [switch]$TolerateSourceFailures,
    [switch]$Probe
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# Windows PowerShell 5.1 defaults to TLS 1.0/1.1, which GitHub and several of the
# source endpoints now reject. Force TLS 1.2 (+ 1.3 when the runtime supports it).
try {
    [Net.ServicePointManager]::SecurityProtocol =
        [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
} catch {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}

# Many list hosts (e.g. pgl.yoyo.org) throttle or deny the default PowerShell UA.
$UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GrayzoneBlocklistTools/1.0"

$K = [int]10
# 20 bits per domain with k=10 gives a false-positive rate of ~0.009% (1 in ~11,000 lookups).
# Filters are sized from their real domain count, so lists can grow without the rate creeping up.
$BITS_PER_DOMAIN = 20

$AD_MIN_TOTAL_DOMAINS       = 100000
$ADULT_MIN_TOTAL_DOMAINS    = 50000
$SECURITY_MIN_TOTAL_DOMAINS = 500000
$MIN_ABUSED_TLDS            = 200

$MIN_DOH_DOMAINS            = 500
$MIN_TELEMETRY_DOMAINS      = 200

# Forward slashes: the script also runs on Linux (CI).
$CriticalDomainsFile = "app/src/main/res/raw/critical_domains.txt"

# HaGeZi lists must use wildcard/*-onlydomains.txt (plain domains); the old domains/*.txt paths
# are gone, and adblock/* uses ||domain^ syntax.
$AdSources = [ordered]@{
    "HaGeZi Ultimate"        = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/ultimate-onlydomains.txt"; MinDomains = 10000 }
    "OISD Big"               = @{ Url = "https://big.oisd.nl/"; MinDomains = 10000 }
    "Block List Project Ads" = @{ Url = "https://blocklistproject.github.io/Lists/ads.txt"; MinDomains = 1000 }
    "StevenBlack Unified"    = @{ Url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"; MinDomains = 1000 }
    "Peter Lowe AdServers"   = @{ Url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"; MinDomains = 1000 }
}

# Malware, phishing, scam and C2 domains: their own filter (security_bloom.bin), reported as
# threats in the app and also used to unmask CNAMEs that point at them.
$SecuritySources = [ordered]@{
    "HaGeZi Threat Intel"      = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif-onlydomains.txt"; MinDomains = 500000 }
    "HaGeZi Fake (scam shops)" = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/fake-onlydomains.txt"; MinDomains = 1000 }
    "HaGeZi Badware Hosters"   = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/hoster-onlydomains.txt"; MinDomains = 100 }
    # DynDNS hosts are commonly abused for phishing / malware C2.
    "HaGeZi DynDNS"            = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/dyndns-onlydomains.txt"; MinDomains = 500 }
    # Only the multi-label rows (e.g. co.com, dpdns.org) enter the filter here;
    # the ~430 bare TLDs go to res/raw/abused_tlds.txt instead, because a Bloom
    # false positive on a bare TLD would blackhole everything under it.
    "HaGeZi Most Abused TLDs"  = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/spam-tlds-onlydomains.txt"; MinDomains = 5 }
    "URLhaus (malware)"        = @{ Url = "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt"; MinDomains = 100 }
    "Phishing Filter"          = @{ Url = "https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt"; MinDomains = 1000 }
    "Phishing Army Extended"   = @{ Url = "https://phishing.army/download/phishing_army_blocklist_extended.txt"; MinDomains = 10000 }
    "Block List Project Phishing"   = @{ Url = "https://blocklistproject.github.io/Lists/phishing.txt"; MinDomains = 10000 }
    "Block List Project Scam"       = @{ Url = "https://blocklistproject.github.io/Lists/scam.txt"; MinDomains = 1000 }
    "Block List Project Ransomware" = @{ Url = "https://blocklistproject.github.io/Lists/ransomware.txt"; MinDomains = 500 }
}

$AbusedTldSourceUrl = $SecuritySources["HaGeZi Most Abused TLDs"].Url

$AdultSources = [ordered]@{
    "HaGeZi NSFW"             = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt"; MinDomains = 1000 }
    "Block List Project Porn" = @{ Url = "https://blocklistproject.github.io/Lists/porn.txt"; MinDomains = 1000 }
    "StevenBlack Porn"        = @{ Url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts"; MinDomains = 1000 }
}

# Gambling is a separate HaGeZi category from NSFW; merged into the adult filter.
$GamblingSources = [ordered]@{
    "HaGeZi Gambling" = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling-onlydomains.txt"; MinDomains = 1000 }
}

# Encrypted-DNS (DoH/DoT) resolvers apps could use to dodge the VPN's filtering. Always blocked;
# the app keeps a small hardcoded floor of the big providers in case this list is unavailable.
$DohSources = [ordered]@{
    "HaGeZi DoH" = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/doh-onlydomains.txt"; MinDomains = 500 }
}

# Phone / OS vendor telemetry, blocked only when the user turns on "Block background telemetry".
$TelemetrySources = [ordered]@{}
foreach ($vendor in "xiaomi", "samsung", "apple", "amazon", "huawei", "winoffice", "tiktok", "vivo", "oppo-realme", "lgwebos", "roku") {
    $TelemetrySources["HaGeZi native $vendor"] = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.$vendor-onlydomains.txt"; MinDomains = 5 }
}

# The hardcoded floor in BlocklistManager.dohBypassDomains; probed to spot dead entries.
$DohDomains = @(
    "dns.google", "dns.google.com", "8888.google",
    "cloudflare-dns.com", "1dot1dot1dot1.cloudflare-dns.com", "mozilla.cloudflare-dns.com",
    "family.cloudflare-dns.com", "security.cloudflare-dns.com",
    "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
    "dns.adguard.com", "dns-family.adguard.com", "dns-unfiltered.adguard.com",
    "dns.nextdns.io", "doh.opendns.com", "doh.familyshield.opendns.com",
    "resolver1.opendns.com", "resolver2.opendns.com", "doh.xfinity.com",
    "dns.alidns.com", "doh.pub",
    "doh.cleanbrowsing.org", "security-filter-dns.cleanbrowsing.org",
    "family-filter-dns.cleanbrowsing.org", "freedns.controld.com",
    "dns.mullvad.net", "adblock.dns.mullvad.net", "use-application-dns.net"
)

Add-Type -Language CSharp -TypeDefinition @"
using System;
using System.Security.Cryptography;
using System.Text;

public static class BloomBuilder {
    [ThreadStatic] private static SHA256 _sha;
    private static SHA256 Sha { get { return _sha ?? (_sha = SHA256.Create()); } }

    public static void AddDomain(byte[] bits, long bitCount, int k, string domain) {
        byte[] d = Sha.ComputeHash(Encoding.UTF8.GetBytes(domain.ToLowerInvariant()));
        long h1 = ((long)d[0]<<24)|((long)d[1]<<16)|((long)d[2]<<8)|d[3];
        long h2 = ((long)d[4]<<24)|((long)d[5]<<16)|((long)d[6]<<8)|d[7];
        if (h2 == 0) h2 = 1;
        for (int i = 0; i < k; i++) {
            long idx = (h1 + (long)i * h2) % bitCount;
            if (idx < 0) idx += bitCount;
            bits[idx / 8] |= (byte)(1 << (int)(idx % 8));
        }
    }

    public static byte[] Build(System.Collections.Generic.IEnumerable<string> domains, long bitCount, int k) {
        byte[] bits = new byte[(bitCount + 7) / 8];
        foreach (string d in domains) AddDomain(bits, bitCount, k, d);
        return bits;
    }

    public static string ParseDomain(string raw) {
        if (raw == null) return null;
        string line = raw.Trim().Trim('\uFEFF');
        if (line.Length == 0) return null;
        if (line.StartsWith("!") || line.StartsWith("[") || line.StartsWith("#")) return null;
        if (line.StartsWith("@@")) return null;

        int hash = line.IndexOf('#');
        if (hash >= 0) line = line.Substring(0, hash).Trim();
        if (line.Length == 0) return null;

        string dom;
        string[] parts = line.Split(new char[] {' ', '\t'}, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length >= 2 && LooksLikeAddress(parts[0])) {
            dom = parts[1];
        } else {
            dom = line;
            int option = dom.IndexOf('$');
            if (option >= 0) dom = dom.Substring(0, option);
            if (dom.StartsWith("||")) dom = dom.Substring(2);
            while (dom.StartsWith("|")) dom = dom.Substring(1);
            if (dom.StartsWith("*.")) dom = dom.Substring(2);
            int end = dom.IndexOfAny(new char[] {'^', '/', '?', '&'});
            if (end >= 0) dom = dom.Substring(0, end);
        }

        dom = dom.Trim().Trim('.').ToLowerInvariant();
        if (dom.Length == 0) return null;
        if (dom.IndexOf(':') >= 0) return null;
        if (dom.IndexOf('*') >= 0 || dom.IndexOf('|') >= 0 || dom.IndexOf('@') >= 0) return null;
        if (dom.IndexOf('.') < 0) return null;
        if (LooksLikeAddress(dom)) return null;
        if (dom == "localhost" || dom == "broadcasthost") return null;
        return IsValidDomain(dom) ? dom : null;
    }

    // Counterpart to ParseDomain for the spam-TLD feed: keeps ONLY the bare,
    // dotless rows that ParseDomain deliberately drops.
    public static string ParseTld(string raw) {
        if (raw == null) return null;
        string line = raw.Trim().Trim('\uFEFF');
        if (line.Length == 0) return null;
        if (line.StartsWith("!") || line.StartsWith("[") || line.StartsWith("#")) return null;
        if (line.StartsWith("@@")) return null;

        int hash = line.IndexOf('#');
        if (hash >= 0) line = line.Substring(0, hash).Trim();

        line = line.Trim().Trim('.').ToLowerInvariant();
        if (line.Length == 0) return null;
        if (line.IndexOf('.') >= 0) return null;
        if (LooksLikeAddress(line)) return null;
        return IsValidDomain(line) ? line : null;
    }

    private static bool LooksLikeAddress(string value) {
        if (string.IsNullOrEmpty(value)) return false;
        bool hasDigit = false;
        foreach (char c in value) {
            if (char.IsDigit(c)) { hasDigit = true; continue; }
            if (c != '.') return false;
        }
        return hasDigit;
    }

    private static bool IsValidDomain(string domain) {
        if (domain.Length > 253) return false;
        string[] labels = domain.Split('.');
        foreach (string label in labels) {
            if (label.Length == 0 || label.Length > 63) return false;
            if (label[0] == '-' || label[label.Length - 1] == '-') return false;
            foreach (char c in label) {
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') continue;
                return false;
            }
        }
        return true;
    }
}
"@

function Get-SourceUrl($Source) { return [string]$Source.Url }
function Get-SourceMinDomains($Source) { return [int]$Source.MinDomains }

function Merge-SourceTables {
    param([Parameter(Mandatory)][System.Collections.IDictionary[]]$Tables)
    $merged = [ordered]@{}
    foreach ($table in $Tables) {
        foreach ($name in $table.Keys) {
            if ($merged.Contains($name)) {
                throw "Duplicate blocklist source name '$name' when merging feed tables."
            }
            $merged[$name] = $table[$name]
        }
    }
    return $merged
}

function Invoke-Download([string]$Url, [switch]$Strict, [int]$MaxAttempts = 3) {
    $lastError = $null
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 120 -UserAgent $UserAgent
            $content = [string]$response.Content
            if ([string]::IsNullOrWhiteSpace($content)) { throw "empty response body" }
            return ($content -split "`n")
        } catch {
            $lastError = $_
            if ($attempt -lt $MaxAttempts) {
                $delay = [int][Math]::Pow(2, $attempt)  # 2s, 4s backoff
                Write-Warning "Attempt $attempt/$MaxAttempts failed for $Url ($($_.Exception.Message)); retrying in ${delay}s..."
                Start-Sleep -Seconds $delay
            }
        }
    }

    if ($Strict) { throw "Failed to download $Url after $MaxAttempts attempts`: $($lastError.Exception.Message)" }
    Write-Warning "Failed after $MaxAttempts attempts: $Url`n  $lastError"
    return @()
}

function Read-CriticalDomains([string]$Path) {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($line in (Get-Content -LiteralPath $Path)) {
        $d = [BloomBuilder]::ParseDomain($line)
        if ($d) { [void]$set.Add($d) }
    }
    if ($set.Count -lt 20) { throw "Critical domain list $Path has only $($set.Count) entries; refusing to build." }
    return $set
}

function Write-SourceProblem([string]$Message) {
    if (-not $TolerateSourceFailures) { throw $Message }
    Write-Warning $Message
    # Surfaces as an annotation on the GitHub Actions run.
    if ($env:GITHUB_ACTIONS -eq "true") { Write-Host "::warning::$Message" }
}

function Get-Domains($Sources, [string]$Label, [int]$MinTotalDomains, $Critical) {
    Write-Host "[$Label] Collecting from $($Sources.Count) sources..." -ForegroundColor Yellow
    $seen = New-Object 'System.Collections.Generic.HashSet[string]'

    foreach ($name in $Sources.Keys) {
        $source = $Sources[$name]
        $url = Get-SourceUrl $source
        $minDomains = Get-SourceMinDomains $source
        Write-Host "  $name" -ForegroundColor Gray
        $lines = @()
        try {
            $lines = Invoke-Download $url -Strict
        } catch {
            Write-SourceProblem "[$Label] Source '$name' could not be downloaded: $($_.Exception.Message)"
            continue
        }
        $fromSource = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($line in $lines) {
            $d = [BloomBuilder]::ParseDomain($line)
            if ($d) { [void]$fromSource.Add($d) }
        }
        # Guard on domains PARSED from this source (download-health check), not on
        # newly-unique domains: small curated lists processed after large ones can
        # legitimately add ~0 new domains due to heavy overlap, which is not a failure.
        # A degraded source is skipped whole, never merged half-broken.
        if ($fromSource.Count -lt $minDomains) {
            Write-SourceProblem "[$Label] Source '$name' parsed only $($fromSource.Count) valid domains; expected at least $minDomains (download may be broken)."
            continue
        }
        $before = $seen.Count
        $seen.UnionWith($fromSource)
        Write-Host "  -> $($seen.Count) total unique so far (source parsed $($fromSource.Count), +$($seen.Count - $before) new)" -ForegroundColor DarkGray
    }

    # The app ignores hits on these names anyway; dropping them keeps the filter honest and
    # surfaces feeds that list a whole platform.
    $dropped = @($Critical | Where-Object { $seen.Contains($_) })
    foreach ($d in $dropped) { [void]$seen.Remove($d) }
    if ($dropped.Count -gt 0) {
        Write-Host "  Dropped critical domains listed by feeds: $($dropped -join ', ')" -ForegroundColor DarkYellow
    }

    # Always fatal: a list this small means the feeds broke, and publishing it would weaken every
    # phone. CI then fails and phones keep the last good set.
    if ($seen.Count -lt $MinTotalDomains) {
        throw "[$Label] Total unique domains $($seen.Count) is below safety threshold $MinTotalDomains. Refusing to write it."
    }
    # Comma: return the set itself, not its enumerated items.
    return ,$seen
}

function Build-Filter($Sources, [int]$NumHashes, [string]$Label, [int]$MinTotalDomains, $Critical) {
    $seen = Get-Domains $Sources $Label $MinTotalDomains $Critical
    # Byte aligned (the app requires it).
    $bitCount = [long]([Math]::Ceiling($seen.Count * $BITS_PER_DOMAIN / 8.0) * 8)
    $bits = [BloomBuilder]::Build($seen, $bitCount, $NumHashes)
    Write-Host "[$Label] $($seen.Count) unique domains inserted into filter ($bitCount bits)" -ForegroundColor Green
    return @{ Bits = $bits; BitCount = $bitCount; Count = $seen.Count }
}

function Save-DomainList([string]$Path, $Domains, [string]$Description) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("# Generated by scripts/blocklist_tools.ps1 - do not edit by hand.`n")
    [void]$sb.Append("# $Description`n")
    foreach ($d in ($Domains | Sort-Object)) { [void]$sb.Append($d).Append("`n") }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    Write-FileAtomic $Path $encoding.GetBytes($sb.ToString())
}

function Save-Manifest([string]$Dir, [string[]]$Names) {
    $version = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmm")
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("format=1`n")
    [void]$sb.Append("version=$version`n")
    foreach ($name in $Names) {
        $path = Join-Path $Dir $name
        $sha = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $size = (Get-Item -LiteralPath $path).Length
        [void]$sb.Append("file $name $sha $size`n")
    }
    $encoding = New-Object System.Text.UTF8Encoding($false)
    Write-FileAtomic (Join-Path $Dir "blocklist_manifest.txt") $encoding.GetBytes($sb.ToString())
    Write-Host "  Manifest version $version" -ForegroundColor Cyan
}

function Build-AbusedTldList([string]$Url, [int]$MinTlds) {
    Write-Host "[TLDS] Extracting bare TLD blocks..." -ForegroundColor Yellow
    $tlds = New-Object 'System.Collections.Generic.SortedSet[string]' ([StringComparer]::Ordinal)
    foreach ($line in (Invoke-Download $Url -Strict)) {
        $t = [BloomBuilder]::ParseTld($line)
        if ($t) { [void]$tlds.Add($t) }
    }
    if ($tlds.Count -lt $MinTlds) {
        throw "[TLDS] Parsed only $($tlds.Count) bare TLDs; expected at least $MinTlds. Refusing to write a degraded list (download may be broken)."
    }
    Write-Host "[TLDS] $($tlds.Count) abused TLDs extracted" -ForegroundColor Green
    return $tlds
}

# Write via temp file + backup so a crash mid-write can never leave a truncated
# resource in res/raw (the app refuses to start filtering without valid ones).
function Write-FileAtomic([string]$Path, [byte[]]$Bytes) {
    $fullPath = Join-Path (Resolve-Path .).Path $Path
    $dir = Split-Path -Parent $fullPath
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    $tmpPath = "$fullPath.tmp"
    $bakPath = "$fullPath.bak"
    try {
        $stream = [System.IO.File]::Open($tmpPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $stream.Write($Bytes, 0, $Bytes.Length)
        } finally {
            $stream.Dispose()
        }

        if ((Get-Item $tmpPath).Length -ne $Bytes.Length) {
            throw "Temporary file length mismatch for $Path"
        }

        if (Test-Path $fullPath) {
            Move-Item -LiteralPath $fullPath -Destination $bakPath -Force
        }
        Move-Item -LiteralPath $tmpPath -Destination $fullPath -Force
        if (Test-Path $bakPath) { Remove-Item -LiteralPath $bakPath -Force }
    } catch {
        if (Test-Path $tmpPath) { Remove-Item -LiteralPath $tmpPath -Force }
        if ((-not (Test-Path $fullPath)) -and (Test-Path $bakPath)) {
            Move-Item -LiteralPath $bakPath -Destination $fullPath -Force
        }
        throw
    }

    $kb = [int]((Get-Item $fullPath).Length / 1024)
    Write-Host "  Saved: $Path  ($kb KB)" -ForegroundColor Cyan
}

function Save-TldFile([string]$Path, $Tlds) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("# Generated by scripts/blocklist_tools.ps1 - do not edit by hand.`n")
    [void]$sb.Append("# Source: HaGeZi Most Abused TLDs (wildcard/spam-tlds-onlydomains.txt)`n")
    [void]$sb.Append("# One bare TLD per line; matched exactly against the last label of a hostname.`n")
    foreach ($t in $Tlds) { [void]$sb.Append($t).Append("`n") }
    # UTF-8 without BOM, LF endings: parsed by AbusedTldList on the Android side.
    $encoding = New-Object System.Text.UTF8Encoding($false)
    Write-FileAtomic $Path $encoding.GetBytes($sb.ToString())
}

function Save-BloomFile([string]$Path, [byte[]]$Bits, [long]$BitCount, [int]$NumHashes) {
    $hdr = New-Object byte[] 20
    $hdr[0]=0x47; $hdr[1]=0x5A; $hdr[2]=0x42; $hdr[3]=0x4C
    $hdr[7]=1
    $hdr[8]  = [byte](($NumHashes -shr 24) -band 0xFF)
    $hdr[9]  = [byte](($NumHashes -shr 16) -band 0xFF)
    $hdr[10] = [byte](($NumHashes -shr 8)  -band 0xFF)
    $hdr[11] = [byte]($NumHashes           -band 0xFF)
    $bc = $BitCount
    $hdr[12] = [byte](($bc -shr 56) -band 0xFF)
    $hdr[13] = [byte](($bc -shr 48) -band 0xFF)
    $hdr[14] = [byte](($bc -shr 40) -band 0xFF)
    $hdr[15] = [byte](($bc -shr 32) -band 0xFF)
    $hdr[16] = [byte](($bc -shr 24) -band 0xFF)
    $hdr[17] = [byte](($bc -shr 16) -band 0xFF)
    $hdr[18] = [byte](($bc -shr 8)  -band 0xFF)
    $hdr[19] = [byte]($bc           -band 0xFF)

    $payload = New-Object byte[] ($hdr.Length + $Bits.Length)
    [Array]::Copy($hdr, 0, $payload, 0, $hdr.Length)
    [Array]::Copy($Bits, 0, $payload, $hdr.Length, $Bits.Length)
    Write-FileAtomic $Path $payload
}

$AdultBuildSources = Merge-SourceTables @($AdultSources, $GamblingSources)

if ($Probe) {
    Write-Host "`n=== Probing Blocklist Source URLs ===" -ForegroundColor Cyan
    $allSources = @()
    foreach ($v in $AdSources.Values)         { $allSources += $v }
    foreach ($v in $SecuritySources.Values)   { $allSources += $v }
    foreach ($v in $AdultBuildSources.Values) { $allSources += $v }
    foreach ($v in $DohSources.Values)        { $allSources += $v }
    foreach ($v in $TelemetrySources.Values)  { $allSources += $v }
    foreach ($v in $DohSources.Values)        { $allSources += $v }
    foreach ($v in $TelemetrySources.Values)  { $allSources += $v }
    $deadUrls = @()
    foreach ($source in $allSources) {
        $url = Get-SourceUrl $source
        try {
            # GET, not HEAD: several list hosts reject HEAD requests.
            $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 60 -UserAgent $UserAgent
            Write-Host "  OK   [$($r.StatusCode)] $url" -ForegroundColor Green
        } catch {
            $code = if ($_.Exception.Response) { $_.Exception.Response.StatusCode.value__ } else { "ERR" }
            Write-Host "  DEAD [$code] $url" -ForegroundColor Red
            $deadUrls += $url
        }
    }

    Write-Host "`n=== Probing DoH Bypass Domains ===" -ForegroundColor Cyan
    $deadDoh = @()
    foreach ($dom in $DohDomains) {
        try {
            $ip = [System.Net.Dns]::GetHostAddresses($dom)[0]
            Write-Host "  OK   $dom -> $ip" -ForegroundColor Green
        } catch {
            Write-Host "  DEAD $dom" -ForegroundColor Red
            $deadDoh += $dom
        }
    }

    Write-Host "`n=== Summary ===" -ForegroundColor Cyan
    Write-Host "URLs alive : $($allSources.Count - $deadUrls.Count)/$($allSources.Count)"
    Write-Host "DoH alive  : $($DohDomains.Count - $deadDoh.Count)/$($DohDomains.Count)"
    if ($deadUrls.Count -gt 0) { Write-Host "DEAD URLs:" -ForegroundColor Red; $deadUrls | ForEach-Object { Write-Host "  $_" } }
    if ($deadDoh.Count  -gt 0) { Write-Host "DEAD DoH:"  -ForegroundColor Red; $deadDoh  | ForEach-Object { Write-Host "  $_" } }
    return
}

Write-Host "`n=== Grayzone Bloom Filter Builder ===" -ForegroundColor Cyan
Write-Host "FPR target ~0.009%  |  k=$K hashes  |  $BITS_PER_DOMAIN bits per domain`n" -ForegroundColor Gray

$critical = Read-CriticalDomains $CriticalDomainsFile

$ad = Build-Filter $AdSources $K "ADS" $AD_MIN_TOTAL_DOMAINS $critical
Save-BloomFile (Join-Path $OutputDir "adblock_bloom.bin") $ad.Bits $ad.BitCount $K

Write-Host ""
$security = Build-Filter $SecuritySources $K "SECURITY" $SECURITY_MIN_TOTAL_DOMAINS $critical
Save-BloomFile (Join-Path $OutputDir "security_bloom.bin") $security.Bits $security.BitCount $K

Write-Host ""
$adult = Build-Filter $AdultBuildSources $K "ADULT" $ADULT_MIN_TOTAL_DOMAINS $critical
Save-BloomFile (Join-Path $OutputDir "adult_bloom.bin") $adult.Bits $adult.BitCount $K

Write-Host ""
$tlds = Build-AbusedTldList $AbusedTldSourceUrl $MIN_ABUSED_TLDS
Save-TldFile (Join-Path $OutputDir "abused_tlds.txt") $tlds

Write-Host ""
$doh = Get-Domains $DohSources "DOH" $MIN_DOH_DOMAINS $critical
Save-DomainList (Join-Path $OutputDir "doh_domains.txt") $doh "Encrypted-DNS (DoH/DoT) resolvers; a listed domain blocks its subdomains too."

Write-Host ""
$telemetry = Get-Domains $TelemetrySources "TELEMETRY" $MIN_TELEMETRY_DOMAINS $critical
Save-DomainList (Join-Path $OutputDir "telemetry_domains.txt") $telemetry "Phone / OS vendor telemetry; a listed domain blocks its subdomains too."

# The protected-name list ships with the set, so new protections reach phones without an app update.
$criticalOut = Join-Path $OutputDir "critical_domains.txt"
if ((Resolve-Path $CriticalDomainsFile).Path -ne [System.IO.Path]::GetFullPath((Join-Path (Resolve-Path .).Path $criticalOut))) {
    Copy-Item -LiteralPath $CriticalDomainsFile -Destination $criticalOut -Force
}

Write-Host ""
Save-Manifest $OutputDir @(
    "adblock_bloom.bin", "adult_bloom.bin", "security_bloom.bin",
    "abused_tlds.txt", "doh_domains.txt", "telemetry_domains.txt", "critical_domains.txt"
)

Write-Host "`nDone." -ForegroundColor Cyan