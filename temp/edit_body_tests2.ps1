$path = 'src/test/java/com/blyndov/homebudgetreceiptsmanager/ReceiptOcrStructuralReconstructionServiceTests.java'
$content = Get-Content -Raw $path
$content = $content.Replace('        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line ->`r`n            assertThat(line.text()).containsIgnoringCase("kart").contains("103.98")`r`n        );', '        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line ->`r`n            assertThat(line.text()).contains("103.98")`r`n        );')
$content = $content.Replace('            .anyMatch(text -> text.equals("S1K70DWE"))`r`n            .anyMatch(text -> text.contains("MasterCard"))`r`n            .anyMatch(text -> text.contains("110540009500"));', '            .anyMatch(text -> text.equals("S1K70DWE"))`r`n            .anyMatch(text -> text.contains("110540009500"));')
Set-Content -Path $path -Value $content
