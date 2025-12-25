package com.trxsafe.payment.data.repository

import com.trxsafe.payment.data.dao.AddressBookDao
import com.trxsafe.payment.data.entity.AddressBook
import kotlinx.coroutines.flow.Flow

/**
 * 地址簿仓库
 */
class AddressBookRepository(private val dao: AddressBookDao) {
    
    /**
     * 获取所有地址
     */
    fun getAllAddresses(): Flow<List<AddressBook>> = dao.getAllAddresses()
    
    /**
     * 获取白名单地址
     */
    fun getWhitelistedAddresses(): Flow<List<AddressBook>> = dao.getWhitelistedAddresses()
    
    /**
     * 根据地址查询
     */
    suspend fun getByAddress(address: String): AddressBook? = dao.getByAddress(address)
    
    /**
     * 添加地址
     */
    suspend fun addAddress(
        name: String,
        address: String,
        isWhitelisted: Boolean = false,
        notes: String = ""
    ): Result<Long> {
        return try {
            // 检查地址是否已存在
            if (dao.isAddressExists(address) > 0) {
                Result.failure(Exception("该地址已存在"))
            } else {
                val addressBook = AddressBook(
                    name = name,
                    address = address,
                    isWhitelisted = isWhitelisted,
                    notes = notes
                )
                val id = dao.insert(addressBook)
                Result.success(id)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 更新地址
     */
    suspend fun updateAddress(addressBook: AddressBook): Result<Unit> {
        return try {
            dao.update(addressBook)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 删除地址
     */
    suspend fun deleteAddress(addressBook: AddressBook): Result<Unit> {
        return try {
            dao.delete(addressBook)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * 检查地址是否在白名单
     */
    suspend fun isWhitelisted(address: String): Boolean {
        return dao.isWhitelisted(address) ?: false
    }
}
