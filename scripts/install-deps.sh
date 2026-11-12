#!/usr/bin/env bash
# Installs the Solidity dependencies (forge-std and OpenZeppelin v5.4.0) into contracts/lib.
# They are not committed: `forge install --no-git` keeps this repo free of vendored copies.
set -euo pipefail
cd "$(dirname "$0")/../contracts"
[ -d lib/forge-std ] || forge install foundry-rs/forge-std --no-git
[ -d lib/openzeppelin-contracts ] || forge install OpenZeppelin/openzeppelin-contracts@v5.4.0 --no-git
echo "contracts/lib ready"
