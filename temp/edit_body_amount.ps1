$path = 'src/main/java/com/blyndov/homebudgetreceiptsmanager/service/ReceiptOcrStructuralReconstructionService.java'
$content = Get-Content -Raw $path
$content = $content.Replace('[\\.,]\\d{2}', '[\\.,:]\\d{2}')
Set-Content -Path $path -Value $content
