package com.n0n5ense.taassistant.core.report

import com.n0n5ense.taassistant.core.model.Judge
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.ConditionalFormattingThreshold
import org.apache.poi.ss.usermodel.ExtendedColor
import org.apache.poi.ss.usermodel.PatternFormatting
import org.apache.poi.ss.usermodel.SheetConditionalFormatting
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule
import org.apache.poi.xssf.usermodel.XSSFDataBarFormatting
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.xmlbeans.XmlObject
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTCfRule
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTDataBar
import java.io.OutputStream

/** Excel のセルに入る文字数の上限 (32767) より少し小さく切る */
private const val MAX_CELL_TEXT = 32_000

internal object ExcelReportWriter {

    fun write(out: OutputStream, report: Report) {
        XSSFWorkbook().use { workbook ->
            writeDetailSheet(workbook, report.details)
            writeSummarySheet(workbook, report.summary)
            workbook.write(out)
        }
    }

    private fun writeDetailSheet(workbook: XSSFWorkbook, rows: List<DetailRow>) {
        val sheet = workbook.createSheet("Detail")
        val timeStyle = workbook.createCellStyle().apply {
            dataFormat = workbook.createDataFormat().getFormat("0.000")
        }
        val wrapStyle = workbook.createCellStyle().apply { wrapText = true }

        sheet.createRow(0).let { row ->
            DetailRow.header.forEachIndexed { i, title ->
                row.createCell(i, CellType.STRING).setCellValue(title)
            }
        }
        rows.forEachIndexed { rowIndex, detail ->
            val row = sheet.createRow(rowIndex + 1)
            detail.cells().forEachIndexed { col, value ->
                when (value) {
                    null -> row.createCell(col, CellType.BLANK)
                    is Double -> row.createCell(col, CellType.NUMERIC).apply {
                        setCellValue(value)
                        cellStyle = timeStyle
                    }

                    else -> row.createCell(col, CellType.STRING).apply {
                        setCellValue(value.toString().truncateForCell())
                        if (col in DetailRow.wrapColumns) cellStyle = wrapStyle
                    }
                }
            }
        }

        sheet.createFreezePane(0, 1)
        sheet.setAutoFilter(CellRangeAddress(0, 0, 0, DetailRow.header.lastIndex))
        if (rows.isNotEmpty()) {
            sheet.setStatConditionalFormatting(
                CellRangeAddress(1, rows.size, DetailRow.statColumn, DetailRow.statColumn),
            )
        }
        DetailRow.header.indices.forEach { col ->
            if (col in DetailRow.wrapColumns) sheet.setColumnWidth(
                col,
                60 * 256
            ) else sheet.autoSizeColumn(col)
        }
    }

    private fun writeSummarySheet(workbook: XSSFWorkbook, table: SummaryTable) {
        val sheet = workbook.createSheet("Summary")
        val header = table.header
        sheet.createRow(0).let { row ->
            header.forEachIndexed { i, title ->
                row.createCell(i, CellType.STRING).setCellValue(title)
            }
        }
        table.rows.forEachIndexed { index, summary ->
            sheet.createRow(index + 1).apply {
                createCell(0, CellType.STRING).setCellValue(summary.studentId.value)
                createCell(1, CellType.STRING).setCellValue(summary.studentName)
                summary.acceptRatios.forEachIndexed { i, ratio ->
                    createCell(2 + i, CellType.NUMERIC).setCellValue(ratio.toDouble())
                }
                summary.stats.forEachIndexed { i, judge ->
                    createCell(2 + summary.acceptRatios.size + i, CellType.STRING).setCellValue(
                        judge.label
                    )
                }
            }
        }

        sheet.createFreezePane(2, 1)
        sheet.setAutoFilter(CellRangeAddress(0, 0, 0, header.lastIndex))
        val lastRow = table.rows.size
        val firstStatColumn = 2 + table.taskNames.size
        if (lastRow > 0 && table.columns.isNotEmpty()) {
            sheet.setStatConditionalFormatting(
                CellRangeAddress(
                    1,
                    lastRow,
                    firstStatColumn,
                    header.lastIndex
                )
            )
        }
        if (lastRow > 0 && table.taskNames.isNotEmpty()) {
            val color = workbook.creationHelper.createExtendedColor().apply {
                rgb = byteArrayOf(99, 142.toByte(), 198.toByte())
            }
            applyDataBars(
                sheet.sheetConditionalFormatting,
                CellRangeAddress(1, lastRow, 2, firstStatColumn - 1),
                color
            )
        }
        header.indices.forEach { sheet.autoSizeColumn(it) }
    }
}

private fun String.truncateForCell(): String =
    if (length <= MAX_CELL_TEXT) this else take(MAX_CELL_TEXT) + "\n…(truncated)"

