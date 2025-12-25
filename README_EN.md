# TRX Safe Payment

[中文](./README.md) | [English](./README_EN.md)

TRX Safe Payment is a high-security Android wallet designed specifically for **TRON (TRX) Energy Trading** scenarios. It provides enterprise-grade protection through multi-layer verification, strict transaction constraints, and a robust cold-hot separation mechanism.

## 🌟 Key Highlights

- **Extreme Security Constraints**: System-level restriction to `TransferContract` only, preventing smart contract traps and malicious authorizations.
- **Cold-Hot Separation**: Supports both hot wallet direct signing and cold wallet offline QR code scanning/signing.
- **Watch-Only Mode**: Monitor assets by importing addresses only, keeping private keys absolutely offline.
- **Multi-layer Risk Control**: Integrated unit price locking, multiplier control, and whitelist comparison.
- **Biometric Protection**: Integrated App lock and biometric verification for every sensitive action.
- **Flash Pay Mode**: 100% offline QR generation for quick payments (standard TRON URI).

## 📄 Documentation / 说明文档

- **[GETTING_STARTED_ZH.md](./GETTING_STARTED_ZH.md)**: **Beginner's guide to Run & Build APK (Chinese)**.
- **[SECURITY.md](./SECURITY.md)**: Deep dive into the four-layer security architecture.
- **[USER_GUIDE_ZH.md](./USER_GUIDE_ZH.md)**: Detailed Chinese operation manual.
- **[TRUST_AND_VERIFICATION.md](./TRUST_AND_VERIFICATION.md)**: How to verify the safety and transparency of the App.
- **[DEVELOPER_GUIDE.md](./DEVELOPER_GUIDE.md)**: Architecture overview for developers.

---

## 🏗️ Technical Architecture

- **Language**: Kotlin
- **Pattern**: MVVM (Model-View-ViewModel)
- **Engine**: [Trident SDK](https://github.com/tronprotocol/trident)
- **Database**: Room (Local Address Book)
- **Security**: Android Jetpack Security (Crypto/Biometric)
- **UI**: Material Design 3

## 🚀 Getting Started

### Requirements
- Android Studio Iguana or higher
- Android SDK 24 (7.0) +
- Java 17

### Build
1. Clone the repo.
2. Sync Gradle in Android Studio.
3. Run on a physical device with Biometric support.

## 🔍 Verification

We maintain 100% transparency. You can verify any transaction:
1. Generate an unsigned transaction QR.
2. Scan it with a 3rd party scanner to get the `rawData`.
3. Decode it at [Tronscan Decoder](https://tronscan.org/#/tools/transaction-decoder).
4. **Verification**: You will see the exact Recipient, Amount, and Block Info, proving no tampering has occurred.

## ⚖️ Disclaimer

This App is a high-security tool, but blockchain transactions are irreversible. Always double-check addresses. Use at your own risk.
