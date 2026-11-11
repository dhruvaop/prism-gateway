# Starts the Prism gateway against the hosted PostgreSQL database configured in .env.
# Use this after Flyway has applied the migrations and the SQLite snapshot has been seeded.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\run-backend-postgres.ps1
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
    throw "No .env found at $envFile. DATABASE_URL, DATABASE_USERNAME, and DATABASE_PASSWORD are required."
}

if (-not $env:DATABASE_URL) {
    throw "DATABASE_URL is not set. Add the Supabase JDBC URL to .env."
}

$env:SPRING_PROFILES_ACTIVE = 'enterprise'
Push-Location (Join-Path $root 'backend')
try {
    & mvn spring-boot:run
} finally {
    Pop-Location
}