private fun rgb(r: Int, g: Int, b: Int) =
    XSSFColor(byteArrayOf(r.toByte(), g.toByte(), b.toByte()), DefaultIndexedColorMap())

/** 判定ごとの (背景色, 文字色) */
private val judgeColors: Map<Judge, Pair<XSSFColor, XSSFColor>> by lazy {
    mapOf(
        Judge.AC to (rgb(198, 239, 206) to rgb(0, 97, 0)),
        Judge.WA to (rgb(255, 235, 156) to rgb(156, 87, 0)),
        Judge.TLE to (rgb(255, 235, 156) to rgb(156, 87, 0)),
        Judge.RE to (rgb(255, 199, 206) to rgb(156, 0, 6)),
        Judge.CE to (rgb(255, 199, 206) to rgb(156, 0, 6)),
        Judge.IE to (rgb(255, 255, 255) to rgb(0, 0, 0)),
        Judge.NF to (rgb(191, 191, 191) to rgb(0, 0, 0)),
        Judge.CF to (rgb(217, 210, 233) to rgb(63, 49, 81)),
    )
}

private fun XSSFSheet.setStatConditionalFormatting(range: CellRangeAddress) {
    val formatting = sheetConditionalFormatting
    judgeColors.forEach { (judge, colors) ->
        val (background, font) = colors
        val rule = formatting.createConditionalFormattingRule(
            """EXACT("${judge.label}",INDIRECT(ADDRESS(ROW(),COLUMN())))""",
        ).apply {
            createPatternFormatting().apply {
                setFillBackgroundColor(background)
                fillPattern = PatternFormatting.SOLID_FOREGROUND
            }
            createFontFormatting().fontColor = font
        }
        formatting.addConditionalFormatting(arrayOf(range), rule)
    }
}

/** 0〜100 のグラデーションなしデータバー */
private fun applyDataBars(
    formatting: SheetConditionalFormatting,
    region: CellRangeAddress,
    color: ExtendedColor
) {
    val rule = formatting.createConditionalFormattingRule(color)
    rule.dataBarFormatting.apply {
        minThreshold.rangeType = ConditionalFormattingThreshold.RangeType.NUMBER
        minThreshold.value = 0.0
        maxThreshold.rangeType = ConditionalFormattingThreshold.RangeType.NUMBER
        maxThreshold.value = 100.0
        isIconOnly = false
    }
    // POI の API では設定できない項目をリフレクションで設定する (失敗しても通常のデータバーにはなる)
    runCatching {
        val dataBarFormatting = rule.dataBarFormatting
        if (dataBarFormatting is XSSFDataBarFormatting) {
            val field = XSSFDataBarFormatting::class.java.getDeclaredField("_databar")
                .apply { isAccessible = true }
            (field.get(dataBarFormatting) as CTDataBar).apply {
                minLength = 0
                maxLength = 100
            }
        }
        if (rule is XSSFConditionalFormattingRule) {
            val cfRuleField = XSSFConditionalFormattingRule::class.java.getDeclaredField("_cfRule")
                .apply { isAccessible = true }
            val ctRule = cfRuleField.get(rule) as CTCfRule
            ctRule.addNewExtLst().addNewExt().apply {
                set(
                    XmlObject.Factory.parse(
                        """<x14:id xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main">{00000000-000E-0000-0000-000001000000}</x14:id>""",
                    ),
                )
                uri = "{B025F937-C7B1-47D3-B67F-A62EFF666E3E}"
            }
            val sheetField = XSSFConditionalFormattingRule::class.java.getDeclaredField("_sh")
                .apply { isAccessible = true }
            val sheet = sheetField.get(rule) as XSSFSheet
            sheet.ctWorksheet.addNewExtLst().addNewExt().apply {
                set(
                    XmlObject.Factory.parse(
                        """<x14:conditionalFormattings xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main">""" +
                                """<x14:conditionalFormatting xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main">""" +
                                """<x14:cfRule type="dataBar" id="{00000000-000E-0000-0000-000001000000}">""" +
                                """<x14:dataBar minLength="0" maxLength="100" gradient="false">""" +
                                """<x14:cfvo type="num"><xm:f>0</xm:f></x14:cfvo>""" +
                                """<x14:cfvo type="num"><xm:f>100</xm:f></x14:cfvo>""" +
                                """</x14:dataBar></x14:cfRule>""" +
                                """<xm:sqref>${region.formatAsString()}</xm:sqref>""" +
                                """</x14:conditionalFormatting></x14:conditionalFormattings>""",
                    ),
                )
                uri = "{78C0D931-6437-407d-A8EE-F0AAD7539E65}"
            }
        }
    }
    formatting.addConditionalFormatting(arrayOf(region), rule)
}
