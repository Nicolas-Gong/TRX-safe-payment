package com.trxsafe.payment.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trxsafe.payment.databinding.ItemWalletBinding
import com.trxsafe.payment.utils.setDebouncedClick

/**
 * 钱包列表适配器
 */
class WalletAdapter(
    private val onWalletClick: (WalletInfo) -> Unit,
    private val onWalletDelete: (WalletInfo) -> Unit,
    private val onWalletExport: (WalletInfo) -> Unit
) : ListAdapter<WalletInfo, WalletAdapter.WalletViewHolder>(WalletDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WalletViewHolder {
        val binding = ItemWalletBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WalletViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WalletViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class WalletViewHolder(private val binding: ItemWalletBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(wallet: WalletInfo) {
            binding.tvWalletName.text = wallet.name
            binding.tvWalletType.text = when (wallet.type) {
                WalletType.PRIVATE_KEY -> "私钥钱包"
                WalletType.WATCH_ONLY -> "观察钱包"
                WalletType.HARDWARE -> "硬件钱包"
            }
            binding.tvWalletAddress.text = wallet.address

            // 点击事件
            binding.btnViewDetails.setDebouncedClick(debounceDelayMs = 1000) {
                onWalletClick(wallet)
            }

            binding.btnExport.setDebouncedClick(debounceDelayMs = 1000) {
                onWalletExport(wallet)
            }

            binding.btnDelete.setDebouncedClick(debounceDelayMs = 1000) {
                onWalletDelete(wallet)
            }

            // 整个卡片点击
            binding.root.setDebouncedClick(debounceDelayMs = 1000) {
                onWalletClick(wallet)
            }
        }
    }

    class WalletDiffCallback : DiffUtil.ItemCallback<WalletInfo>() {
        override fun areItemsTheSame(oldItem: WalletInfo, newItem: WalletInfo): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: WalletInfo, newItem: WalletInfo): Boolean {
            return oldItem == newItem
        }
    }
}
