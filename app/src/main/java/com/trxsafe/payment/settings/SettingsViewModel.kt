package com.trxsafe.payment.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Settings ViewModel
 * 管理设置配置的状态和业务逻辑
 */
class SettingsViewModel(
    private val validator: SettingsValidator = SettingsValidator(),
    private val repository: SettingsRepository = SettingsRepository()
) : ViewModel() {
    
    // 当前配置状态
    private val _configState = MutableStateFlow(SettingsConfig())
    val configState: StateFlow<SettingsConfig> = _configState.asStateFlow()
    
    // UI 状态
    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        // 加载保存的配置
        loadConfig()
    }
    
    /**
     * 加载配置
     */
    private fun loadConfig() {
        viewModelScope.launch {
            try {
                val config = repository.loadConfig()
                _configState.value = config
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error("加载配置失败：${e.message}")
            }
        }
    }
    
    /**
     * 更新收款地址
     * 
     * @param address 新地址
     * @param isFirstTime 是否首次设置
     */
    fun updateSellerAddress(address: String, isFirstTime: Boolean = false) {
        viewModelScope.launch {
            // 验证地址
            val validationResult = validator.validateSellerAddress(address)
            
            when (validationResult) {
                is SettingsValidationResult.Success -> {
                    // 判断是否需要二次确认
                    if (isFirstTime && _configState.value.isFirstTimeSetAddress) {
                        _uiState.value = SettingsUiState.RequireAddressConfirmation(address)
                    } else {
                        // 更新地址，并清空金额配置
                        val newConfig = _configState.value.copy(
                            sellerAddress = address,
                            pricePerUnitSun = 0L,
                            isPriceLocked = false,
                            isFirstTimeSetAddress = false
                        )
                        _configState.value = newConfig
                        _uiState.value = SettingsUiState.Success("地址设置成功")
                    }
                }
                is SettingsValidationResult.Error -> {
                    _uiState.value = SettingsUiState.Error(validationResult.message)
                }
                else -> {}
            }
        }
    }
    
    /**
     * 确认设置地址（二次确认）
     * 
     * @param address 确认的地址
     */
    fun confirmSellerAddress(address: String) {
        viewModelScope.launch {
            val newConfig = _configState.value.copy(
                sellerAddress = address,
                pricePerUnitSun = 0L,
                isPriceLocked = false,
                isFirstTimeSetAddress = false
            )
            _configState.value = newConfig
            _uiState.value = SettingsUiState.Success("地址设置成功")
        }
    }
    
    /**
     * 更新单价
     * 
     * @param priceStr 单价字符串（TRX 格式）
     */
    fun updatePrice(priceStr: String) {
        viewModelScope.launch {
            // 检查是否已锁定
            if (_configState.value.isPriceLocked) {
                _uiState.value = SettingsUiState.Error("单价已锁定，请先解锁后再修改")
                return@launch
            }
            
            // 验证并转换
            val (validationResult, priceSun) = validator.validatePriceInput(priceStr)
            
            when (validationResult) {
                is SettingsValidationResult.Success -> {
                    if (priceSun != null) {
                        val newConfig = _configState.value.copy(pricePerUnitSun = priceSun)
                        _configState.value = newConfig
                        _uiState.value = SettingsUiState.Idle
                    }
                }
                is SettingsValidationResult.Warning -> {
                    // 需要用户确认
                    _uiState.value = SettingsUiState.RequirePriceConfirmation(
                        priceSun ?: 0L,
                        validationResult.message
                    )
                }
                is SettingsValidationResult.Error -> {
                    _uiState.value = SettingsUiState.Error(validationResult.message)
                }
            }
        }
    }
    
    /**
     * 确认设置单价（用户确认警告后）
     * 
     * @param priceSun 确认的单价
     */
    fun confirmPrice(priceSun: Long) {
        viewModelScope.launch {
            val newConfig = _configState.value.copy(pricePerUnitSun = priceSun)
            _configState.value = newConfig
            _uiState.value = SettingsUiState.Success("单价设置成功")
        }
    }
    
    /**
     * 更新倍率
     * 
     * @param multiplierStr 倍率字符串
     */
    fun updateMultiplier(multiplierStr: String) {
        viewModelScope.launch {
            // 验证并转换
            val (validationResult, multiplier) = validator.validateMultiplierInput(multiplierStr)
            
            when (validationResult) {
                is SettingsValidationResult.Success -> {
                    if (multiplier != null) {
                        // 修改倍率不影响单价锁定状态
                        val newConfig = _configState.value.copy(multiplier = multiplier)
                        _configState.value = newConfig
                        _uiState.value = SettingsUiState.Idle
                    }
                }
                is SettingsValidationResult.Error -> {
                    _uiState.value = SettingsUiState.Error(validationResult.message)
                }
                else -> {}
            }
        }
    }
    
    /**
     * 锁定单价
     */
    fun lockPrice() {
        viewModelScope.launch {
            if (_configState.value.pricePerUnitSun <= 0) {
                _uiState.value = SettingsUiState.Error("请先设置单价")
                return@launch
            }
            
            val newConfig = _configState.value.copy(isPriceLocked = true)
            _configState.value = newConfig
            _uiState.value = SettingsUiState.Success("单价已锁定")
        }
    }
    
    /**
     * 解锁单价
     */
    fun unlockPrice() {
        viewModelScope.launch {
            val newConfig = _configState.value.copy(isPriceLocked = false)
            _configState.value = newConfig
            _uiState.value = SettingsUiState.Success("单价已解锁")
        }
    }

    /**
     * 切换生物识别启用状态
     */
    fun toggleBiometricEnabled(isEnabled: Boolean) {
        viewModelScope.launch {
            val newConfig = _configState.value.copy(isBiometricEnabled = isEnabled)
            _configState.value = newConfig
            // 这里不保存，点击保存按钮时统一保存，或者可以设计为立即保存
            // 此处为了保持一致性，仅更新状态，等待 saveConfig
        }
    }
    
    /**
     * 更新节点 URL
     */
    fun updateNodeUrl(url: String) {
        viewModelScope.launch {
            val newConfig = _configState.value.copy(nodeUrl = url)
            _configState.value = newConfig
        }
    }
    
    /**
     * 保存配置
     */
    fun saveConfig() {
        viewModelScope.launch {
            try {
                // 验证完整配置
                val validationResult = validator.validateConfig(_configState.value)
                
                when (validationResult) {
                    is SettingsValidationResult.Success -> {
                        // 保存配置
                        repository.saveConfig(_configState.value)
                        
                        // 自动锁定单价
                        if (!_configState.value.isPriceLocked) {
                            _configState.value = _configState.value.copy(isPriceLocked = true)
                        }
                        
                        _uiState.value = SettingsUiState.Success("配置保存成功")
                    }
                    is SettingsValidationResult.Error -> {
                        _uiState.value = SettingsUiState.Error(validationResult.message)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error("保存配置失败：${e.message}")
            }
        }
    }
    
    /**
     * 重置 UI 状态
     */
    fun resetUiState() {
        _uiState.value = SettingsUiState.Idle
    }
}

/**
 * Settings UI 状态
 */
sealed class SettingsUiState {
    /**
     * 空闲状态
     */
    object Idle : SettingsUiState()
    
    /**
     * 成功状态
     */
    data class Success(val message: String) : SettingsUiState()
    
    /**
     * 错误状态
     */
    data class Error(val message: String) : SettingsUiState()
    
    /**
     * 需要确认地址
     */
    data class RequireAddressConfirmation(val address: String) : SettingsUiState()
    
    /**
     * 需要确认单价
     */
    data class RequirePriceConfirmation(
        val priceSun: Long,
        val warningMessage: String
    ) : SettingsUiState()
}
