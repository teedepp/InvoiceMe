package com.teedee.invoiceme

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.teedee.invoiceme.data.Invoice
import com.teedee.invoiceme.data.InvoiceDatabase
import com.teedee.invoiceme.databinding.ActivityInvoiceFormBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreateInvoiceActivity : AppCompatActivity() { // Change ComponentActivity to AppCompatActivity

    private lateinit var binding: ActivityInvoiceFormBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityInvoiceFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonCalculate.setOnClickListener {
            calculateInvoice()
        }

        binding.buttonDownload.setOnClickListener {
            downloadPdfInvoice()
        }

        binding.textViewSubtotal.visibility = View.GONE
        binding.textViewTotal.visibility = View.GONE
        binding.buttonSave.visibility = View.GONE

        val db = InvoiceDatabase.getDatabase(this)
        val invoiceDao = db.invoiceDao()

        binding.buttonSave.setOnClickListener {
            val invoice = Invoice(
                customerName = binding.editTextCustomerName.text.toString().trim(),
                customerContact = binding.editTextCustomerContact.text.toString().trim().ifEmpty { null },
                itemName = binding.editTextItemName.text.toString().trim(),
                quantity = binding.editTextItemQuantity.text.toString().trim().toInt(),
                pricePerItem = binding.editTextPricePerItem.text.toString().trim().toDouble(),
                tax = binding.editTextTax.text.toString().trim().toDouble(),
                notes = binding.editTextNotes.text.toString().trim().ifEmpty { null },
                subtotal = subtotal, // you may need to store `subtotal` and `total` as class-level vars
                total = total
            )

            lifecycleScope.launch {
                invoiceDao.insertInvoice(invoice)
                Toast.makeText(this@CreateInvoiceActivity, "Invoice Saved!", Toast.LENGTH_SHORT).show()
            }

            binding.editTextCustomerName.text?.clear()
            binding.editTextCustomerContact.text?.clear()
            binding.editTextItemName.text?.clear()
            binding.editTextItemQuantity.text?.clear()
            binding.editTextPricePerItem.text?.clear()
            binding.editTextTax.text?.clear()
            binding.editTextNotes.text?.clear()

            binding.textViewSubtotal.visibility = View.GONE
            binding.textViewTotal.visibility = View.GONE
            binding.buttonSave.visibility = View.GONE

        }

        binding.buttonDownload.setOnClickListener {
            if(!validateInputsForSave()) {
                return@setOnClickListener
            }

            // --- Handle PDF Generation ---
            // For Android 10+ (API 29+), use SAF to pick a file location.
            // For Android 9- (API 28-), request WRITE_EXTERNAL_STORAGE and save directly.

            val invoiceFileName = "Invoice_${binding.editTextCustomerName.text.toString().trim().replace(" ", "_")}_${System.currentTimeMillis()}.pdf"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 (API 29) and above: Use Storage Access Framework (SAF)
                createPdfFileLauncher.launch(invoiceFileName)
            } else {
                // Android 9 (API 28) and below: Request WRITE_EXTERNAL_STORAGE permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    // Permission already granted, proceed
                    downloadPdfInvoice() // Will save to public Downloads or app-specific external
                } else {
                    // Request permission
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            // --- End PDF Generation Handling ---


        }

    }

    private var subtotal = 0.0
    private var total = 0.0
    private var taxRate = 0.0


    private fun calculateInvoice(){
        binding.editTextItemQuantity.error = null
        binding.editTextPricePerItem.error = null
        binding.textViewTotal.visibility = View.GONE
        binding.textViewSubtotal.visibility = View.GONE
        binding.buttonSave.visibility = View.GONE

        val quantityStr = binding.editTextItemQuantity.text.toString().trim()
        val priceStr = binding.editTextPricePerItem.text.toString().trim()
        val taxStr = binding.editTextTax.text.toString().trim()

        if(quantityStr.isEmpty()) {
            binding.editTextItemQuantity.error = "Quantity is required"
            binding.editTextItemQuantity.requestFocus()
            return
        }
        val quantity = quantityStr.toIntOrNull()
        if (quantity == null || quantity <= 0) {
            binding.editTextItemQuantity.error = "Please enter a valid quantity (e.g., 1, 2, 3)"
            binding.editTextItemQuantity.requestFocus()
            return
        }

        if (priceStr.isEmpty()) {
            binding.editTextPricePerItem.error = "Price per item is required"
            binding.editTextPricePerItem.requestFocus()
            return
        }
        val pricePerItem = priceStr.toDoubleOrNull()
        if (pricePerItem == null || pricePerItem < 0) { // Price can be 0 if it's a free item, but not negative
            binding.editTextPricePerItem.error = "Please enter a valid price (e.g., 10.50)"
            binding.editTextPricePerItem.requestFocus()
            return
        }

        if(taxStr.isEmpty()) {
            binding.editTextTax.error = "Tax is required"
            binding.editTextTax.requestFocus()
            return
        }
        taxRate = taxStr.toDouble()
        if (taxRate <= 0) {
            binding.editTextTax.error = "Please enter a valid quantity (e.g., 1, 2, 3)"
            binding.editTextTax.requestFocus()
            return
        }

        subtotal = quantity * pricePerItem
        var taxAmount = subtotal * (taxRate / 100.0)
        total = subtotal + taxAmount

        binding.textViewSubtotal.text = String.format("Subtotal: $%.2f", subtotal)
        binding.textViewTotal.text = String.format("Total (incl. %.0f%% tax): $%.2f", (taxRate).toFloat(), total.toFloat())

        binding.textViewSubtotal.visibility = View.VISIBLE
        binding.textViewTotal.visibility = View.VISIBLE
        binding.buttonSave.visibility = View.VISIBLE
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission is granted, proceed to save PDF
            downloadPdfInvoice()
        } else {
            Toast.makeText(this, "Storage permission denied. Cannot save PDF.", Toast.LENGTH_LONG).show()
        }
    }

    private val createPdfFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf") // MIME type for PDF
    ) { uri: Uri? ->
        uri?.let {
            downloadPdfInvoice(it) // Pass the selected URI to the PDF generation function
        } ?: run {
            Toast.makeText(this, "PDF file creation cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateInputsForSave(): Boolean {
        val name = binding.editTextCustomerName.text.toString().trim()
        val item = binding.editTextItemName.text.toString().trim()
        val qty = binding.editTextItemQuantity.text.toString().trim()
        val price = binding.editTextPricePerItem.text.toString().trim()
        val tax = binding.editTextTax.text.toString().trim()

        if (name.isEmpty() || item.isEmpty() || qty.isEmpty() || price.isEmpty() || tax.isEmpty()) {
            Toast.makeText(this, "Please fill in all required fields.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // This function will be called either with a SAF URI (Android 10+) or without (Android 9-)
    private fun downloadPdfInvoice(outputUri: Uri? = null) {
        val pdfDocument = PdfDocument()
        val paint = Paint()
        val titlePaint = Paint()

        // Page dimensions (example: A4 size at 72 DPI)
        val pageWidth = 595
        val pageHeight = 842

        // Get invoice data
        val customerName = binding.editTextCustomerName.text.toString().trim()
        val customerContact = binding.editTextCustomerContact.text.toString().trim()
        val itemName = binding.editTextItemName.text.toString().trim()
        val quantity = binding.editTextItemQuantity.text.toString().trim().toIntOrNull() ?: 0
        val pricePerItem = binding.editTextPricePerItem.text.toString().trim().toDoubleOrNull() ?: 0.0
        val notes = binding.editTextNotes.text.toString().trim()
        val taxPercentage = taxRate

        // Create a PageInfo object
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()

        // Start the page
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        // Set up paints
        paint.color = resources.getColor(R.color.black, null) // Use your defined colors
        paint.textSize = 12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

        titlePaint.color = resources.getColor(R.color.black, null)
        titlePaint.textSize = 24f
        titlePaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        titlePaint.textAlign = Paint.Align.CENTER

        // --- Drawing content on the page ---
        var x = 40f
        var y = 40f

        // Title
        canvas.drawText("INVOICE", pageWidth / 2f, y, titlePaint)
        y += 40

        // Invoice Details
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Invoice Details:", x, y, paint)
        y += 20
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

        val dateFormat = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
        val currentDateTime = dateFormat.format(Date())
        canvas.drawText("Date: $currentDateTime", x, y, paint)
        y += 20

        // Customer Info
        y += 20
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Customer Information:", x, y, paint)
        y += 20
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Name: $customerName", x, y, paint)
        y += 20
        if (customerContact.isNotEmpty()) {
            canvas.drawText("Contact: $customerContact", x, y, paint)
            y += 20
        }

        // Item Details
        y += 20
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Item Details:", x, y, paint)
        y += 20
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Item: $itemName", x, y, paint)
        y += 20
        canvas.drawText("Quantity: $quantity", x, y, paint)
        y += 20
        canvas.drawText("Price per Item: $%.2f".format(pricePerItem), x, y, paint)
        y += 20

        // Notes (if any)
        if (notes.isNotEmpty()) {
            y += 20
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("Additional Notes:", x, y, paint)
            y += 20
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            // For multiline notes, you might need to split the text and draw line by line
            canvas.drawText(notes, x, y, paint)
            y += 20
        }

        // Calculations
        y += 40
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.LEFT // Align left for numbers
        canvas.drawText("Subtotal: $%.2f".format(subtotal), x, y, paint)
        y += 20
        canvas.drawText("Tax (%.2f%%): $%.2f".format(taxPercentage, subtotal * (taxPercentage / 100.0)), x, y, paint)
        y += 20
        paint.textSize = 18f // Larger for total
        canvas.drawText("TOTAL: $%.2f".format(total), x, y, paint)

        // Finish the page
        pdfDocument.finishPage(page)

        // --- Save the PDF ---
        try {
            val pdfFile: File
            if (outputUri != null) {
                // Android 10+ using SAF
                contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }
                Toast.makeText(this, "Invoice PDF saved to chosen location!", Toast.LENGTH_LONG).show()
            } else {
                // Android 9- or fallback if SAF not used (saving to Downloads directory)
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs() // Create directory if it doesn't exist
                }
                val fileName = "Invoice_${customerName.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
                pdfFile = File(downloadsDir, fileName)
                FileOutputStream(pdfFile).use { fos ->
                    pdfDocument.writeTo(fos)
                }
                Toast.makeText(this, "Invoice PDF saved to ${pdfFile.absolutePath}", Toast.LENGTH_LONG).show()

                // Optional: Open the PDF file after saving
                openPdf(pdfFile)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error generating PDF: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pdfDocument.close() // Important: Always close the document
        }
    }

    // Function to open the generated PDF (for Android 9- path)
    private fun openPdf(pdfFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.provider", // Must match the authority in your manifest
            pdfFile
        )
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/pdf")
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION // Grant temporary read permission
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No application found to open PDF files.", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
}
