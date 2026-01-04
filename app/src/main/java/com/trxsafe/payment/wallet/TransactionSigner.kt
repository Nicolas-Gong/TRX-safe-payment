package com.trxsafe.payment.wallet

import com.google.protobuf.ByteString
import com.trxsafe.payment.security.SecurityPolicy
import com.trxsafe.payment.security.TransactionValidator
import com.trxsafe.payment.security.ValidationResult
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve
import org.tron.trident.core.key.KeyPair
import org.tron.trident.crypto.Hash
import org.tron.trident.proto.Chain
import org.tron.trident.proto.Contract
import java.math.BigInteger

/**
 * 交易签名管理器
 * 硬性约束：
 * 1. 签名前必须验证交易类型
 * 2. 仅允许签名 TRX 普通转账
 * 3. 任意异常必须直接拒绝签名
 */
class TransactionSigner(
    private val validator: TransactionValidator = TransactionValidator()
) {

    /**
     * 签名交易
     *
     * @param transaction 待签名的交易
     * @param keyPair 密钥对
     * @return 签名后的交易
     * @throws SecurityException 安全检查失败时抛出异常
     */
    @Throws(SecurityException::class)
    suspend fun signTransaction(
        transaction: Chain.Transaction, keyPair: KeyPair
    ): Chain.Transaction {
        try {
            // 硬性约束 1：签名前必须验证交易类型
            val validationResult = validator.validateTransaction(transaction)
            if (!validationResult.isValid) {
                throw SecurityException("交易验证失败：${validationResult.message}")
            }

            val rawData = transaction.rawData
            if (!rawData.data.isEmpty) {
                throw SecurityException(
                    "禁止签名包含 data 的交易（${rawData.data.size()} 字节）"
                )
            }

            // 硬性约束 2：再次确认仅允许 TRX 普通转账
            val contractType = transaction.rawData.getContract(0).type
            if (contractType != Chain.Transaction.Contract.ContractType.TransferContract) {
                throw SecurityException("严重错误：尝试签名非 TRX 转账交易")
            }

            // 验证私钥地址与交易发送方地址匹配
            val transactionOwnerAddress = validateAndGetOwnerAddress(transaction)
            val keyPairAddress = keyPair.toBase58CheckAddress()
            android.util.Log.d("TransactionSigner", "交易发送方地址: $transactionOwnerAddress")
            android.util.Log.d("TransactionSigner", "密钥对地址: $keyPairAddress")

            if (transactionOwnerAddress != keyPairAddress) {
                throw SecurityException("私钥与交易发送方地址不匹配")
            }

            // 执行本地签名 - 不依赖网络
            val signedTransaction = signTransactionLocally(transaction, keyPair)

            return signedTransaction

        } catch (e: SecurityException) {
            // 硬性约束 3：任意异常必须直接拒绝签名
            throw e
        } catch (e: Exception) {
            // 硬性约束 3：任意异常必须直接拒绝签名
            if (SecurityPolicy.REJECT_ON_ANY_EXCEPTION) {
                throw SecurityException("签名失败，已拒绝：${e.message}", e)
            }
            throw SecurityException("签名过程发生未知错误", e)
        }
    }

    /**
     * 从交易中提取并验证发送方地址
     */
    private fun validateAndGetOwnerAddress(transaction: Chain.Transaction): String {
        val contract = transaction.rawData.getContract(0)
        val transferContract = Contract.TransferContract.parseFrom(contract.parameter.value)
        val ownerBytes = transferContract.ownerAddress.toByteArray()
        return org.tron.trident.utils.Base58Check.bytesToBase58(ownerBytes)
    }

    /**
     * 创建 TRX 转账交易（未签名）
     *
     * @param fromAddress 发送方地址
     * @param toAddress 接收方地址
     * @param amountSun 转账金额（单位：sun）
     * @return 未签名的交易
     * @throws SecurityException 参数验证失败时抛出异常
     */
    @Throws(SecurityException::class)
    fun createTransferTransaction(
        fromAddress: ByteString, toAddress: ByteString, amountSun: Long
    ): Chain.Transaction {
        try {
            validator.validateAmount(amountSun)

            val transferContract =
                Contract.TransferContract.newBuilder().setOwnerAddress(fromAddress)
                    .setToAddress(toAddress).setAmount(amountSun).build()

            val contract = Chain.Transaction.Contract.newBuilder()
                .setType(Chain.Transaction.Contract.ContractType.TransferContract)
                .setParameter(com.google.protobuf.Any.pack(transferContract)).build()

            val timestamp = System.currentTimeMillis()
            val expiration = timestamp + 60000

            android.util.Log.d(
                "TransactionSigner", "创建交易 - timestamp: $timestamp, expiration: $expiration"
            )

            val rawData =
                Chain.Transaction.raw.newBuilder().addContract(contract).setTimestamp(timestamp)
                    .setExpiration(expiration).setFeeLimit(10_000_000).setData(ByteString.empty())
                    .build()

            return Chain.Transaction.newBuilder().setRawData(rawData).build()

        } catch (e: Exception) {
            throw SecurityException("创建交易失败：${e.message}", e)
        }
    }

    /**
     * 本地签名交易（不依赖网络）
     *
     * @param transaction 待签名的交易
     * @param keyPair 密钥对
     * @return 签名后的交易
     */
    private fun signTransactionLocally(
        transaction: Chain.Transaction, keyPair: KeyPair
    ): Chain.Transaction {
        try {
            val rawDataBytes = transaction.rawData.toByteArray()

            val txHash = Hash.sha256(rawDataBytes)
            android.util.Log.d("TransactionSigner", "交易ID (txID): ${org.tron.trident.utils.Numeric.toHexString(txHash)}")

            val signature = signWithECDSA(txHash, keyPair)
            android.util.Log.d("TransactionSigner", "签名长度: ${signature.size} 字节")

            val signedTransaction = Chain.Transaction.newBuilder().setRawData(transaction.rawData)
                .addSignature(ByteString.copyFrom(signature)).build()

            return signedTransaction

        } catch (e: Exception) {
            throw SecurityException("本地签名失败：${e.message}", e)
        }
    }

    /**
     * 使用ECDSA签名交易哈希
     * 使用真实的私钥进行ECDSA签名
     */
    private fun signWithECDSA(txHash: ByteArray, keyPair: KeyPair): ByteArray {
        try {
            val privateKeyHex = keyPair.toPrivateKey()

            val privateKeyBytes = hexStringToByteArray(privateKeyHex)
            val privateKeyInt = java.math.BigInteger(1, privateKeyBytes)

            val curve = SecP256K1Curve()
            val n = curve.order
            val h = java.math.BigInteger.valueOf(1)
            val G = curve.createPoint(
                java.math.BigInteger(
                    "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16
                ), java.math.BigInteger(
                    "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16
                )
            )
            val domain = ECDomainParameters(curve, G, n, h)

            val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
            val privateKeyParams = ECPrivateKeyParameters(privateKeyInt, domain)

            signer.init(true, privateKeyParams)

            val components = signer.generateSignature(txHash)
            val r = components[0]
            val s = components[1]

            val halfOrder = n.shiftRight(1)
            val adjustedS = if (s > halfOrder) {
                n.subtract(s)
            } else {
                s
            }

            val rBytes = r.toByteArray()
            val sBytes = adjustedS.toByteArray()

            val rPadded = padToLength(rBytes, 32)
            val sPadded = padToLength(sBytes, 32)

            val publicKey = G.multiply(privateKeyInt)
            val v = calculateRecoveryId(txHash, r, adjustedS, publicKey, curve)

            val vAdjusted = (v + 27).toByte()

            val rawSignature = rPadded + sPadded + byteArrayOf(vAdjusted)

            return rawSignature

        } catch (e: Exception) {
            throw SecurityException("签名失败：${e.message}", e)
        }
    }

    private fun calculateRecoveryId(
        txHash: ByteArray,
        r: BigInteger,
        s: BigInteger,
        expectedPublicKey: ECPoint,
        curve: SecP256K1Curve
    ): Int {
        val e = BigInteger(1, txHash)
        val n = curve.order
        val G = curve.createPoint(
            java.math.BigInteger(
                "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16
            ), java.math.BigInteger(
                "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16
            )
        )

        android.util.Log.d(
            "TransactionSigner", "calculateRecoveryId - 正在恢复公钥..."
        )

        for (v in 0..3) {
            try {
                val recoveredPublicKey = recoverPublicKey(e, r, s, v, curve, G, n)
                if (recoveredPublicKey != null) {
                    if (recoveredPublicKey.equals(expectedPublicKey)) {
                        android.util.Log.d("TransactionSigner", "Found correct recovery ID: v=$v")
                        return v
                    }
                }
            } catch (ex: Exception) {
                continue
            }
        }

        android.util.Log.e("TransactionSigner", "Failed to find correct recovery ID, returning 0")
        return 0
    }

    private fun recoverPublicKey(
        e: BigInteger,
        r: BigInteger,
        s: BigInteger,
        v: Int,
        curve: SecP256K1Curve,
        G: ECPoint,
        n: BigInteger
    ): ECPoint? {
        try {
            val p = curve.field.characteristic

            val x = if (v >= 2) r + n else r

            if (x >= p || x <= BigInteger.ZERO) {
                return null
            }

            val alpha = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)

            val beta = modularSquareRoot(alpha, p)

            if (beta == null) {
                return null
            }

            val y = if (v % 2 == 0) {
                if (beta.mod(BigInteger.valueOf(2)) == BigInteger.ZERO) beta else p.subtract(beta)
            } else {
                if (beta.mod(BigInteger.valueOf(2)) != BigInteger.ZERO) beta else p.subtract(beta)
            }

            val R = curve.createPoint(x, y)

            val eNeg = e.negate().mod(n)
            val rInv = r.modInverse(n)

            val Q = R.multiply(s).add(G.multiply(eNeg)).multiply(rInv)

            return Q
        } catch (e: Exception) {
            return null
        }
    }

    private fun modularSquareRoot(n: BigInteger, p: BigInteger): BigInteger? {
        if (n == BigInteger.ZERO) return BigInteger.ZERO

        if (p == BigInteger.valueOf(2)) return n

        val legendreSymbol = legendreSymbol(n, p)
        if (legendreSymbol != BigInteger.ONE) {
            return null
        }

        if (p.mod(BigInteger.valueOf(4)) == BigInteger.valueOf(3)) {
            return n.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), p)
        }

        val pMinusOne = p.subtract(BigInteger.ONE)
        var sVal = BigInteger.ZERO
        var tempP = pMinusOne
        while (tempP.mod(BigInteger.valueOf(2)) == BigInteger.ZERO) {
            tempP = tempP.divide(BigInteger.valueOf(2))
            sVal = sVal.add(BigInteger.ONE)
        }

        val s = sVal
        val q = tempP

        var z = BigInteger.valueOf(2)
        while (legendreSymbol(z, p) != BigInteger.valueOf(-1)) {
            z = z.add(BigInteger.ONE)
        }

        var c = z.modPow(q, p)
        var x = n.modPow(q.add(BigInteger.ONE).divide(BigInteger.valueOf(2)), p)
        var t = n.modPow(q, p)
        var m = s

        while (t != BigInteger.ONE) {
            if (t == BigInteger.ZERO) return BigInteger.ZERO

            var i = BigInteger.ONE
            var tempT = t.multiply(t).mod(p)

            while (i < m) {
                if (tempT == BigInteger.ONE) break
                tempT = tempT.multiply(tempT).mod(p)
                i = i.add(BigInteger.ONE)
            }

            val twoPowerMMinusI = BigInteger.valueOf(2).pow((m.subtract(i)).toInt())
            var b = c.modPow(twoPowerMMinusI, p)

            x = x.multiply(b).mod(p)
            t = t.multiply(b).multiply(b).mod(p)
            c = b.multiply(b).mod(p)
            m = i
        }

        return x
    }

    private fun legendreSymbol(a: BigInteger, p: BigInteger): BigInteger {
        val ls = a.modPow(p.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2)), p)
        return if (ls == p.subtract(BigInteger.ONE)) BigInteger.valueOf(-1) else ls
    }

    private fun padToLength(bytes: ByteArray, length: Int): ByteArray {
        if (bytes.size >= length) {
            return bytes.copyOfRange(bytes.size - length, bytes.size)
        }
        val padded = ByteArray(length)
        System.arraycopy(bytes, 0, padded, length - bytes.size, bytes.size)
        return padded
    }

    private fun convertToDERSignature(r: ByteArray, s: ByteArray): ByteArray {
        val rInt = BigInteger(1, r)
        val sInt = BigInteger(1, s)

        val rAsn1 = ASN1Integer(rInt)
        val sAsn1 = ASN1Integer(sInt)

        val sequence = DERSequence(arrayOf(rAsn1, sAsn1))

        return sequence.encoded
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] =
                ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * 验证签名后的交易
     *
     * @param signedTransaction 已签名的交易
     * @return 验证结果
     */
    fun verifySignedTransaction(signedTransaction: Chain.Transaction): ValidationResult {
        return try {
            // 检查是否包含签名
            if (signedTransaction.signatureCount == 0) {
                return ValidationResult.failure("交易未签名")
            }

            // 验证交易类型
            validator.validateTransaction(signedTransaction)

            ValidationResult.success("签名验证通过")
        } catch (e: SecurityException) {
            ValidationResult.failure("签名验证失败：${e.message}")
        }
    }
}
