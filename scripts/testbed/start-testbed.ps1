#requires -Version 7.0
<#
.SYNOPSIS
  M16A local testbed - starts an isolated Postgres+Keycloak (Docker) plus the
  existing backend/frontend (host processes) for a repeatable, real-OIDC,
  personal-Google-account testbed. See docs/runbooks/M16A_LOCAL_TESTBED.md.

.DESCRIPTION
  - Never touches the canonical stack (infra/docker-compose.dev.yml, ports
    5432/8080/8180/5173) - this is a fully separate Compose project
    ("sdv-testbed") on ports 15432/18180/18080/15173, all bound to loopback.
  - Generates local-only secrets/account passwords ONCE under the git-ignored
    infra/testbed/secrets/ directory and reuses them on every subsequent run
    - it never resets an existing password or recreates an existing user.
  - Zero additional service cost: only local Docker images and the existing
    Gradle/npm tooling. No cloud/billing resource is created by this script.
  - Backend/frontend secrets never appear in a process command line - see
    _lib.ps1's Start-TrackedProcess/New-IsolatedEnvironment. The backend is
    launched from its own built jar (not `gradlew bootRun`), so its lifetime
    is not tied to a shared, detachable Gradle daemon.

.NOTES
  Run: scripts\testbed\start-testbed.ps1  (from anywhere - paths are resolved
  relative to the repository root).
#>

