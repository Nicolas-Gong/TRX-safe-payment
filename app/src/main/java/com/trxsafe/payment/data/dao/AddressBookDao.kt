package com.trxsafe.payment.data.dao

import androidx.room.*
import com.trxsafe.payment.data.entity.AddressBook
import kotlinx.coroutines.flow.Flow

/**
 * 地址簿 DAO
 */
@Dao
interface AddressBookDao {
    
    /**
     * 获取所有地址
     */
    @Query("SELECT * FROM address_book ORDER BY createTime DESC")
    fun getAllAddresses(): Flow<List<AddressBook>>
    
    /**
     * 获取白名单地址
     */
    @Query("SELECT * FROM address_book WHERE isWhitelisted = 1 ORDER BY createTime DESC")
    fun getWhitelistedAddresses(): Flow<List<AddressBook>>
    
    /**
     * 根据地址查询
     */
    @Query("SELECT * FROM address_book WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): AddressBook?
    
    /**
     * 根据ID查询
     */
    @Query("SELECT * FROM address_book WHERE id = :id")
    suspend fun getById(id: Long): AddressBook?
    
    /**
     * 插入地址
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(addressBook: AddressBook): Long
    
    /**
     * 更新地址
     */
    @Update
    suspend fun update(addressBook: AddressBook)
    
    /**
     * 删除地址
     */
    @Delete
    suspend fun delete(addressBook: AddressBook)
    
    /**
     * 根据ID删除
     */
    @Query("DELETE FROM address_book WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    /**
     * 检查地址是否存在
     */
    @Query("SELECT COUNT(*) FROM address_book WHERE address = :address")
    suspend fun isAddressExists(address: String): Int
    
    /**
     * 检查地址是否在白名单
     */
    @Query("SELECT isWhitelisted FROM address_book WHERE address = :address LIMIT 1")
    suspend fun isWhitelisted(address: String): Boolean?
}
