package com.teedee.invoiceme.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val customerName: String,
    val customerContact: String?,
    val itemName: String,
    val quantity: Int,
    val pricePerItem: Double,
    val subtotal: Double,
    val tax: Double,
    val total: Double,
    val notes: String?,
    val createdAt: Long = System.currentTimeMillis()


)
