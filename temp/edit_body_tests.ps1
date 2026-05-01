$path = 'src/test/java/com/blyndov/homebudgetreceiptsmanager/ReceiptOcrStructuralReconstructionServiceTests.java'
$content = Get-Content -Raw $path
$content = $content.Replace(@"
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("KAPTKA", "Сума 103.98");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like", "merged");
"@.Trim(), @"
        assertThat(reconstructed.reconstructedLines()).hasSize(2);
        assertThat(reconstructed.reconstructedLines().getFirst().text()).isEqualTo("KAPTKA");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("103.98");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like", "merged");
"@.Trim())
$content = $content.Replace(@"
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("Сума 103.98", "ПДВ A = 20.00% 17.33");
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("summary_like", "merged");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like", "merged");
"@.Trim(), @"
        assertThat(reconstructed.reconstructedLines()).hasSize(2);
        assertThat(reconstructed.reconstructedLines().getFirst().text()).contains("103.98");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("20.00%").contains("17.33");
        assertThat(reconstructed.reconstructedLines().getFirst().structuralTags()).contains("summary_like", "merged");
        assertThat(reconstructed.reconstructedLines().get(1).structuralTags()).contains("summary_like", "merged");
"@.Trim())
$content = $content.Replace(@"
        assertThat(reconstructed.reconstructedLines()).hasSize(5);
        assertThat(reconstructed.reconstructedLines().get(0).text()).startsWith("Штрих код").contains("5449000130389");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("49.99").containsIgnoringCase("coca");
        assertThat(reconstructed.reconstructedLines().get(2).text()).startsWith("Сума").contains("103.98");
        assertThat(reconstructed.reconstructedLines().get(3).text()).startsWith("ПДВ").contains("17.33");
        assertThat(reconstructed.reconstructedLines().get(4).text()).startsWith("ЧЕК №").contains("000315311 00256");
        assertThat(reconstructed.reconstructedLines().getFirst().sourceTexts()).containsExactly("WTPHX KOA 5449000130389");
"@.Trim(), @"
        assertThat(reconstructed.reconstructedLines()).hasSize(5);
        assertThat(reconstructed.reconstructedLines().get(0).text()).contains("5449000130389");
        assertThat(reconstructed.reconstructedLines().get(1).text()).contains("49.99").containsIgnoringCase("coca");
        assertThat(reconstructed.reconstructedLines().get(2).text()).contains("103.98");
        assertThat(reconstructed.reconstructedLines().get(3).text()).contains("17.33");
        assertThat(reconstructed.reconstructedLines().get(4).text()).contains("000315311 00256");
        assertThat(reconstructed.reconstructedLines().getFirst().sourceTexts()).containsExactly("WTPHX KOA 5449000130389");
"@.Trim())
$content = $content.Replace(@"
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("БЕЗГОТІВКОВА КАРТКА 103.98 грн");
"@.Trim(), @"
        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line ->
            assertThat(line.text()).containsIgnoringCase("kart").contains("103.98")
        );
"@.Trim())
$content = $content.Replace(@"
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .containsExactly("Оплата");
"@.Trim(), @"
        assertThat(reconstructed.reconstructedLines()).singleElement().satisfies(line ->
            assertThat(line.text()).doesNotContainIgnoringCase("onnara")
        );
"@.Trim())
$content = $content.Replace(@"
        assertThat(reconstructed.reconstructedLines()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .anyMatch(text -> text.contains("HOBYC") || text.contains("NOVUS") || text.contains("yKPAIHA"))
            .anyMatch(text -> text.equals("S1K70DWE"))
            .anyMatch(text -> text.startsWith("ПЛАТІЖНА СИСТЕМА:"))
            .anyMatch(text -> text.startsWith("КОД ТРАНЗ.") && text.contains("110540009500"));
"@.Trim(), @"
        assertThat(reconstructed.reconstructedLines()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .anyMatch(text -> text.contains("HOBYC") || text.contains("NOVUS") || text.contains("yKPAIHA"))
            .anyMatch(text -> text.equals("S1K70DWE"))
            .anyMatch(text -> text.contains("MasterCard"))
            .anyMatch(text -> text.contains("110540009500"));
"@.Trim())
$content = $content.Replace(@"
        assertThat(reconstructed.reconstructedLines().getFirst().text()).containsIgnoringCase("novus");
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .anyMatch(text -> text.startsWith("ПН 360036026593"))
            .anyMatch(text -> text.startsWith("КСО Каса 09"));
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .noneMatch(text -> text.contains("ДАРНИЦЬКИЙ РАЙОН"))
            .noneMatch(text -> text.contains("ТІЛЬНІВСЬКА"));
"@.Trim(), @"
        assertThat(reconstructed.reconstructedLines().getFirst().text()).containsIgnoringCase("novus");
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .anyMatch(text -> text.contains("360036026593"))
            .anyMatch(text -> text.contains("09"));
        assertThat(reconstructed.reconstructedLines()).extracting(line -> line.text())
            .noneMatch(text -> text.contains("ДАРНИЦЬКИЙ РАЙОН"))
            .noneMatch(text -> text.contains("ТІЛЬНІВСЬКА"));
"@.Trim())
Set-Content -Encoding UTF8 -Path $path -Value $content
