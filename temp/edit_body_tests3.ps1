$path = 'src/test/java/com/blyndov/homebudgetreceiptsmanager/ReceiptOcrStructuralReconstructionServiceTests.java'
$content = Get-Content -Raw $path
$regex = New-Object System.Text.RegularExpressions.Regex('(?s)        assertThat\(reconstructed\.reconstructedLines\(\)\)\.hasSizeGreaterThanOrEqualTo\(3\);\s+        assertThat\(reconstructed\.reconstructedLines\(\)\)\.extracting\(line -> line\.text\(\)\)\s+            \.anyMatch\(text -> text\.contains\("HOBYC"\) \|\| text\.contains\("NOVUS"\) \|\| text\.contains\("yKPAIHA"\)\)\s+            \.anyMatch\(text -> text\.equals\("S1K70DWE"\)\)\s+            \.anyMatch\(text -> .*?\);', [System.Text.RegularExpressions.RegexOptions]::Multiline)
$replacement = @"
        assertThat(reconstructed.reconstructedLines()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .anyMatch(text -> text.contains("HOBYC") || text.contains("NOVUS") || text.contains("yKPAIHA"))
            .anyMatch(text -> text.equals("S1K70DWE"))
            .anyMatch(text -> text.contains("110540009500"));
"@
$content = $regex.Replace($content, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $replacement.TrimEnd() }, 1)
Set-Content -Path $path -Value $content
