package com.trxsafe.payment.qrcode

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.tron.trident.proto.Chain
import com.google.protobuf.ByteString

/**
 * 主 App QR 生成器
 * 用于生成未签名交易二维码
 */
class MainAppQRGenerator {
    
    /**
     * 从交易生成未签名交易 QR 数据
     * 
     * @param transaction 未签名的交易
     * @param fromAddress 发送方地址
     * @return 未签名交易 QR 数据
     */
    fun createUnsignedTransactionQR(
        transaction: Chain.Transaction,
        fromAddress: String
    ): UnsignedTransactionQR {
        
        val rawData = transaction.rawData
        val contract = rawData.getContract(0)
        val transferContract = org.tron.trident.proto.Contract.TransferContract
            .parseFrom(contract.parameter.value)
        
        // 提取交易信息
        val toAddress = org.tron.trident.utils.Base58Check.bytesToBase58(
            transferContract.toAddress.toByteArray()
        )
        val amountSun = transferContract.amount
        
        // 提取引用区块信息
        val refBlockHash = QRCodec.bytesToBase64(rawData.refBlockHash.toByteArray())
        val refBlockHeight = 0L // TRON 交易中仅存储 2 字节 refBlockBytes，不直接提供完整高度
        
        // 原始交易数据（用于签名）
        val rawDataBytes = rawData.toByteArray()
        val rawDataBase64 = QRCodec.bytesToBase64(rawDataBytes)
        
        return UnsignedTransactionQR(
            fromAddress = fromAddress,
            toAddress = toAddress,
            amountSun = amountSun,
            refBlockHash = refBlockHash,
            refBlockHeight = refBlockHeight,
            expiration = rawData.expiration,
            timestamp = rawData.timestamp,
            rawDataBase64 = rawDataBase64
        )
    }
    
    /**
     * 生成分片二维码列表
     * 
     * @param content 原始数据内容
     * @param size 二维码尺寸
     * @return 二维码 Bitmap 列表
     */
    fun generateMultiPartQRCodeBitmaps(content: String, size: Int = 512): List<Bitmap> {
        val chunks = QRSplitter.split(content)
        return chunks.map { chunk ->
            generateQRCodeBitmap(chunk, size)
        }
    }

    /**
     * 生成二维码 Bitmap
     * 
     * @param content 二维码内容
     * @param size 二维码尺寸（像素）
     * @return 二维码 Bitmap
     */
    fun generateQRCodeBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        
        return bitmap
    }
}

/**
 * 冷钱包 QR 处理器
 * 用于扫描、签名、生成签名二维码
 */
class ColdWalletQRProcessor {
    
    /**
     * 签名交易并生成签名 QR 数据
     *
     * @param unsigned 未签名交易 QR 数据
     * @param walletManager 钱包管理器
     * @return 已签名交易 QR 数据
     * @throws SecurityException 签名失败时抛出
     */
    @Throws(SecurityException::class)
    fun signAndCreateQR(
        unsigned: UnsignedTransactionQR,
        walletManager: com.trxsafe.payment.wallet.WalletManager
    ): SignedTransactionQR {

        // 重建交易对象
        val rawDataBytes = QRCodec.base64ToBytes(unsigned.rawDataBase64)
        val rawData = Chain.Transaction.raw.parseFrom(rawDataBytes)

        val unsignedTransaction = Chain.Transaction.newBuilder()
            .setRawData(rawData)
            .build()

        // 签名交易 - 使用runBlocking调用suspend函数
        val signedTransaction = kotlinx.coroutines.runBlocking {
            walletManager.signTransferContract(unsignedTransaction)
        }
        
        // 提取签名
        val signature = signedTransaction.getSignature(0)
        val signatureBase64 = QRCodec.bytesToBase64(signature.toByteArray())
        
        // 完整签名交易
        val signedTxBytes = signedTransaction.toByteArray()
        val signedTxBase64 = QRCodec.bytesToBase64(signedTxBytes)
        
        return SignedTransactionQR(
            toAddress = unsigned.toAddress,
            amountSun = unsigned.amountSun,
            signatureBase64 = signatureBase64,
            signedTransactionBase64 = signedTxBase64
        )
    }
    
    /**
     * 生成签名二维码 Bitmap
     */
    fun generateSignedQRCodeBitmap(content: String, size: Int = 512): Bitmap {
        return MainAppQRGenerator().generateQRCodeBitmap(content, size)
    }
}

/**
 * 主 App 签名验证器
 * 用于扫描签名二维码并验证
 */
class SignatureVerifier {
    
    /**
     * 验证签名交易
     * 
     * @param originalUnsigned 原始未签名交易 QR 数据
     * @param signed 已签名交易 QR 数据
     * @return 验证结果
     */
    fun verify(
        originalUnsigned: UnsignedTransactionQR,
        signed: SignedTransactionQR
    ): VerificationResult {
        
        // 1. 验证基本数据
        if (!QRCodec.validateSignedTransaction(originalUnsigned, signed)) {
            return VerificationResult.Failure("交易数据被篡改")
        }
        
        // 2. 验证金额
        if (originalUnsigned.amountSun != signed.amountSun) {
            return VerificationResult.Failure(
                "金额被篡改：期望 ${originalUnsigned.amountSun}，实际 ${signed.amountSun}"
            )
        }
        
        // 3. 验证接收地址
        if (originalUnsigned.toAddress != signed.toAddress) {
            return VerificationResult.Failure(
                "接收地址被篡改"
            )
        }
        
        return VerificationResult.Success("验证通过")
    }
    
    /**
     * 重建完整交易
     * 
     * @param signed 已签名交易 QR 数据
     * @return 完整的签名交易
     */
    fun rebuildTransaction(signed: SignedTransactionQR): Chain.Transaction {
        val signedTxBytes = QRCodec.base64ToBytes(signed.signedTransactionBase64)
        return Chain.Transaction.parseFrom(signedTxBytes)
    }
}

/**
 * 验证结果
 */
sealed class VerificationResult {
    data class Success(val message: String) : VerificationResult()
    data class Failure(val message: String) : VerificationResult()
}
