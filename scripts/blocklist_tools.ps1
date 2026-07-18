<#
.SYNOPSIS
    Grayzone Blocklist Tools

.DESCRIPTION
    Modes:
      (default)  Build Bloom filter binaries (adblock_bloom.bin, adult_bloom.bin)
      -AdultOnly Skip ad sources, only rebuild the adult filter
      -Probe     Health-check all source URLs and DoH bypass domains

.EXAMPLE
    .\scripts\blocklist_tools.ps1
    .\scripts\blocklist_tools.ps1 -AdultOnly
    .\scripts\blocklist_tools.ps1 -Probe
#>
param(
    [string]$OutputDir = "app\src\main\res\raw",
    [switch]$AdultOnly,
    [switch]$Probe
)

$ErrorActionPreference = "Stop"
$ProgressPreference    = "SilentlyContinue"

# =============================================================================
# BLOOM FILTER PARAMETERS
# m = ceil(-n*ln(p)/ln(2)^2), k = round(m/n*ln(2))
# p = 0.001 (0.1% FPR), k = 10 for both
# =============================================================================
$AD_BITS    = [long]12222208   # 1.5 MB,  optimal for n=850k
$ADULT_BITS = [long]2449408    # 299 KB,  optimal for n=180k
$K          = [int]10

# =============================================================================
# SOURCES  (all verified alive 2026-07-18)
# =============================================================================
$AdSources = [ordered]@{
    "StevenBlack unified"  = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    "Peter Lowe"           = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
    "AdGuard DNS filter 1" = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt"
    "Hagezi Multi Pro"     = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.txt"
}

$AdultSources = [ordered]@{
    "StevenBlack porn-only"              = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"
    "StevenBlack fakenews+gambling+porn" = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews-gambling-porn/hosts"
    "StevenBlack gambling+porn"          = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-porn/hosts"
    "AdGuard DNS filter 2"               = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_2.txt"
}

$DohDomains = @(
    "dns.google","8888.google","cloudflare-dns.com","1dot1dot1dot1.cloudflare-dns.com",
    "mozilla.cloudflare-dns.com","dns.quad9.net","dns9.quad9.net","dns10.quad9.net",
    "dns11.quad9.net","dns.adguard.com","dns-family.adguard.com","dns.nextdns.io",
    "doh.opendns.com","doh.xfinity.com","dns.alidns.com","dns.google.com"
)

# =============================================================================
# INLINE C#  — fast native bloom builder using same algorithm as Kotlin class
# =============================================================================
Add-Type -Language CSharp -TypeDefinition @'
using System;
using System.Security.Cryptography;
using System.Text;

public static class BloomBuilder {
    [ThreadStatic] private static SHA256 _sha;
    private static SHA256 Sha { get { return _sha ?? (_sha = SHA256.Create()); } }

    public static void AddDomain(byte[] bits, long bitCount, int k, string domain) {
        byte[] d = Sha.ComputeHash(Encoding.UTF8.GetBytes(domain.ToLower()));
        long h1 = ((long)d[0]<<24)|((long)d[1]<<16)|((long)d[2]<<8)|d[3];
        long h2 = ((long)d[4]<<24)|((long)d[5]<<16)|((long)d[6]<<8)|d[7];
        if (h2 == 0) h2 = 1;
        for (int i = 0; i < k; i++) {
            long idx = (h1 + (long)i * h2) % bitCount;
            bits[idx / 8] |= (byte)(1 << (int)(idx % 8));
        }
    }

    public static string ParseDomain(string raw) {
        int h = raw.IndexOf('#');
        string line = (h >= 0 ? raw.Substring(0, h) : raw).Trim();
        if (line.Length == 0 || line.IndexOf(':') >= 0) return null;
        string dom;
        if (line.StartsWith("0.0.0.0 ") || line.StartsWith("0.0.0.0\t"))
            dom = line.Substring(8).Trim();
        else if (line.StartsWith("127.0.0.1 ") || line.StartsWith("127.0.0.1\t"))
            dom = line.Substring(10).Trim();
        else if (char.IsDigit(line[0])) {
            int sp = line.IndexOfAny(new char[]{' ','\t'});
            if (sp < 0) return null;
            dom = line.Substring(sp).Trim();
        } else dom = line;
        if (string.IsNullOrEmpty(dom)) return null;
        if (dom.StartsWith("*.")) dom = dom.Substring(2);
        if (dom.IndexOf('.') < 0) return null;
        string lo = dom.ToLower();
        if (lo == "localhost" || lo == "broadcasthost" || lo == "0.0.0.0") return null;
        return lo;
    }
}
'@

# =============================================================================
# HELPERS
# =============================================================================

function Invoke-Download([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 60).Content -split "`n" }
    catch { Write-Warning "Failed: $Url`n  $_"; return @() }
}

function Build-Filter($Sources, [long]$BitCount, [int]$NumHashes, [string]$Label) {
    Write-Host "[$Label] Building filter from $($Sources.Count) sources..." -ForegroundColor Yellow
    $byteCount = [int](($BitCount + 7) / 8)
    $bits      = New-Object byte[] $byteCount
    $seen      = New-Object 'System.Collections.Generic.HashSet[string]'

    foreach ($name in $Sources.Keys) {
        Write-Host "  $name" -ForegroundColor Gray
        foreach ($line in (Invoke-Download $Sources[$name])) {
            $d = [BloomBuilder]::ParseDomain($line)
            if ($d -and $seen.Add($d)) {
                [BloomBuilder]::AddDomain($bits, $BitCount, $NumHashes, $d)
            }
        }
        Write-Host "  -> $($seen.Count) unique domains" -ForegroundColor DarkGray
    }
    Write-Host "[$Label] $($seen.Count) unique domains inserted into filter" -ForegroundColor Green
    return @{ Bits = $bits; Count = $seen.Count }
}

