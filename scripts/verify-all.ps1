param()

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendPath = Join-Path $repoRoot "frontend"
$playwrightImage = "mcr.microsoft.com/playwright:v1.58.2-jammy"

function Test-CommandExists {
    param([string]$Name)

    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
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
        return
    }

    if ($env:MAVEN_CMD) {
        & $env:MAVEN_CMD test
        return
    }

    if (Test-CommandExists "mvn") {
        mvn test
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
            }
            Invoke-Step "Run frontend tests" {
                npm test
            }
            Invoke-Step "Install Playwright Chromium" {
                npx playwright install chromium
            }
            Invoke-Step "Run frontend smoke tests" {
                npm run test:smoke
            }
            Invoke-Step "Build frontend" {
                npm run build
            }
        } else {
            Invoke-Step "Run frontend verification in Dockerized Playwright environment" {
                docker run --rm --ipc=host `
                    -v "${frontendPath}:/app" `
                    -w /app `
                    $playwrightImage `
                    /bin/bash -lc "npm ci && npm test && npx playwright install chromium && npm run test:smoke && npm run build"
            }
        }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}
