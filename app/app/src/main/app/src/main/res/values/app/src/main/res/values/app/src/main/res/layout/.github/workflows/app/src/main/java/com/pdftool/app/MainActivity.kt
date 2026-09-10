package com.pdftool.app

import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.pdftool.app.databinding.ActivityMainBinding
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ---- Activity result launchers (Storage Access Framework - no permissions needed) ----
    private var pendingAction: ((Uri?) -> Unit)? = null
    private var pendingMultiAction: ((List<Uri>) -> Unit)? = null

    private val pickSingleFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingAction?.invoke(uri)
        }

    private val pickMultipleFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingMultiAction?.invoke(uris)
        }

    private val pickSaveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            pendingAction?.invoke(uri)
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            pendingAction?.invoke(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Required once before using PDFBox on Android
        PDFBoxResourceLoader.init(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMerge.setOnClickListener { startMerge() }
        binding.btnSplit.setOnClickListener { startSplit() }
        binding.btnCompress.setOnClickListener { startCompress() }
        binding.btnRotate.setOnClickListener { startRotate() }
        binding.btnDeletePages.setOnClickListener { startDeletePage() }
        binding.btnWatermark.setOnClickListener { startWatermark() }
        binding.btnToImages.setOnClickListener { startToImages() }
        binding.btnProtect.setOnClickListener { startProtect() }
        binding.btnUnlock.setOnClickListener { startUnlock() }
    }

    private fun status(msg: String) {
        runOnUiThread {
            binding.tvStatus.text = msg
        }
    }

    private fun runSafely(block: () -> Unit) {
        try {
            block()
            status("Done ✅")
        } catch (e: Exception) {
            status("Error: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun askText(title: String, hint: String, onResult: (String) -> Unit) {
        val input = EditText(this)
        input.hint = hint
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onResult(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- MERGE ----------------
    private fun startMerge() {
        pendingMultiAction = { uris ->
            if (uris.size < 2) {
                status("Kam se kam 2 PDF select karein")
            } else {
                pendingAction = { outUri ->
                    if (outUri != null) {
                        status("Merging...")
                        runSafely { mergePdfs(uris, outUri) }
                    }
                }
                pickSaveFile.launch("merged.pdf")
            }
        }
        pickMultipleFiles.launch(arrayOf("application/pdf"))
    }

    private fun mergePdfs(inputs: List<Uri>, output: Uri) {
        val merger = PDFMergerUtility()
        contentResolver.openOutputStream(output)!!.use { out ->
            inputs.forEach { uri ->
                contentResolver.openInputStream(uri)!!.use { input ->
                    merger.addSource(input)
                }
            }
            // addSource with streams needs destination set before merge
            merger.destinationStream = out
            merger.mergeDocuments(null)
        }
    }

    // ---------------- SPLIT (every page -> own file) ----------------
    private fun startSplit() {
        pendingAction = { fileUri ->
            if (fileUri != null) {
                pendingAction = { folderUri ->
                    if (folderUri != null) {
                        status("Splitting...")
                        runSafely { splitPdf(fileUri, folderUri) }
                    }
                }
                pickFolder.launch(null)
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun splitPdf(input: Uri, folder: Uri) {
        val folderDoc = DocumentFile.fromTreeUri(this, folder)!!
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream).use { doc ->
                for (i in 0 until doc.numberOfPages) {
                    val single = PDDocument()
                    single.addPage(doc.getPage(i))
                    val newFile = folderDoc.createFile("application/pdf", "page_${i + 1}.pdf")!!
                    contentResolver.openOutputStream(newFile.uri)!!.use { out ->
                        single.save(out)
                    }
                    single.close()
                }
            }
        }
    }

    // ---------------- COMPRESS (re-encode images at lower quality) ----------------
    private fun startCompress() {
        pendingAction = { inUri ->
            if (inUri != null) {
                pendingAction = { outUri ->
                    if (outUri != null) {
                        status("Compressing...")
                        runSafely { compressPdf(inUri, outUri) }
                    }
                }
                pickSaveFile.launch("compressed.pdf")
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun compressPdf(input: Uri, output: Uri) {
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream).use { doc ->
                for (page in doc.pages) {
                    val resources = page.resources ?: continue
                    for (name in resources.xObjectNames.toList()) {
                        val xobj = resources.getXObject(name)
                        if (xobj is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                            try {
                                val bitmap = xobj.image
                                val compressed = JPEGFactory.createFromImage(doc, bitmap, 0.4f)
                                resources.put(name, compressed)
                            } catch (_: Exception) {
                                // skip images that fail to re-encode
                            }
                        }
                    }
                }
                contentResolver.openOutputStream(output)!!.use { out ->
                    doc.save(out)
                }
            }
        }
    }

    // ---------------- ROTATE (rotate every page 90°) ----------------
    private fun startRotate() {
        pendingAction = { inUri ->
            if (inUri != null) {
                pendingAction = { outUri ->
                    if (outUri != null) {
                        status("Rotating...")
                        runSafely { rotatePdf(inUri, outUri) }
                    }
                }
                pickSaveFile.launch("rotated.pdf")
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun rotatePdf(input: Uri, output: Uri) {
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream).use { doc ->
                for (page in doc.pages) {
                    page.rotation = (page.rotation + 90) % 360
                }
                contentResolver.openOutputStream(output)!!.use { out ->
                    doc.save(out)
                }
            }
        }
    }

    // ---------------- DELETE PAGE ----------------
    private fun startDeletePage() {
        pendingAction = { inUri ->
            if (inUri != null) {
                askText("Page Number Delete Karni Hai", "e.g. 2") { pageStr ->
                    val pageNum = pageStr.trim().toIntOrNull()
                    if (pageNum == null) {
                        status("Valid page number likhein")
                        return@askText
                    }
                    pendingAction = { outUri ->
                        if (outUri != null) {
                            status("Deleting page...")
                            runSafely { deletePage(inUri, outUri, pageNum) }
                        }
                    }
                    pickSaveFile.launch("edited.pdf")
                }
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun deletePage(input: Uri, output: Uri, pageNumberOneBased: Int) {
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream).use { doc ->
                val index = pageNumberOneBased - 1
                if (index < 0 || index >= doc.numberOfPages) {
                    throw IllegalArgumentException("Page number sahi nahi hai")
                }
                doc.removePage(index)
                contentResolver.openOutputStream(output)!!.use { out ->
                    doc.save(out)
                }
            }
        }
    }

    // ---------------- WATERMARK ----------------
    private fun startWatermark() {
        pendingAction = { inUri ->
            if (inUri != null) {
                askText("Watermark Text", "e.g. CONFIDENTIAL") { text ->
                    if (text.isBlank()) {
                        status("Watermark text likhein")
                        return@askText
                    }
                    pendingAction = { outUri ->
                        if (outUri != null) {
                            status("Adding watermark...")
                            runSafely { addWatermark(inUri, outUri, text) }
                        }
                    }
                    pickSaveFile.launch("watermarked.pdf")
                }
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun addWatermark(input: Uri, output: Uri, text: String) {
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream).use { doc ->
                for (page: PDPage in doc.pages) {
                    val box: PDRectangle = page.mediaBox
                    PDPageContentStream(
                        doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { cs ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA_BOLD, 40f)
                        cs.setNonStrokingColor(200, 200, 200)
                        cs.newLineAtOffset(box.width / 2 - 100, box.height / 2)
                        cs.showText(text)
                        cs.endText()
                    }
                }
                contentResolver.openOutputStream(output)!!.use { out ->
                    doc.save(out)
                }
            }
        }
    }

    // ---------------- PDF TO IMAGES ----------------
    private fun startToImages() {
        pendingAction = { inUri ->
            if (inUri != null) {
                pendingAction = { folderUri ->
                    if (folderUri != null) {
                        status("Converting to images...")
                        runSafely { pdfToImages(inUri, folderUri) }
                    }
                }
                pickFolder.launch(null)
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun pdfToImages(input: Uri, folder: Uri) {
        val folderDoc = DocumentFile.fromTreeUri(this, folder)!!
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream).use { doc ->
                val renderer = PDFRenderer(doc)
                for (i in 0 until doc.numberOfPages) {
                    val bitmap: Bitmap = renderer.renderImageWithDPI(i, 150f)
                    val newFile = folderDoc.createFile("image/jpeg", "page_${i + 1}.jpg")!!
                    contentResolver.openOutputStream(newFile.uri)!!.use { out ->
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                        out.write(bos.toByteArray())
                    }
                }
            }
        }
    }

    // ---------------- ADD PASSWORD ----------------
    private fun startProtect() {
        pendingAction = { inUri ->
            if (inUri != null) {
                askText("Naya Password Set Karein", "password") { pass ->
                    if (pass.isBlank()) {
                        status("Password likhein")
                        return@askText
                    }
                    pendingAction = { outUri ->
                        if (outUri != null) {
                            status("Protecting...")
                            runSafely { protectPdf(inUri, outUri, pass) }
                        }
                    }
                    pickSaveFile.launch("protected.pdf")
                }
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun protectPdf(input: Uri, output: Uri, password: String) {
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream).use { doc ->
                val ap = AccessPermission()
                val spp = StandardProtectionPolicy(password, password, ap)
                spp.encryptionKeyLength = 128
                doc.protect(spp)
                contentResolver.openOutputStream(output)!!.use { out ->
                    doc.save(out)
                }
            }
        }
    }

    // ---------------- REMOVE PASSWORD ----------------
    private fun startUnlock() {
        pendingAction = { inUri ->
            if (inUri != null) {
                askText("Current Password Likhein", "password") { pass ->
                    pendingAction = { outUri ->
                        if (outUri != null) {
                            status("Unlocking...")
                            runSafely { unlockPdf(inUri, outUri, pass) }
                        }
                    }
                    pickSaveFile.launch("unlocked.pdf")
                }
            }
        }
        pickSingleFile.launch(arrayOf("application/pdf"))
    }

    private fun unlockPdf(input: Uri, output: Uri, password: String) {
        contentResolver.openInputStream(input)!!.use { inStream ->
            PDDocument.load(inStream, password).use { doc ->
                doc.setAllSecurityToBeRemoved(true)
                contentResolver.openOutputStream(output)!!.use { out ->
                    doc.save(out)
                }
            }
        }
    }
}
