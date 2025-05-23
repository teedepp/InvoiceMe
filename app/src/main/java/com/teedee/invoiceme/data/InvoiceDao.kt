package com.teedee.invoiceme.data

import androidx.room.*

@Dao
interface InvoiceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: Invoice)

    @Query("SELECT * FROM invoices ORDER BY id DESC")
    suspend fun getAllInvoices(): List<Invoice>
}