[CmdletBinding()]
param(
    [switch]$EnableIndexing,
    [ValidateSet('FirstRun', 'Resume')][string]$IndexingActivationMode,
    [string]$KafkaBootstrapServers,
    [string]$IndexingTopic,
    [string]$IndexingGroupId,
    [string]$AiServiceUrl,
    [ValidateSet('NewEmptyIndex', 'ConfirmedSameKey')][string]$IndexHmacState,
    [switch]$PrepareNewIndexingTopics
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_lib.ps1')
. (Join-Path $PSScriptRoot '_indexing-activation.ps1')

Assert-M17IndexingParameters -Enabled $EnableIndexing.IsPresent `
    -ActivationMode $IndexingActivationMode -KafkaBootstrapServers $KafkaBootstrapServers `
    -Topic $IndexingTopic -GroupId $IndexingGroupId -AiServiceUrl $AiServiceUrl `
    -IndexHmacState $IndexHmacState -PrepareNewTopics $PrepareNewIndexingTopics.IsPresent

$paths = Get-TestbedPaths
$KeycloakRealm = 'sdv-testbed'
$FrontendPort = 15173
$BackendPort = 18080
$KeycloakPort = 18180
$PostgresPort = 15432

# ------------------------------------------------------------------
# 0. Refuse to start a duplicate - identify an already-owned healthy
#    instance instead.
# ------------------------------------------------------------------
$existingBackend = Test-TrackedProcessAlive -Name 'backend' -PidsDir $paths.PidsDir
$existingFrontend = Test-TrackedProcessAlive -Name 'frontend' -PidsDir $paths.PidsDir
if ($existingBackend -or $existingFrontend) {
    Write-Host "The testbed already appears to be running (tracked, verified process identity):" -ForegroundColor Yellow
    if ($existingBackend) { Write-Host "  backend  PID $($existingBackend.Id), started $($existingBackend.StartTime)" }
    if ($existingFrontend) { Write-Host "  frontend PID $($existingFrontend.Id), started $($existingFrontend.StartTime)" }
    if ($EnableIndexing) {
        Invoke-Fail 'Indexing was requested but an existing baseline/testbed process is already running. It is not indexing-enabled merely because this invocation included a switch. Run the normal testbed stop yourself, then restart with the explicit activation settings.'
    }
    Write-Host "Run scripts\testbed\status-testbed.ps1 to check readiness, or scripts\testbed\stop-testbed.ps1 first if you want a clean restart."
    exit 0
}

# ------------------------------------------------------------------
# 1. Preflight
# ------------------------------------------------------------------
Write-Step 'Preflight checks'

docker info *>$null
if ($LASTEXITCODE -ne 0) { Invoke-Fail 'Docker Desktop engine is not reachable (docker info exited nonzero). Start Docker Desktop yourself, then re-run this script.' }
Write-Ok 'Docker engine reachable'

& java -version 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Invoke-Fail 'Java is not on PATH or failed to run (this repo uses Java 21).' }
Write-Ok 'Java present'

& node -v 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Invoke-Fail 'Node.js is not on PATH or failed to run.' }
Write-Ok 'Node present'

& npm -v 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Invoke-Fail 'npm is not on PATH or failed to run.' }
if (-not (Test-Path (Join-Path $paths.FrontendDir 'node_modules'))) {
    Invoke-Fail "frontend/node_modules is missing - run 'npm install' in frontend/ once before starting the testbed (this script does not install dependencies for you)."
}
Write-Ok 'npm present and frontend dependencies already installed'

foreach ($portCheck in @(
        @{ Port = $FrontendPort; Name = 'frontend (Vite)' },
        @{ Port = $BackendPort; Name = 'backend (Spring Boot)' },
        @{ Port = $KeycloakPort; Name = 'Keycloak' },
        @{ Port = $PostgresPort; Name = 'Postgres' }
    )) {
    if (-not (Test-PortFree -Port $portCheck.Port)) {
        Invoke-Fail "Port $($portCheck.Port) ($($portCheck.Name)) is already in use. This script never stops another listener - free the port (or find what's using it with 'Get-NetTCPConnection -LocalPort $($portCheck.Port)') and re-run."
    }
}
Write-Ok "Ports $FrontendPort/$BackendPort/$KeycloakPort/$PostgresPort are free"

New-Item -ItemType Directory -Force -Path $paths.SecretsDir, $paths.PidsDir, $paths.LogsDir | Out-Null

# ------------------------------------------------------------------
# 2. Generate-once local secrets
# ------------------------------------------------------------------
Write-Step 'Local secrets (generated once, reused afterward)'

$PostgresPasswordFile = Join-Path $paths.SecretsDir 'postgres_password.txt'
$RuntimePasswordFile = Join-Path $paths.SecretsDir 'sdv_runtime_password.txt'
$TokenKeyFile = Join-Path $paths.SecretsDir 'token-encryption-key.txt'
$GoogleEnvFile = Join-Path $paths.SecretsDir 'google-oauth.local.env'
$AccountsFile = Join-Path $paths.SecretsDir 'testbed-accounts.local.txt'

if (-not (Test-Path $PostgresPasswordFile)) {
    New-RandomSecret -Length 32 | Set-Content -NoNewline -Path $PostgresPasswordFile
    Write-Ok 'Generated postgres_password.txt'
} else { Write-Ok 'Reusing existing postgres_password.txt' }

if (-not (Test-Path $RuntimePasswordFile)) {
    New-RandomSecret -Length 32 | Set-Content -NoNewline -Path $RuntimePasswordFile
    Write-Ok 'Generated sdv_runtime_password.txt'
} else { Write-Ok 'Reusing existing sdv_runtime_password.txt' }

if (-not (Test-Path $TokenKeyFile)) {
    New-Aes256KeyBase64 | Set-Content -NoNewline -Path $TokenKeyFile
    Write-Ok 'Generated token-encryption-key.txt (32-byte AES key, base64)'
} else { Write-Ok 'Reusing existing token-encryption-key.txt' }

if (-not (Test-Path $GoogleEnvFile)) {
    @'
# M16A local testbed - fill these in yourself for the LIVE GOOGLE acceptance
# stage (docs/runbooks/M16A_LOCAL_TESTBED.md). Leave blank for the BASELINE
# stage - Google connect will safely show OAUTH_UNAVAILABLE until filled in.
# Never commit this file (it is git-ignored) and never paste these values
# into chat.
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
# Set this after uploading your own small (a few KB is enough) plain .txt
# test file to your test Google account's Drive and copying its file ID
# from the URL. The diagnostic's own cap is 1 MiB, applied AFTER the
# existing connector already reads the file under its own larger (25MB)
# bound - keep this file tiny regardless.
SDV_TESTBED_DIAGNOSTICS_ALLOWED_FILE_ID=
'@ | Set-Content -Path $GoogleEnvFile
    Write-Ok "Created $GoogleEnvFile - edit it for the LIVE GOOGLE stage (optional for BASELINE)"
} else { Write-Ok 'Reusing existing google-oauth.local.env' }

$googleEnv = @{}
Get-Content $GoogleEnvFile | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object {
    $parts = $_.Split('=', 2)
    $googleEnv[$parts[0]] = $parts[1]
}

# ------------------------------------------------------------------
# 3. Start Postgres + Keycloak (isolated Compose project)
# ------------------------------------------------------------------
Write-Step "Starting isolated Compose project '$($paths.ProjectName)' (Postgres + Keycloak only)"

Push-Location $paths.TestbedDir
try {
    docker compose -p $paths.ProjectName -f $paths.ComposeFile up -d
    if ($LASTEXITCODE -ne 0) { Invoke-Fail 'docker compose up failed - see output above.' }
} finally {
    Pop-Location
}

Write-Step 'Waiting for Postgres to become healthy (bounded)'
$pgReady = $false
for ($i = 0; $i -lt 30; $i++) {
    docker compose -p $paths.ProjectName -f $paths.ComposeFile exec -T postgres pg_isready -U sdv_user -d sdv *>$null
    if ($LASTEXITCODE -eq 0) { $pgReady = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $pgReady) { Invoke-Fail 'Postgres did not become ready within 60s. Check: docker compose -p sdv-testbed -f infra/testbed/docker-compose.testbed.yml logs postgres' }
Write-Ok 'Postgres ready'

Write-Step 'Waiting for Keycloak realm to become available (bounded)'
$kcReady = $false
for ($i = 0; $i -lt 45; $i++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$KeycloakPort/realms/$KeycloakRealm" -TimeoutSec 3
        if ($response.StatusCode -eq 200) { $kcReady = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $kcReady) { Invoke-Fail "Keycloak realm '$KeycloakRealm' did not become available within 90s. Check: docker compose -p sdv-testbed -f infra/testbed/docker-compose.testbed.yml logs keycloak" }
Write-Ok "Realm '$KeycloakRealm' available"

# ------------------------------------------------------------------
# 4. Set the three fixture accounts' passwords (once)
# ------------------------------------------------------------------
Write-Step 'Testbed accounts (sdv-user / sdv-admin-a / sdv-admin-b)'

if (Test-Path $AccountsFile) {
    Write-Ok "Reusing existing credentials - see $AccountsFile"
} else {
    Write-Host '    Setting each fixture user''s password once via the Keycloak Admin REST API...'
    $tokenResponse = Invoke-RestMethod -Method Post `
        -Uri "http://localhost:$KeycloakPort/realms/master/protocol/openid-connect/token" `
        -ContentType 'application/x-www-form-urlencoded' `
        -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'admin' }
    $adminToken = $tokenResponse.access_token
    $authHeader = @{ Authorization = "Bearer $adminToken" }

    $lines = @()
    foreach ($username in @('sdv-user', 'sdv-admin-a', 'sdv-admin-b')) {
        $users = Invoke-RestMethod -Method Get -Headers $authHeader `
            -Uri "http://localhost:$KeycloakPort/admin/realms/$KeycloakRealm/users?username=$username&exact=true"
        if (-not $users -or $users.Count -eq 0) {
            Invoke-Fail "User '$username' was not found in realm '$KeycloakRealm' - the realm import may not have run (fresh volume required for --import-realm)."
        }
        $userId = $users[0].id
        $password = New-RandomSecret -Length 20
        Invoke-RestMethod -Method Put -Headers $authHeader `
            -Uri "http://localhost:$KeycloakPort/admin/realms/$KeycloakRealm/users/$userId/reset-password" `
            -ContentType 'application/json' `
            -Body (@{ type = 'password'; value = $password; temporary = $false } | ConvertTo-Json)
        $lines += "$username : $password"
    }

    @(
        '# M16A local testbed fixture account credentials - generated once by start-testbed.ps1.'
        '# Never commit this file (it is git-ignored) and never paste these values into chat.'
        ''
    ) + $lines | Set-Content -Path $AccountsFile
    Write-Ok "Generated and saved to $AccountsFile"
}

