package com.trxsafe.payment.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 地址簿实体
 */
@Entity(tableName = "address_book")
data class AddressBook(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * 联系人名称/备注
     */
    val name: String,
    
    /**
     * 钱包地址
     */
    val address: String,
    
    /**
     * 是否在白名单中
     * 白名单地址在转账时风险等级较低
     */
    val isWhitelisted: Boolean = false,
    
    /**
     * 创建时间
     */
    val createTime: Long = System.currentTimeMillis(),
    
    /**
     * 备注信息
     */
    val notes: String = ""
)
