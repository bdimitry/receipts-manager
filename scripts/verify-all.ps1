param()

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendPath = Join-Path $repoRoot "frontend"
$playwrightImage = "mcr.microsoft.com/playwright:v1.58.2-jammy"

function Test-CommandExists {
    param([string]$Name)

    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Assert-LastExitCode {
    param([string]$CommandName)

    if ($LASTEXITCODE -ne 0) {
        throw "$CommandName failed with exit code $LASTEXITCODE."
    }
}

function Test-RealMavenWrapper {
    param([string]$RepoRoot)

    $wrapperProperties = Join-Path $RepoRoot ".mvn\\wrapper\\maven-wrapper.properties"
    $wrapperScript = Join-Path $RepoRoot "mvnw.cmd"

    return (Test-Path $wrapperScript) -and (Test-Path $wrapperProperties)
}

function Invoke-MavenTest {
    param([string]$RepoRoot)

    if (Test-RealMavenWrapper $RepoRoot) {
        & (Join-Path $RepoRoot "mvnw.cmd") test
        Assert-LastExitCode "mvnw.cmd test"
        return
    }

    if ($env:MAVEN_CMD) {
        & $env:MAVEN_CMD test
        Assert-LastExitCode $env:MAVEN_CMD
        return
    }

    if (Test-CommandExists "mvn") {
        mvn test
        Assert-LastExitCode "mvn test"
        return
    }

    throw "No usable Maven command found. Install Maven, provide MAVEN_CMD, or add a real Maven wrapper."
}

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    & $Action
}

Push-Location $repoRoot
try {
    Invoke-Step "Run backend tests" {
        Invoke-MavenTest $repoRoot
    }

    Push-Location $frontendPath
    try {
        if (Test-CommandExists "npm") {
            Invoke-Step "Install frontend dependencies" {
                npm ci
                Assert-LastExitCode "npm ci"
            }
            Invoke-Step "Run frontend tests" {
                npm test
                Assert-LastExitCode "npm test"
            }
            Invoke-Step "Install Playwright Chromium" {
                npx playwright install chromium
                Assert-LastExitCode "npx playwright install chromium"
            }
            Invoke-Step "Run frontend smoke tests" {
                npm run test:smoke
                Assert-LastExitCode "npm run test:smoke"
            }
            Invoke-Step "Build frontend" {
                npm run build
                Assert-LastExitCode "npm run build"
            }
        } else {
            Invoke-Step "Run frontend verification in Dockerized Playwright environment" {
                docker run --rm --ipc=host `
                    -v "${frontendPath}:/app" `
                    -w /app `
                    $playwrightImage `
                    /bin/bash -lc "npm ci && npm test && npx playwright install chromium && npm run test:smoke && npm run build"
                Assert-LastExitCode "dockerized frontend verification"
            }
        }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
