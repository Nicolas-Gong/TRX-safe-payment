# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2025-12-25

### Added
- Initial release of TRX Safe Payment App.
- Core Wallet Management (Create/Import/Delete).
- **Watch-Only Mode** for offline security.
- **Flash Pay Mode** (Standard TRON URI support).
- Energy Trading configuration (Price lock, multiplier).
- Biometric Authentication integration (App lock, Transaction sign).
- Multi-node Switching (Mainnet, Nile, Shasta).
- Detailed Transaction History with BottomSheet view.
- Local Address Book with Whitelist support.
- Offline QR Multipart protocol (`trxsafe:v1`).
- Transaction Audit View (Raw Hex).

### Security
- Mandatory 3-second long press for transfer confirmation.
- TEE/SE based private key storage.
- App-level auto-lock logic.
- Protocol-level restriction (TransferContract only).
