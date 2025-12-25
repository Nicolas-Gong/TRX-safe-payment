package com.trxsafe.payment.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.trxsafe.payment.R
import com.trxsafe.payment.data.AppDatabase
import com.trxsafe.payment.data.entity.AddressBook
import com.trxsafe.payment.data.repository.AddressBookRepository
import com.trxsafe.payment.databinding.ActivityAddressBookBinding
import com.trxsafe.payment.databinding.ItemAddressBookBinding
import com.trxsafe.payment.wallet.WalletManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 地址簿管理界面
 */
class AddressBookActivity : BaseActivity() {
    
    private lateinit var binding: ActivityAddressBookBinding
    private lateinit var repository: AddressBookRepository
    private lateinit var adapter: AddressBookAdapter
    private lateinit var walletManager: WalletManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddressBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        val database = AppDatabase.getInstance(this)
        repository = AddressBookRepository(database.addressBookDao())
        walletManager = WalletManager(this)
        
        setupRecyclerView()
        setupListeners()
        observeAddresses()
    }
    
    private fun setupRecyclerView() {
        adapter = AddressBookAdapter(
            onEditClick = { address -> showEditDialog(address) },
            onDeleteClick = { address -> showDeleteConfirmation(address) }
        )
        
        binding.rvAddressList.layoutManager = LinearLayoutManager(this)
        binding.rvAddressList.adapter = adapter
    }
    
    private fun setupListeners() {
        binding.btnAddAddress.setOnClickListener {
            showAddDialog()
        }
    }
    
    private fun observeAddresses() {
        lifecycleScope.launch {
            repository.getAllAddresses().collectLatest { addresses ->
                if (addresses.isEmpty()) {
                    binding.rvAddressList.visibility = View.GONE
                    binding.tvEmptyState.visibility = View.VISIBLE
                } else {
                    binding.rvAddressList.visibility = View.VISIBLE
                    binding.tvEmptyState.visibility = View.GONE
                    adapter.submitList(addresses)
                }
            }
        }
    }
    
    private fun showAddDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_address, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val etAddress = dialogView.findViewById<TextInputEditText>(R.id.etAddress)
        val etNotes = dialogView.findViewById<TextInputEditText>(R.id.etNotes)
        val switchWhitelist = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchWhitelist)
        
        AlertDialog.Builder(this)
            .setTitle("添加地址")
            .setView(dialogView)
            .setPositiveButton("添加") { _, _ ->
                val name = etName.text.toString().trim()
                val address = etAddress.text.toString().trim()
                val notes = etNotes.text.toString().trim()
                val isWhitelisted = switchWhitelist.isChecked
                
                if (name.isEmpty() || address.isEmpty()) {
                    Toast.makeText(this, "名称和地址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (!walletManager.isValidAddress(address)) {
                    Toast.makeText(this, "地址格式错误", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                lifecycleScope.launch {
                    repository.addAddress(name, address, isWhitelisted, notes).fold(
                        onSuccess = {
                            Toast.makeText(this@AddressBookActivity, "添加成功", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Toast.makeText(this@AddressBookActivity, "添加失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showEditDialog(addressBook: AddressBook) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_address, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etName)
        val etAddress = dialogView.findViewById<TextInputEditText>(R.id.etAddress)
        val etNotes = dialogView.findViewById<TextInputEditText>(R.id.etNotes)
        val switchWhitelist = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchWhitelist)
        
        // 填充现有数据
        etName.setText(addressBook.name)
        etAddress.setText(addressBook.address)
        etNotes.setText(addressBook.notes)
        switchWhitelist.isChecked = addressBook.isWhitelisted
        etAddress.isEnabled = false // 地址不可修改
        
        AlertDialog.Builder(this)
            .setTitle("编辑地址")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val notes = etNotes.text.toString().trim()
                val isWhitelisted = switchWhitelist.isChecked
                
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                lifecycleScope.launch {
                    val updated = addressBook.copy(
                        name = name,
                        notes = notes,
                        isWhitelisted = isWhitelisted
                    )
                    repository.updateAddress(updated).fold(
                        onSuccess = {
                            Toast.makeText(this@AddressBookActivity, "更新成功", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Toast.makeText(this@AddressBookActivity, "更新失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showDeleteConfirmation(addressBook: AddressBook) {
        AlertDialog.Builder(this)
            .setTitle("删除地址")
            .setMessage("确定要删除 ${addressBook.name} 吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteAddress(addressBook).fold(
                        onSuccess = {
                            Toast.makeText(this@AddressBookActivity, "删除成功", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Toast.makeText(this@AddressBookActivity, "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

/**
 * 地址簿适配器
 */
class AddressBookAdapter(
    private val onEditClick: (AddressBook) -> Unit,
    private val onDeleteClick: (AddressBook) -> Unit
) : RecyclerView.Adapter<AddressBookAdapter.ViewHolder>() {
    
    private var addresses = listOf<AddressBook>()
    
    fun submitList(list: List<AddressBook>) {
        addresses = list
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAddressBookBinding.inflate(
            android.view.LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(addresses[position])
    }
    
    override fun getItemCount() = addresses.size
    
    inner class ViewHolder(private val binding: ItemAddressBookBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(addressBook: AddressBook) {
            binding.tvName.text = addressBook.name
            binding.tvAddress.text = addressBook.address
            
            if (addressBook.notes.isNotEmpty()) {
                binding.tvNotes.text = addressBook.notes
                binding.tvNotes.visibility = View.VISIBLE
            } else {
                binding.tvNotes.visibility = View.GONE
            }
            
            if (addressBook.isWhitelisted) {
                binding.tvWhitelistBadge.visibility = View.VISIBLE
            } else {
                binding.tvWhitelistBadge.visibility = View.GONE
            }
            
            binding.btnEdit.setOnClickListener {
                onEditClick(addressBook)
            }
            
            binding.btnDelete.setOnClickListener {
                onDeleteClick(addressBook)
            }
        }
    }
}
