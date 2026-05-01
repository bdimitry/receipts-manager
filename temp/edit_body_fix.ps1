$path = 'src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java'
$content = Get-Content -Raw $path
$content = $content.Replace('if (!StringUtils.hasText(text) || isLikelyItemLike() || isSummaryLike() || isServiceLike()) {', 'if (!StringUtils.hasText(text) || isAmountOnly() || isMeasureOnly() || isLikelyItemLike() || isSummaryLike() || isServiceLike()) {')
$content = $content.Replace('            || normalized.contains("pdv")`r`n            || normalized.contains("ןהג");', '            || normalized.contains("pdv");')
Set-Content -Encoding UTF8 -Path $path -Value $content
