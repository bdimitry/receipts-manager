param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$FrontendUrl = "http://localhost:3000",
    [string]$Email = ("demo-" + [guid]::NewGuid().ToString("N") + "@example.com"),
    [string]$Password = "P@ssword123"
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Invoke-JsonPost {
    param(
        [string]$Uri,
        [object]$Body,
        [hashtable]$Headers = @{}
    )

    return Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 10)
}

function New-DemoReceiptImage {
    param([string]$Path)

    Add-Type -AssemblyName System.Drawing

    $bitmap = New-Object System.Drawing.Bitmap 1400, 800
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $font = $null

    try {
        $graphics.Clear([System.Drawing.Color]::White)
        $font = New-Object System.Drawing.Font("Consolas", 36, [System.Drawing.FontStyle]::Bold)
        $brush = [System.Drawing.Brushes]::Black
        $lines = @(
            "FRESH MARKET",
            "TOTAL 42.75",
            "DATE 2026-03-30"
        )

        $y = 100
        foreach ($line in $lines) {
            $graphics.DrawString($line, $font, $brush, 80, $y)
            $y += 100
        }
    }
    finally {
        $graphics.Dispose()
        if ($font) {
            $font.Dispose()
        }
    }

    $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

Write-Step "Register user $Email"
Invoke-JsonPost -Uri "$BaseUrl/api/auth/register" -Body @{
    email = $Email
    password = $Password
} | Out-Null

Write-Step "Login"
$login = Invoke-JsonPost -Uri "$BaseUrl/api/auth/login" -Body @{
    email = $Email
    password = $Password
}

$authHeaders = @{
    Authorization = "Bearer $($login.accessToken)"
}

Write-Step "Create purchase"
$purchase = Invoke-JsonPost -Uri "$BaseUrl/api/purchases" -Headers $authHeaders -Body @{
    title = "Demo Groceries"
    category = "FOOD"
    amount = 42.75
    purchaseDate = "2026-03-30"
    storeName = "Fresh Market"
    comment = "Portfolio demo purchase"
}

$tempReceiptPath = Join-Path $env:TEMP ("demo-receipt-" + [guid]::NewGuid().ToString("N") + ".png")
New-DemoReceiptImage -Path $tempReceiptPath

try {
    Write-Step "Upload receipt"
    $receiptJson = & curl.exe -s -X POST "$BaseUrl/api/receipts/upload" `
        -H "Authorization: Bearer $($login.accessToken)" `
        -F "file=@$tempReceiptPath;type=image/png" `
        -F "purchaseId=$($purchase.id)"

    $receipt = $receiptJson | ConvertFrom-Json

    Write-Step "Wait for OCR completion"
    $ocrDeadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Seconds 1
        $ocr = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/receipts/$($receipt.id)/ocr" -Headers $authHeaders
        Write-Host "Receipt OCR status: $($ocr.ocrStatus)"
    } while ($ocr.ocrStatus -in @("NEW", "PROCESSING") -and (Get-Date) -lt $ocrDeadline)

    if ($ocr.ocrStatus -ne "DONE") {
        throw "OCR did not finish successfully. Final status: $($ocr.ocrStatus). Error: $($ocr.ocrErrorMessage)"
    }

    Write-Step "Create monthly report job"
    $reportJob = Invoke-JsonPost -Uri "$BaseUrl/api/reports/monthly" -Headers $authHeaders -Body @{
        year = 2026
        month = 3
    }

    Write-Host "Report job created with status: $($reportJob.status)"

    Write-Step "Wait for async completion"
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Seconds 1
        $currentJob = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/reports/$($reportJob.id)" -Headers $authHeaders
        Write-Host "Current status: $($currentJob.status)"
    } while ($currentJob.status -in @("NEW", "PROCESSING") -and (Get-Date) -lt $deadline)

    if ($currentJob.status -ne "DONE") {
        throw "Report job did not finish successfully. Final status: $($currentJob.status). Error: $($currentJob.errorMessage)"
    }

    Write-Step "Get download contract"
    $download = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/reports/$($reportJob.id)/download" -Headers $authHeaders

    Write-Step "Demo summary"
    Write-Host "User email:        $Email"
    Write-Host "Purchase id:       $($purchase.id)"
    Write-Host "Receipt id:        $($receipt.id)"
    Write-Host "Receipt OCR:       $($ocr.ocrStatus)"
    Write-Host "Parsed store:      $($ocr.parsedStoreName)"
    Write-Host "Parsed total:      $($ocr.parsedTotalAmount)"
    Write-Host "Report job id:     $($currentJob.id)"
    Write-Host "Report status:     $($currentJob.status)"
    Write-Host "Report s3Key:      $($currentJob.s3Key)"
    Write-Host "Download URL:      $($download.downloadUrl)"
    Write-Host "Frontend UI:       $FrontendUrl"
    Write-Host "MailHog UI:        http://localhost:8025"
    Write-Host "LocalStack S3:     docker exec home-budget-localstack awslocal s3 ls s3://home-budget-files --recursive"
    Write-Host "OCR Queue:         docker exec home-budget-localstack awslocal sqs receive-message --queue-url http://localhost:4566/000000000000/receipt-ocr-queue"
}
finally {
    if (Test-Path $tempReceiptPath) {
        Remove-Item $tempReceiptPath -Force
    }
}
