# Starts the Prism gateway locally with variables from the repo-root .env file.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\run-backend.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'

if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $name, $value = $line -split '=', 2
        $name = $name.Trim()
        if (-not $name) { continue }
        [Environment]::SetEnvironmentVariable($name, $value.Trim(), 'Process')
    }
    Write-Host "Loaded environment from $envFile"
} else {
    Write-Warning "No .env found at $envFile; using defaults"
}

if ($env:OPENROUTER_API_KEY) {
    Write-Host "OpenRouter key detected (primary=$env:OPENROUTER_PREFER_AS_PRIMARY)"
} else {
    Write-Host "No OPENROUTER_API_KEY set - the bundled Alpha/Beta mock providers will answer with canned text"
}

Push-Location (Join-Path $root 'backend')
try {
    & mvn spring-boot:run
} finally {
    Pop-Location
}