# ------------------------------------------------------------------
# 4.5 Optional M17 indexing activation gate. This is deliberately after
#     Postgres is ready (a normal stop removes its container) and before the
#     enabled backend is built/spawned. It never loads or inspects a secret.
# ------------------------------------------------------------------
if ($EnableIndexing) {
    Write-Step 'M17 explicit indexing activation gate (content-free, fail closed)'
    Invoke-M17IndexingActivationPreflight -Paths $paths -ActivationMode $IndexingActivationMode `
        -KafkaBootstrapServers $KafkaBootstrapServers -Topic $IndexingTopic `
        -GroupId $IndexingGroupId -AiServiceUrl $AiServiceUrl -IndexHmacState $IndexHmacState `
        -PrepareNewTopics:$PrepareNewIndexingTopics
    Write-Ok 'Indexing activation prerequisites confirmed before backend launch'
}

# ------------------------------------------------------------------
# 5. Build and start the backend (host process, testbed profile)
#    Built once via `gradlew bootJar`, then run directly as `java -jar` -
#    NOT `gradlew bootRun`, whose actual application process can end up
#    tied to a shared, reusable Gradle daemon that outlives the launcher's
#    own idea of "the app's lifetime". `java -jar` is a single, clearly
#    owned process this script's stop path can reliably account for.
# ------------------------------------------------------------------
Write-Step 'Building backend (gradlew bootJar)'
Push-Location $paths.BackendDir
try {
    & '.\gradlew.bat' bootJar
    if ($LASTEXITCODE -ne 0) { Invoke-Fail 'Backend build (gradlew bootJar) failed - see console output above.' }
} finally {
    Pop-Location
}
$bootJar = Get-ChildItem -Path (Join-Path $paths.BackendDir 'build\libs\*.jar') -Exclude '*-plain.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $bootJar) { Invoke-Fail 'No executable jar found under backend/build/libs after bootJar.' }
Write-Ok "Built $($bootJar.Name)"

$postgresPassword = (Get-Content $PostgresPasswordFile -Raw).Trim()
$runtimePassword = (Get-Content $RuntimePasswordFile -Raw).Trim()
$tokenKey = (Get-Content $TokenKeyFile -Raw).Trim()
$resolvedDatasourceUrl = "jdbc:postgresql://localhost:$PostgresPort/sdv"

# Fail Closed 사전 검증 - 실제로 Migration을 실행할 수 있는 애플리케이션을
# 기동하기 전, 지금 넘기려는 DB 대상이 정말 이 Testbed 것인지 다시 확인한다.
Assert-TestbedDatabaseTarget -DatasourceUrl $resolvedDatasourceUrl -FlywayUser 'sdv_user' -ExpectedPort $PostgresPort
Write-Ok "Verified backend will target $resolvedDatasourceUrl (Flyway user sdv_user)"

$backendOverrides = @{
    SPRING_PROFILES_ACTIVE                  = 'testbed'
    SPRING_DATASOURCE_URL                   = $resolvedDatasourceUrl
    SPRING_DATASOURCE_USERNAME              = 'sdv'
    SPRING_DATASOURCE_PASSWORD              = $runtimePassword
    SPRING_FLYWAY_USER                      = 'sdv_user'
    SPRING_FLYWAY_PASSWORD                  = $postgresPassword
    KEYCLOAK_ISSUER_URI                     = "http://localhost:$KeycloakPort/realms/$KeycloakRealm"
    KEYCLOAK_AUDIENCE                       = 'sdv-backend'
    SERVER_PORT                             = "$BackendPort"
    SERVER_ADDRESS                          = '127.0.0.1'
    GOOGLE_CLIENT_ID                        = [string]($googleEnv['GOOGLE_CLIENT_ID'])
    GOOGLE_CLIENT_SECRET                    = [string]($googleEnv['GOOGLE_CLIENT_SECRET'])
    GOOGLE_REDIRECT_URI                     = "http://localhost:$BackendPort/api/admin/sources/google/callback"
    SOURCE_TOKEN_ENCRYPTION_KEY             = $tokenKey
    SOURCE_TOKEN_ENCRYPTION_KEY_ID          = 'testbed-v1'
    SOURCE_TOKEN_ENCRYPTION_KEY_FILE        = ''
    GOOGLE_OAUTH_FRONTEND_RETURN_URL        = "http://localhost:$FrontendPort/admin/sources"
    GOOGLE_OAUTH_COOKIE_SECURE              = 'false'
    SDV_TESTBED_DIAGNOSTICS_ENABLED         = 'true'
    SDV_TESTBED_DIAGNOSTICS_ALLOWED_FILE_ID = [string]($googleEnv['SDV_TESTBED_DIAGNOSTICS_ALLOWED_FILE_ID'])
}
$indexingOverrides = Get-M17IndexingBackendOverrides -Enabled $EnableIndexing.IsPresent `
    -KafkaBootstrapServers $KafkaBootstrapServers -Topic $IndexingTopic `
    -GroupId $IndexingGroupId -AiServiceUrl $AiServiceUrl
foreach ($key in $indexingOverrides.Keys) {
    $backendOverrides[$key] = $indexingOverrides[$key]
}
$backendEnv = New-IsolatedEnvironment -Overrides $backendOverrides

$backendMode = if ($EnableIndexing) { 'indexing enabled; Assistant disabled' } else { 'publisher/consumer/Assistant disabled' }
Write-Step "Starting backend (testbed profile, hidden, isolated environment; $backendMode)"
$backendProcess = Start-TrackedProcess -Name 'backend' -FilePath 'java' `
    -ArgumentList @('-jar', $bootJar.FullName) `
    -WorkingDirectory $paths.BackendDir -Environment $backendEnv -PidsDir $paths.PidsDir `
    -LogFile (Join-Path $paths.LogsDir 'backend.log')
Write-Ok "Backend launching (PID $($backendProcess.Id)) - see $(Join-Path $paths.LogsDir 'backend.log')"

# ------------------------------------------------------------------
# 6. Start the frontend (host process)
# ------------------------------------------------------------------
Write-Step 'Starting frontend (Vite dev server, loopback-only, hidden)'

$npmCmd = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
if (-not $npmCmd) { $npmCmd = (Get-Command npm -ErrorAction Stop).Source }
$frontendOverrides = @{
    VITE_KEYCLOAK_URL       = "http://localhost:$KeycloakPort"
    VITE_KEYCLOAK_REALM     = $KeycloakRealm
    VITE_KEYCLOAK_CLIENT_ID = 'sdv-frontend'
    VITE_API_PROXY_TARGET   = "http://localhost:$BackendPort"
    # 테스트베드 전용 UI(진단 패널)를 노출하는 유일한 Signal - 일반 개발
    # Frontend(이 값 없이 npm run dev)에서는 기본적으로 숨겨진다. Backend의
    # 실제 강제(Profile+Flag Double Gate)가 최종 권한이며, 이 값은 UI 노출
    # 여부만 결정한다.
    VITE_TESTBED_MODE       = 'true'
}
$frontendEnv = New-IsolatedEnvironment -Overrides $frontendOverrides
$frontendProcess = Start-TrackedProcess -Name 'frontend' -FilePath $npmCmd `
    -ArgumentList @('run', 'dev', '--', '--port', "$FrontendPort", '--strictPort', '--host', '127.0.0.1') `
    -WorkingDirectory $paths.FrontendDir -Environment $frontendEnv -PidsDir $paths.PidsDir `
    -LogFile (Join-Path $paths.LogsDir 'frontend.log')
Write-Ok "Frontend launching (PID $($frontendProcess.Id)) - see $(Join-Path $paths.LogsDir 'frontend.log')"

# ------------------------------------------------------------------
# 7. Bounded readiness wait + loopback-binding assertion
# ------------------------------------------------------------------
Write-Step 'Waiting for backend/frontend to actually respond (bounded)'

$backendReady = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:$BackendPort/actuator/health" -TimeoutSec 3
        if ($health.status -eq 'UP') { $backendReady = $true; break }
    } catch { Start-Sleep -Seconds 2 }
}

$frontendReady = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$FrontendPort/" -TimeoutSec 3
        if ($response.StatusCode -eq 200) { $frontendReady = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}

$backendLoopback = $backendReady -and (Test-LoopbackListener -Port $BackendPort -Name 'backend')
$frontendLoopback = $frontendReady -and (Test-LoopbackListener -Port $FrontendPort -Name 'frontend')

Write-Host ''
if ($backendReady -and $frontendReady -and $backendLoopback -and $frontendLoopback) {
    Write-Host '========================================' -ForegroundColor Green
    Write-Host ' M16A local testbed is READY' -ForegroundColor Green
    Write-Host '========================================' -ForegroundColor Green
} else {
    Write-Host '========================================' -ForegroundColor Yellow
    Write-Host ' M16A local testbed NOT confirmed ready:' -ForegroundColor Yellow
    if (-not $backendReady) { Write-Warn2 "backend did not report UP within the wait window - tail $(Join-Path $paths.LogsDir 'backend.log') for the cause (no secret values are ever written there by this script)" }
    if (-not $frontendReady) { Write-Warn2 "frontend did not respond within the wait window - tail $(Join-Path $paths.LogsDir 'frontend.log')" }
    if ($backendReady -and -not $backendLoopback) { Write-Err2 'backend responded but is NOT loopback-only - refusing to call this ready' }
    if ($frontendReady -and -not $frontendLoopback) { Write-Err2 'frontend responded but is NOT loopback-only - refusing to call this ready' }
    Write-Host '========================================' -ForegroundColor Yellow
}

Write-Host ''
Write-Host "Frontend:        http://localhost:$FrontendPort"
Write-Host "Backend:         http://localhost:$BackendPort"
Write-Host "Keycloak:        http://localhost:$KeycloakPort (realm: $KeycloakRealm)"
Write-Host "Account creds:   $AccountsFile (never share this file's contents)"
Write-Host "Google config:   $GoogleEnvFile (edit for the LIVE GOOGLE stage, then re-run this script)"
Write-Host "Backend log:     $(Join-Path $paths.LogsDir 'backend.log')"
Write-Host "Frontend log:    $(Join-Path $paths.LogsDir 'frontend.log')"
Write-Host "Status:          scripts\testbed\status-testbed.ps1"
Write-Host "Stop with:       scripts\testbed\stop-testbed.ps1"
Write-Host ''

if (-not ($backendReady -and $frontendReady -and $backendLoopback -and $frontendLoopback)) {
    exit 1
}
