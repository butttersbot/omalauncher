# Hunch exchange signing bundle

`npm ci` then `node build.mjs` rebuilds the offline WebView bundle. `node test.mjs`
checks canonical SDK order fields, EIP-712 network separation and signer recovery
with an ephemeral test key (no HTTP).

Pinned @nktkas/hyperliquid 0.33.3 is the community TypeScript SDK listed in
Hyperliquid's official API documentation. It performs canonicalization,
MessagePack hashing, phantom-agent EIP-712 construction and signature splitting.
Hunch does not implement those algorithms itself. viem serializes the standard
EIP-712 JSON RPC request for Privy 0.17's ethSignTypedDataV4 method.

Runtime bundle has no key or network transport. Native Android validates the
reviewed immutable ticket, persists Signing/Submitting, asks the selected Privy
wallet for one signature after the native Confirm, and makes one fixed-host POST.
No server signer, API wallet, delegated approval or retry is used.

References checked 2026-10-28:
https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api
https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/signing
https://github.com/hyperliquid-dex/hyperliquid-python-sdk/blob/master/hyperliquid/utils/signing.py

No funded testnet acceptance or real-wallet signing is implied by the offline test.
