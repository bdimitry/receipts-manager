$path = 'src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java'
$content = Get-Content -Raw $path
$content = [regex]::Replace($content, '\[\\\.,\]\\d\{2\}', '[\\.,:]\\d{2}', 2)
$replacement = @"
private static final Pattern MEASURE_ONLY_PATTERN = Pattern.compile(
        "(?iu)^(?:[a-z\\p{IsCyrillic}]{0,3}\\s*)?\\d{1,4}(?:[\\.,]\\d{1,3})?\\s*[a-z\\p{IsCyrillic}]{1,4}$"
    );
    private static final Pattern LONG_DIGITS_PATTERN = Pattern.compile("\\d{8,}");
"@
$content = $content -replace 'private static final Pattern LONG_DIGITS_PATTERN = Pattern\.compile\("\\\\d\{8,\}"\);', $replacement.Trim()
Set-Content -Path $path -Value $content