function Save-BloomFile([string]$Path, [byte[]]$Bits, [long]$BitCount, [int]$NumHashes) {
    # Header: magic(4) + version(4) + numHashes(4) + bitCount(8) = 20 bytes
    $hdr = New-Object byte[] 20
    $hdr[0]=0x47; $hdr[1]=0x5A; $hdr[2]=0x42; $hdr[3]=0x4C  # GZBL
    $hdr[7]=1                                                  # version
    # numHashes big-endian int32
    $hdr[8]  = [byte](($NumHashes -shr 24) -band 0xFF)
    $hdr[9]  = [byte](($NumHashes -shr 16) -band 0xFF)
    $hdr[10] = [byte](($NumHashes -shr 8)  -band 0xFF)
    $hdr[11] = [byte]($NumHashes           -band 0xFF)
    # bitCount big-endian int64
    $bc = $BitCount
    $hdr[12] = [byte](($bc -shr 56) -band 0xFF)
    $hdr[13] = [byte](($bc -shr 48) -band 0xFF)
    $hdr[14] = [byte](($bc -shr 40) -band 0xFF)
    $hdr[15] = [byte](($bc -shr 32) -band 0xFF)
    $hdr[16] = [byte](($bc -shr 24) -band 0xFF)
    $hdr[17] = [byte](($bc -shr 16) -band 0xFF)
    $hdr[18] = [byte](($bc -shr 8)  -band 0xFF)
    $hdr[19] = [byte]($bc           -band 0xFF)

    $all = New-Object byte[] ($hdr.Length + $Bits.Length)
    [Array]::Copy($hdr,  0, $all, 0,           $hdr.Length)
    [Array]::Copy($Bits, 0, $all, $hdr.Length, $Bits.Length)
    [System.IO.File]::WriteAllBytes((Resolve-Path .).Path + '\' + $Path, $all)

    $kb = [int]($all.Length / 1024)
    Write-Host "  Saved: $Path  ($kb KB)" -ForegroundColor Cyan
}

# =============================================================================
# PROBE MODE
# =============================================================================
if ($Probe) {
    Write-Host ""
    Write-Host "=== Probing Blocklist Source URLs ===" -ForegroundColor Cyan
    Write-Host ""
    $allUrls = @()
    foreach ($v in $AdSources.Values)    { $allUrls += $v }
    foreach ($v in $AdultSources.Values) { $allUrls += $v }
    $deadUrls = @()
    foreach ($url in $allUrls) {
        try {
            $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 15 -Method Head
            Write-Host "  OK   [$($r.StatusCode)] $url" -ForegroundColor Green
        } catch {
            $code = if ($_.Exception.Response) { $_.Exception.Response.StatusCode.value__ } else { "ERR" }
            Write-Host "  DEAD [$code] $url" -ForegroundColor Red
            $deadUrls += $url
        }
    }
    Write-Host ""
    Write-Host "=== Probing DoH Bypass Domains ===" -ForegroundColor Cyan
    Write-Host ""
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
    Write-Host ""
    Write-Host "=== Summary ===" -ForegroundColor Cyan
    Write-Host "URLs alive : $($allUrls.Count - $deadUrls.Count)/$($allUrls.Count)"
    Write-Host "DoH alive  : $($DohDomains.Count - $deadDoh.Count)/$($DohDomains.Count)"
    if ($deadUrls.Count  -gt 0) { Write-Host "DEAD URLs:" -ForegroundColor Red;   $deadUrls | % { Write-Host "  $_" } }
    if ($deadDoh.Count   -gt 0) { Write-Host "DEAD DoH:"  -ForegroundColor Red;   $deadDoh  | % { Write-Host "  $_" } }
    return
}

# =============================================================================
# UPDATE MODE
# =============================================================================
Write-Host ""
Write-Host "=== Grayzone Bloom Filter Builder ===" -ForegroundColor Cyan
Write-Host "FPR ~0.1%  |  k=$K hashes  |  Ad bits=$AD_BITS  |  Adult bits=$ADULT_BITS" -ForegroundColor Gray
Write-Host ""

if (-not $AdultOnly) {
    $ad = Build-Filter $AdSources $AD_BITS $K "ADS"
    Save-BloomFile (Join-Path $OutputDir "adblock_bloom.bin") $ad.Bits $AD_BITS $K
    Write-Host "  $($ad.Count) domains encoded" -ForegroundColor Green
} else {
    Write-Host "[ADS] Skipped (-AdultOnly)" -ForegroundColor DarkGray
}

Write-Host ""
$adult = Build-Filter $AdultSources $ADULT_BITS $K "ADULT"
Save-BloomFile (Join-Path $OutputDir "adult_bloom.bin") $adult.Bits $ADULT_BITS $K
Write-Host "  $($adult.Count) domains encoded" -ForegroundColor Green

Write-Host ""
Write-Host "Done. Run: .\gradlew.bat installDebug" -ForegroundColor Cyan
