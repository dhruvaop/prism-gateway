param(
    [string]$BaseUrl = "http://localhost:8081",
    [int]$HealthTimeoutSeconds = 180,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$composeFile = Join-Path $projectRoot "docker-compose.yml"
$envFile = Join-Path $projectRoot ".env"

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail "Docker was not found. Install Docker Desktop/Engine with the Compose plugin, then retry."
}

& docker compose version *> $null
if ($LASTEXITCODE -ne 0) {
    Fail "Docker Compose is unavailable. Install or enable the Docker Compose plugin, then retry."
}

if (-not (Test-Path $envFile)) {
    Fail "Missing .env. Copy .env.example to .env and set DATABASE_PASSWORD and a private JWT_SECRET (at least 32 characters)."
}

$envLines = Get-Content $envFile
$jwtLine = $envLines | Where-Object { $_ -match '^\s*JWT_SECRET\s*=' } | Select-Object -First 1
$jwtSecret = if ($jwtLine) { ($jwtLine -replace '^\s*JWT_SECRET\s*=\s*', '').Trim().Trim('"').Trim("'") } else { "" }
if ($jwtSecret.Length -lt 32 -or $jwtSecret -match 'replace-with|change-me|example') {
    Fail "Set JWT_SECRET in .env to a private random value of at least 32 characters before deploying."
}

Push-Location $projectRoot
try {
    $composeArgs = @("compose", "-f", $composeFile, "up", "-d")
    if (-not $SkipBuild) { $composeArgs += "--build" }
    $composeArgs += @("db", "alpha", "beta", "gateway", "console")

    Write-Host "Starting PostgreSQL, both provider services, the Java gateway, and the console proxy..."
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) { Fail "Docker Compose failed to start the services. Review the output above." }

    $healthUrl = "$($BaseUrl.TrimEnd('/'))/health"
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    $healthy = $false
    do {
        try {
            $response = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
            $healthy = $true
        } catch {
            Start-Sleep -Seconds 3
        }
    } while (-not $healthy -and (Get-Date) -lt $deadline)

    if (-not $healthy) {
        Write-Host "Services are running, but the gateway did not answer $healthUrl within $HealthTimeoutSeconds seconds."
        Write-Host "Inspect startup with: docker compose logs --tail=100 gateway db"
        exit 2
    }

    Write-Host "Gateway is healthy at $healthUrl"
    Write-Host "OpenAI-compatible API: $($BaseUrl.TrimEnd('/'))/v1/chat/completions"
    Write-Host "Console: $BaseUrl"
    Write-Host "Check service state with: docker compose ps"
    Write-Host "Follow gateway logs with: docker compose logs -f gateway"
    Write-Host "Connect with: curl.exe -X POST `"$($BaseUrl.TrimEnd('/'))/v1/chat/completions`" -H `"Authorization: Bearer prism-sk-search-1a2b3c`" -H `"Content-Type: application/json`" -d `"{\`"model\`":\`"fast\`",\`"messages\`":[{\`"role\`":\`"user\`",\`"content\`":\`"Hello from Prism\`"}]}`""
} finally {
    Pop-Location
}
