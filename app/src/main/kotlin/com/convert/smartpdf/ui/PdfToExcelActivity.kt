package com.convert.smartpdf.ui

import android.app.ProgressDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.convert.smartpdf.NotificationUtils
import com.convert.smartpdf.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import com.itextpdf.text.pdf.parser.SimpleTextExtractionStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PdfToExcelActivity : AppCompatActivity() {

    // ───────── State ─────────
    private var selectedPdfUri: Uri? = null
    private var totalPageCount = 0
    private val pageThumbs = mutableListOf<Bitmap>()
    private lateinit var thumbAdapter: PageThumbAdapter

    // ───────── Extraction Mode ─────────
    private enum class ExtractionMode { AUTO, TABLE, RAW }

    // ───────── Cell data model ─────────
    /** একটি extracted cell: row, col index + raw text value */
    private data class CellData(val row: Int, val col: Int, val value: String)

    // ───────── File picker ─────────
    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { loadPdfThumbnails(it) }
        }

    // ══════════════════════════════════════════════
    // onCreate
    // ══════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_to_excel)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSelectPdf).setOnClickListener {
            pickPdfLauncher.launch("application/pdf")
        }
        findViewById<View>(R.id.btnConvert).setOnClickListener {
            selectedPdfUri?.let { uri -> startExtraction(uri) }
        }

        thumbAdapter = PageThumbAdapter(pageThumbs)
        val rv = findViewById<RecyclerView>(R.id.rvPages)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = thumbAdapter
    }

    // ══════════════════════════════════════════════
    // Step 1 — Load page thumbnails for preview
    // ══════════════════════════════════════════════
    private fun loadPdfThumbnails(uri: Uri) {
        selectedPdfUri = uri
        val dlg = ProgressDialog(this).apply {
            setMessage("Loading pages..."); setCancelable(false); show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pageThumbs.clear()
                val fd = contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                val renderer = PdfRenderer(fd)
                totalPageCount = renderer.pageCount
                repeat(renderer.pageCount) { i ->
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(400, 560, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageThumbs.add(bmp)
                    page.close()
                }
                renderer.close()

                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    findViewById<View>(R.id.btnSelectPdf).visibility = View.GONE
                    findViewById<RecyclerView>(R.id.rvPages).visibility = View.VISIBLE
                    findViewById<View>(R.id.bottomOptions).visibility = View.VISIBLE
                    val tvSub = findViewById<TextView>(R.id.tvSubtitle)
                    tvSub.text = "✨ $totalPageCount page(s) loaded — choose mode and convert"
                    tvSub.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.tvSelectedFile).text =
                        "$totalPageCount page(s) • tap Convert to start"
                    thumbAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dlg.dismiss()
                    toast("Failed to load PDF: ${e.message}")
                }
            }
        }
    }

    // ══════════════════════════════════════════════
    // Step 2 — Choose engine and extract
    // ══════════════════════════════════════════════
    private fun startExtraction(uri: Uri) {
        val mode = when (findViewById<RadioGroup>(R.id.rgMode).checkedRadioButtonId) {
            R.id.rbTable -> ExtractionMode.TABLE
            R.id.rbRaw   -> ExtractionMode.RAW
            else          -> ExtractionMode.AUTO
        }
        val boldHeader = findViewById<View>(R.id.cbHeaderRow).let {
            (it as android.widget.CheckBox).isChecked
        }
        val autoType  = (findViewById<View>(R.id.cbAutoType) as android.widget.CheckBox).isChecked
        val autoWidth = (findViewById<View>(R.id.cbAutoWidth) as android.widget.CheckBox).isChecked

        showLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ── Engine selection ──
                // 1. iText দিয়ে text extract চেষ্টা (text-based PDF)
                // 2. যদি extract হওয়া text blank/খুব কম হয় → ML Kit OCR fallback
                val allPageCells = mutableListOf<List<CellData>>()

                val iTextResult = tryITextExtraction(uri, mode)

                if (iTextResult != null && iTextResult.isNotEmpty()) {
                    // ── Text-based PDF ──
                    updateProgress("Parsing text-based PDF...", "", 50)
                    allPageCells.addAll(iTextResult)
                } else {
                    // ── Image/scanned PDF → OCR ──
                    allPageCells.addAll(runOcrExtraction(uri, mode))
                }

                updateProgress("Building Excel workbook...", "", 90)

                val workbook = buildExcelWorkbook(allPageCells, boldHeader, autoType, autoWidth)
                saveToDownloads(workbook)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    toast("Conversion failed: ${e.message}")
                }
            }
        }
    }

    // ══════════════════════════════════════════════
    // Engine A — iText PDF text extraction
    // Returns null if PDF is image-only
    // ══════════════════════════════════════════════
    private fun tryITextExtraction(
        uri: Uri,
        mode: ExtractionMode
    ): List<List<CellData>>? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val reader = PdfReader(inputStream)
            val result = mutableListOf<List<CellData>>()

            for (pageNum in 1..reader.numberOfPages) {
                val text = PdfTextExtractor.getTextFromPage(
                    reader, pageNum,
                    LocationTextExtractionStrategy() // preserves spatial layout
                ).trim()

                if (text.isBlank()) continue // blank = image page

                val cells = when (mode) {
                    ExtractionMode.RAW   -> parseRawText(text, pageNum)
                    ExtractionMode.TABLE,
                    ExtractionMode.AUTO  -> parseTextAsTable(text)
                }
                result.add(cells)
            }
            reader.close()

            // যদি কোনো page থেকেই text না আসে → null (OCR দরকার)
            if (result.isEmpty() || result.all { it.isEmpty() }) null else result
        } catch (e: Exception) {
            null
        }
    }

    /** LocationTextExtractionStrategy থেকে পাওয়া text → table rows */
    private fun parseTextAsTable(rawText: String): List<CellData> {
        val cells = mutableListOf<CellData>()
        val lines = rawText.lines().filter { it.isNotBlank() }

        lines.forEachIndexed { rowIdx, line ->
            // Multiple whitespace = column separator হিসেবে ব্যবহার করা
            val columns = line.trim().split(Regex("\\s{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            columns.forEachIndexed { colIdx, value ->
                cells.add(CellData(rowIdx, colIdx, value))
            }
        }
        return cells
    }

    /** Raw mode: প্রতিটি line → একটি cell (col 0) */
    private fun parseRawText(rawText: String, pageNum: Int): List<CellData> {
        val cells = mutableListOf<CellData>()
        // Page header
        cells.add(CellData(0, 0, "── Page $pageNum ──"))
        rawText.lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            cells.add(CellData(i + 1, 0, line.trim()))
        }
        return cells
    }

    // ══════════════════════════════════════════════
    // Engine B — ML Kit OCR (scanned / image PDFs)
    // ══════════════════════════════════════════════
    private suspend fun runOcrExtraction(
        uri: Uri,
        mode: ExtractionMode
    ): List<List<CellData>> {
        val allPageCells = mutableListOf<List<CellData>>()
        val fd = contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val renderer = PdfRenderer(fd)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        for (i in 0 until renderer.pageCount) {
            updateProgress(
                "OCR scanning page ${i + 1} of ${renderer.pageCount}...",
                "Image-based PDF detected — using AI OCR",
                ((i + 1).toFloat() / renderer.pageCount * 80).toInt()
            )

            val page = renderer.openPage(i)
            // High-res render for better OCR accuracy
            val scale = 2
            val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
            bmp.recycle()

            val cells = when (mode) {
                ExtractionMode.RAW   -> ocrToRaw(result, i + 1)
                ExtractionMode.TABLE,
                ExtractionMode.AUTO  -> ocrToTable(result)
            }
            allPageCells.add(cells)
        }
        renderer.close()
        return allPageCells
    }

    /**
     * OCR result → table cells using spatial clustering
     *
     * Algorithm:
     * 1. সব Text.Line collect করো
     * 2. Y-position দিয়ে Row group করো (overlap-based)
     * 3. X-position দিয়ে Column cluster করো (k-means style anchor)
     * 4. প্রতিটি line-কে সবচেয়ে কাছের (row, col) তে assign করো
     */
    private fun ocrToTable(result: com.google.mlkit.vision.text.Text): List<CellData> {
        val allLines = mutableListOf<Text.Line>()
        result.textBlocks.forEach { block -> allLines.addAll(block.lines) }
        if (allLines.isEmpty()) return emptyList()

        // ── Step 1: Y-axis Row grouping ──
        allLines.sortBy { it.boundingBox?.centerY() ?: 0 }

        val rowGroups = mutableListOf<MutableList<Text.Line>>()
        for (line in allLines) {
            val cy = line.boundingBox?.centerY() ?: 0
            val height = (line.boundingBox?.height() ?: 20)
            val threshold = (height * 0.6f).toInt().coerceAtLeast(8)

            val matched = rowGroups.find { group ->
                val groupCy = group.map { it.boundingBox?.centerY() ?: 0 }.average().toInt()
                abs(cy - groupCy) <= threshold
            }
            if (matched != null) matched.add(line)
            else rowGroups.add(mutableListOf(line))
        }
        rowGroups.sortBy { group -> group.map { it.boundingBox?.centerY() ?: 0 }.average() }

        // ── Step 2: X-axis Column anchor detection ──
        // সব line এর left position collect করো, তারপর cluster করো
        val allLeftPositions = allLines.mapNotNull { it.boundingBox?.left }
        val columnAnchors = clusterPositions(allLeftPositions, gapThreshold = 40)

        // ── Step 3: Assign each line to (row, col) ──
        val cells = mutableListOf<CellData>()
        rowGroups.forEachIndexed { rowIdx, group ->
            // row এর মধ্যে lines গুলো left → right সাজাও
            group.sortBy { it.boundingBox?.left ?: 0 }
            group.forEach { line ->
                val leftPos = line.boundingBox?.left ?: 0
                val colIdx = columnAnchors.indexOfClosest(leftPos, tolerance = 60)
                    .coerceAtLeast(0)
                cells.add(CellData(rowIdx, colIdx, line.text))
            }
        }
        return cells
    }

    /** Raw OCR mode: সব text → single column */
    private fun ocrToRaw(result: com.google.mlkit.vision.text.Text, pageNum: Int): List<CellData> {
        val cells = mutableListOf<CellData>()
        cells.add(CellData(0, 0, "── Page $pageNum ──"))
        var row = 1
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                cells.add(CellData(row++, 0, line.text))
            }
        }
        return cells
    }

    /**
     * Position list থেকে column anchors বের করো।
     * Simple greedy clustering: যদি পরের position আগেরটার চেয়ে
     * gapThreshold বেশি দূরে থাকে → নতুন cluster।
     */
    private fun clusterPositions(positions: List<Int>, gapThreshold: Int): List<Int> {
        if (positions.isEmpty()) return emptyList()
        val sorted = positions.sorted()
        val anchors = mutableListOf(sorted.first())
        for (pos in sorted.drop(1)) {
            if (pos - anchors.last() > gapThreshold) anchors.add(pos)
            else anchors[anchors.lastIndex] = ((anchors.last() + pos) / 2) // running average
        }
        return anchors
    }

    /** List<Int> এ সবচেয়ে কাছের index খুঁজে বের করো */
    private fun List<Int>.indexOfClosest(value: Int, tolerance: Int): Int {
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        forEachIndexed { idx, anchor ->
            val d = abs(anchor - value)
            if (d < bestDist) { bestDist = d; bestIdx = idx }
        }
        return if (bestDist <= tolerance) bestIdx else size // নতুন column
    }

    // ══════════════════════════════════════════════
    // Step 3 — Build styled Excel workbook
    // ══════════════════════════════════════════════
    private fun buildExcelWorkbook(
        allPageCells: List<List<CellData>>,
        boldHeader: Boolean,
        autoType: Boolean,
        autoWidth: Boolean
    ): XSSFWorkbook {
        val wb = XSSFWorkbook()

        // ── Styles ──
        val headerStyle  = makeHeaderStyle(wb)
        val normalStyle  = makeNormalStyle(wb)
        val numberStyle  = makeNumberStyle(wb)
        val dateStyle    = makeDateStyle(wb)
        val pageTitleStyle = makePageTitleStyle(wb)

        allPageCells.forEachIndexed { pageIdx, cells ->
            if (cells.isEmpty()) return@forEachIndexed

            val sheetName = if (allPageCells.size == 1) "Sheet1"
                            else "Page_${pageIdx + 1}"
            val sheet = wb.createSheet(sheetName)

            // Track column max widths for auto-sizing
            val colWidths = mutableMapOf<Int, Int>()

            // ── Write cells ──
            var globalRowOffset = 0

            // Page title row (multi-page PDF এর জন্য)
            if (allPageCells.size > 1) {
                val titleRow = sheet.createRow(globalRowOffset++)
                val titleCell = titleRow.createCell(0)
                titleCell.setCellValue("Page ${pageIdx + 1}")
                titleCell.cellStyle = pageTitleStyle
            }

            // Group cells by row index
            val rowMap = cells.groupBy { it.row }.toSortedMap()
            val maxCol = cells.maxOfOrNull { it.col } ?: 0

            rowMap.forEach { (_, rowCells) ->
                val excelRow = sheet.createRow(globalRowOffset++)
                val isFirstRow = (excelRow.rowNum == (if (allPageCells.size > 1) 1 else 0))

                rowCells.sortedBy { it.col }.forEach { cellData ->
                    val cell = excelRow.createCell(cellData.col)

                    when {
                        // Header row
                        isFirstRow && boldHeader -> {
                            cell.setCellValue(cellData.value)
                            cell.cellStyle = headerStyle
                        }
                        // Auto type detection
                        autoType -> {
                            val typed = trySetTypedValue(cell, cellData.value, wb, numberStyle, dateStyle)
                            if (!typed) {
                                cell.setCellValue(cellData.value)
                                cell.cellStyle = normalStyle
                            }
                        }
                        else -> {
                            cell.setCellValue(cellData.value)
                            cell.cellStyle = normalStyle
                        }
                    }

                    // Track column width
                    val len = cellData.value.length
                    colWidths[cellData.col] = maxOf(colWidths[cellData.col] ?: 0, len)
                }
            }

            // ── Auto column width ──
            if (autoWidth) {
                for (col in 0..maxCol) {
                    val charCount = colWidths[col] ?: 10
                    // POI width unit = 1/256th of a character width
                    sheet.setColumnWidth(col, ((charCount + 4) * 256).coerceIn(2048, 20480))
                }
            }

            // ── Freeze top row (header) ──
            if (boldHeader) sheet.createFreezePane(0, if (allPageCells.size > 1) 2 else 1)

            // ── Auto filter on header ──
            if (boldHeader && cells.isNotEmpty()) {
                val headerRowIdx = if (allPageCells.size > 1) 1 else 0
                sheet.setAutoFilter(
                    CellRangeAddress(headerRowIdx, headerRowIdx, 0, maxCol)
                )
            }
        }

        return wb
    }

    // ───────── Type detection ─────────
    private val datePatterns = listOf(
        Regex("""^\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4}$"""),
        Regex("""^\d{4}[/\-\.]\d{1,2}[/\-\.]\d{1,2}$"""),
        Regex("""^\d{1,2}\s+\w+\s+\d{4}$""")
    )
    private val numberPattern = Regex("""^-?[\d,]+(\.\d+)?$""")
    private val percentPattern = Regex("""^-?[\d,]+(\.\d+)?%$""")

    /**
     * Value-এর type detect করে cell-এ সঠিক ধরনে সেট করো।
     * @return true যদি typed value সেট হয়, false হলে caller plain text set করবে
     */
    private fun trySetTypedValue(
        cell: org.apache.poi.ss.usermodel.Cell,
        value: String,
        wb: XSSFWorkbook,
        numberStyle: XSSFCellStyle,
        dateStyle: XSSFCellStyle
    ): Boolean {
        val v = value.trim()

        // ── Date ──
        if (datePatterns.any { it.matches(v) }) {
            cell.setCellValue(v)
            cell.cellStyle = dateStyle
            return true
        }

        // ── Percentage ──
        if (percentPattern.matches(v)) {
            val num = v.replace(",", "").replace("%", "").toDoubleOrNull()
            if (num != null) {
                cell.setCellValue(num / 100.0)
                val pctStyle = wb.createCellStyle().also { s ->
                    s.cloneStyleFrom(numberStyle)
                    s.dataFormat = wb.creationHelper.createDataFormat().getFormat("0.00%")
                }
                cell.cellStyle = pctStyle
                return true
            }
        }

        // ── Number ──
        if (numberPattern.matches(v)) {
            val num = v.replace(",", "").toDoubleOrNull()
            if (num != null) {
                cell.setCellValue(num)
                cell.cellStyle = numberStyle
                return true
            }
        }

        return false
    }

    // ───────── Style factories ─────────
    private fun makeHeaderStyle(wb: XSSFWorkbook): XSSFCellStyle =
        wb.createCellStyle().apply {
            val font: XSSFFont = wb.createFont() as XSSFFont
            font.bold = true
            font.color = IndexedColors.WHITE.index
            font.fontHeightInPoints = 11
            setFont(font)
            fillPattern = FillPatternType.SOLID_FOREGROUND
            // Peach-ish header color (#4A3B39 dark brown)
            setFillForegroundColor(XSSFColor(byteArrayOf(0x4A.toByte(), 0x3B.toByte(), 0x39.toByte()), null))
            setBorderBottom(BorderStyle.THIN)
            setBorderTop(BorderStyle.THIN)
            setBorderLeft(BorderStyle.THIN)
            setBorderRight(BorderStyle.THIN)
            wrapText = false
        }

    private fun makeNormalStyle(wb: XSSFWorkbook): XSSFCellStyle =
        wb.createCellStyle().apply {
            val font = wb.createFont()
            font.fontHeightInPoints = 10
            setFont(font)
            setBorderBottom(BorderStyle.HAIR)
            setBorderRight(BorderStyle.HAIR)
            wrapText = false
        }

    private fun makeNumberStyle(wb: XSSFWorkbook): XSSFCellStyle =
        wb.createCellStyle().apply {
            cloneStyleFrom(makeNormalStyle(wb))
            alignment = HorizontalAlignment.RIGHT
            dataFormat = wb.creationHelper.createDataFormat().getFormat("#,##0.##")
        }

    private fun makeDateStyle(wb: XSSFWorkbook): XSSFCellStyle =
        wb.createCellStyle().apply {
            cloneStyleFrom(makeNormalStyle(wb))
            alignment = HorizontalAlignment.CENTER
        }

    private fun makePageTitleStyle(wb: XSSFWorkbook): XSSFCellStyle =
        wb.createCellStyle().apply {
            val font: XSSFFont = wb.createFont() as XSSFFont
            font.bold = true
            font.italic = true
            font.fontHeightInPoints = 9
            setFont(font)
        }

    // ══════════════════════════════════════════════
    // Save to Downloads
    // ══════════════════════════════════════════════
    private suspend fun saveToDownloads(workbook: XSSFWorkbook) {
        val fileName = "SmartPDF_Excel_${System.currentTimeMillis()}.xlsx"
        var outputStream: OutputStream? = null
        var savedFile: File? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val outputUri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                outputStream = outputUri?.let { contentResolver.openOutputStream(it) }
                savedFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                savedFile = File(dir, fileName)
                outputStream = java.io.FileOutputStream(savedFile)
            }

            outputStream?.use { workbook.write(it) }
            workbook.close()

            withContext(Dispatchers.Main) {
                showLoading(false)
                savedFile?.let {
                    NotificationUtils.showDownloadNotification(
                        this@PdfToExcelActivity, it,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                }
                toast("✅ Excel saved to Downloads!")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                showLoading(false)
                toast("Save failed: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════
    // UI helpers
    // ══════════════════════════════════════════════
    private fun showLoading(show: Boolean) {
        findViewById<View>(R.id.loadingLayout).visibility =
            if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnConvert).isEnabled = !show
    }

    private suspend fun updateProgress(msg: String, detail: String, progress: Int) =
        withContext(Dispatchers.Main) {
            findViewById<TextView>(R.id.tvProgress).text = msg
            findViewById<TextView>(R.id.tvProgressDetail).text = detail
            findViewById<ProgressBar>(R.id.pbPages).progress = progress
        }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ══════════════════════════════════════════════
    // Page thumbnail RecyclerView adapter
    // ══════════════════════════════════════════════
    inner class PageThumbAdapter(private val list: List<Bitmap>) :
        RecyclerView.Adapter<PageThumbAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val iv: ImageView  = view.findViewById(R.id.ivThumbnail)
            val tv: TextView   = view.findViewById(R.id.tvPageNumber)
            val r: View        = view.findViewById(R.id.btnRotate)
            val d: View        = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_organize_page, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.iv.setImageBitmap(list[position])
            holder.tv.text = (position + 1).toString()
            holder.r.visibility = View.GONE
            holder.d.visibility = View.GONE
        }

        override fun getItemCount() = list.size
    }
}
