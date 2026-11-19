#requires -Version 5.1
<#
.SYNOPSIS
  Scans tracked files for content that must not be published.

.DESCRIPTION
  Generic guard for the public Enterprise AI Gateway demo repository. It
  intentionally does not contain real customer names, production IPs, or
  secret values. Run from anywhere inside the repository.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-RepoRoot {
    $root = git rev-parse --show-toplevel 2>$null
    if (-not $root) {
        throw 'Not inside a git repository.'
    }
    return $root.Trim()
}

$root = Get-RepoRoot
Push-Location $root
try {
    $tracked = @(git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw 'git ls-files failed.'
    }

    $failures = New-Object System.Collections.Generic.List[string]

    $forbiddenPaths = @(
        '(^|/)\.env$',
        '(^|/)cookies\.txt$',
        '(^|/)backup_.*\.sql$',
        '(^|/)migration_backup_.*',
        '\.pem$',
        '\.key$',
        '\.p12$',
        '\.pfx$',
        '(^|/)nginx/',
        '(^|/)docs/DEV-GUIDE\.md$',
        '(^|/)docs/MIGRATION-PLAN\.md$',
        '(^|/)docs/K8S-LOCAL-PLAN\.md$',
        '(^|/)docs/K8S-WALKTHROUGH\.md$',
        '(^|/)docs/HOW-TO-TEST-AND-INTRO\.md$',
        '(^|/)docs/phase0-.*\.md$',
        '(^|/)docs/tech-solr-rag-analysis\.md$',
        '(^|/)ops/payloads/',
        '(^|/)ops/devbox/',
        '(^|/)ops/deploy/redeploy-container\.sh$',
        '(^|/)ops/deploy/switch-model\.sh$',
        '(^|/)geminiapi-key\.txt$',
        '(^|/)openrouter-apikey\.txt$',
        '(^|/)docs/HANDOVER-',
        '\.pptx$'
    )

    foreach ($file in $tracked) {
        if ($file -eq '.env.example') {
            continue
        }
        foreach ($pattern in $forbiddenPaths) {
            if ($file -match $pattern) {
                $failures.Add("Forbidden tracked file: $file (matched: $pattern)")
                break
            }
        }
    }

    $secretPattern = 'BEGIN [A-Z ]*PRIVATE KEY|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]{10,}'
    $secretHits = @(git grep -n -I -E $secretPattern -- . 2>$null)
    foreach ($hit in $secretHits) {
        $failures.Add("Possible secret: $hit")
    }

    $ipPattern = '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b'
    $allowedIps = @('127.0.0.1', '0.0.0.0', '255.255.255.255')
    $ipHits = @(git grep -n -I -E $ipPattern -- . 2>$null)
    foreach ($hit in $ipHits) {
        $ipMatches = [regex]::Matches($hit, $ipPattern)
        $nonLoopback = @($ipMatches | Where-Object { $allowedIps -notcontains $_.Value })
        if ($nonLoopback.Count -gt 0) {
            $failures.Add("Non-loopback IPv4 found (review): $hit")
        }
    }

    if ($failures.Count -gt 0) {
        Write-Host 'Public safety check FAILED:' -ForegroundColor Red
        foreach ($failure in $failures) {
            Write-Host " - $failure"
        }
        exit 1
    }

    Write-Host 'Public safety check passed.' -ForegroundColor Green
    exit 0
}
finally {
    Pop-Location
}
