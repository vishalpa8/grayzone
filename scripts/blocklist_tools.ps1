<#
.SYNOPSIS
    Grayzone Blocklist Tools

.DESCRIPTION
    Modes:
      (default)  Build Bloom filter binaries (adblock_bloom.bin, adult_bloom.bin)
      -AdultOnly Skip ad sources, only rebuild the adult filter
      -Probe     Health-check all source URLs and DoH bypass domains
#>
param(
    [string]$OutputDir = "app\src\main\res\raw",
    [switch]$AdultOnly,
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

$AD_BITS    = [long]50331648
$ADULT_BITS = [long]33554432
$K          = [int]10

$AD_MIN_TOTAL_DOMAINS    = 100000
$ADULT_MIN_TOTAL_DOMAINS = 50000

$AdSources = [ordered]@{
    "HaGeZi Ultimate"        = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/ultimate.txt"; MinDomains = 10000 }
    "HaGeZi Threat Intel"    = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.txt"; MinDomains = 1000 }
    "OISD Big"               = @{ Url = "https://big.oisd.nl/"; MinDomains = 10000 }
    "Block List Project Ads" = @{ Url = "https://blocklistproject.github.io/Lists/ads.txt"; MinDomains = 1000 }
    "StevenBlack Unified"    = @{ Url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"; MinDomains = 1000 }
    "Peter Lowe AdServers"   = @{ Url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"; MinDomains = 1000 }
}

$AdultSources = [ordered]@{
    "HaGeZi NSFW"             = @{ Url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/nsfw.txt"; MinDomains = 1000 }
    "OISD NSFW"               = @{ Url = "https://nsfw.oisd.nl/"; MinDomains = 1000 }
    "Block List Project Porn" = @{ Url = "https://blocklistproject.github.io/Lists/porn.txt"; MinDomains = 1000 }
    "StevenBlack Porn"        = @{ Url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts"; MinDomains = 1000 }
}

# Keep this in sync with BlocklistManager.dohBypassDomains.
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

function Build-Filter($Sources, [long]$BitCount, [int]$NumHashes, [string]$Label, [int]$MinTotalDomains) {
    Write-Host "[$Label] Building filter from $($Sources.Count) sources..." -ForegroundColor Yellow
    # Ceiling division. Note: PowerShell "/" is floating point and [int] ROUNDS
    # (banker's rounding), so the "+7)/8" idiom over-allocates by one byte when
    # BitCount is already a multiple of 8. Use Math::Ceiling for an exact size.
    $byteCount = [int][Math]::Ceiling($BitCount / 8.0)
    $bits      = New-Object byte[] $byteCount
    $seen      = New-Object 'System.Collections.Generic.HashSet[string]'

    foreach ($name in $Sources.Keys) {
        $source = $Sources[$name]
        $url = Get-SourceUrl $source
        $minDomains = Get-SourceMinDomains $source
        $before = $seen.Count
        $parsed = 0
        Write-Host "  $name" -ForegroundColor Gray
        foreach ($line in (Invoke-Download $url -Strict)) {
            $d = [BloomBuilder]::ParseDomain($line)
            if ($d) {
                $parsed++
                if ($seen.Add($d)) {
                    [BloomBuilder]::AddDomain($bits, $BitCount, $NumHashes, $d)
                }
            }
        }
        $added = $seen.Count - $before
        # Guard on domains PARSED from this source (download-health check), not on
        # newly-unique domains: small curated lists processed after large ones can
        # legitimately add ~0 new domains due to heavy overlap, which is not a failure.
        if ($parsed -lt $minDomains) {
            throw "[$Label] Source '$name' parsed only $parsed valid domains; expected at least $minDomains. Refusing to write a degraded filter (download may be broken)."
        }
        Write-Host "  -> $($seen.Count) total unique so far (source parsed $parsed, +$added new)" -ForegroundColor DarkGray
    }

    if ($seen.Count -lt $MinTotalDomains) {
        throw "[$Label] Total unique domains $($seen.Count) is below safety threshold $MinTotalDomains. Refusing to write filter."
    }

    Write-Host "[$Label] $($seen.Count) unique domains inserted into filter" -ForegroundColor Green
    return @{ Bits = $bits; Count = $seen.Count }
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

    $fullPath = Join-Path (Resolve-Path .).Path $Path
    $dir = Split-Path -Parent $fullPath
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    $tmpPath = "$fullPath.tmp"
    $bakPath = "$fullPath.bak"
    try {
        $stream = [System.IO.File]::Open($tmpPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $stream.Write($hdr, 0, $hdr.Length)
            $stream.Write($Bits, 0, $Bits.Length)
        } finally {
            $stream.Dispose()
        }

        $expectedLength = $hdr.Length + $Bits.Length
        if ((Get-Item $tmpPath).Length -ne $expectedLength) {
            throw "Temporary bloom file length mismatch"
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

if ($Probe) {
    Write-Host "`n=== Probing Blocklist Source URLs ===" -ForegroundColor Cyan
    $allSources = @()
    foreach ($v in $AdSources.Values)    { $allSources += $v }
    foreach ($v in $AdultSources.Values) { $allSources += $v }
    $deadUrls = @()
    foreach ($source in $allSources) {
        $url = Get-SourceUrl $source
        try {
            $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 15 -Method Head -UserAgent $UserAgent
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
Write-Host "FPR target ~0.1%  |  k=$K hashes  |  Ad bits=$AD_BITS  |  Adult bits=$ADULT_BITS`n" -ForegroundColor Gray

if (-not $AdultOnly) {
    $ad = Build-Filter $AdSources $AD_BITS $K "ADS" $AD_MIN_TOTAL_DOMAINS
    Save-BloomFile (Join-Path $OutputDir "adblock_bloom.bin") $ad.Bits $AD_BITS $K
    Write-Host "  $($ad.Count) domains encoded" -ForegroundColor Green
} else {
    Write-Host "[ADS] Skipped (-AdultOnly)" -ForegroundColor DarkGray
}

Write-Host ""
$adult = Build-Filter $AdultSources $ADULT_BITS $K "ADULT" $ADULT_MIN_TOTAL_DOMAINS
Save-BloomFile (Join-Path $OutputDir "adult_bloom.bin") $adult.Bits $ADULT_BITS $K
Write-Host "  $($adult.Count) domains encoded" -ForegroundColor Green

Write-Host "`nDone. Run: .\gradlew.bat installDebug" -ForegroundColor Cyan