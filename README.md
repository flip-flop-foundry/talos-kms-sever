# Talos KMS Server

A simple KMS server for disk encryption in Talos clusters, intended to be run as Linux systemd service.


## Features

* Supported by [Talos Disk Encryption](https://www.talos.dev/v1.11/talos-guides/configuration/disk-encryption/)
* Works for State, Ephemeral and [UserVolumes](https://www.talos.dev/v1.11/talos-guides/configuration/disk-management/user/)


## Basic Operation

1. The Talos node boots, determines that a disk needs to be encrypted
2. The Talos node generates a random lvm key for the disk and sends it to the KMS server in a "seal" request
3. The KMS server:
    * Collects the node UUID and IP address from the request
    * Generates a random AES-256-GCM key
    * Generates a random nonce
    * Configures GCM to use the node UUID and IP address as additional authenticated data (AAD)
        * This helps ensure that the encrypted lvm key can only be decrypted by the same node
    * Encrypts the lvm key using AES-256-GCM with the generated key, nonce and AAD
    * Stores the encrypted lvm key, nonce, node UUID and IP address in a local json file
    * Returns the nonce and the AES-256-GCM key to the Talos node in the response
4. The Talos node receives the response, stores the nonce and AES-256-GCM key locally
5. When needed, the talos node sends an "unseal" request to the KMS server with its node UUID, AES-256-GCM key and nonce
6. The KMS server:
    * Looks up the encrypted lvm key in the local json file using the nonce
    * Configures GCM to use the node UUID and IP address as AAD
    * Decrypts the lvm key using AES-256-GCM with the provided key, nonce and AAD
        * If the lvm key or nonce is invalid, decryption will fail
        * If the connecting IP address does not match the stored IP address, decryption will fail
        * If the node UUID does not match the stored node UUID, decryption will fail
        * If decryption fails, the KMS server will return an incorrect lvm key to the Talos node, this is to avoid leaking information about the failure
    * Returns the decrypted lvm key to the Talos node in the response
7. The Talos node receives the decrypted lvm key and uses it to unlock the disk


## Security

* The lvm keys are encrypted using AES-256-GCM
* Each volume get an individual AES-256-GCM key
* The encrypted lvm keys are stored in a local json file, ideally on an encrypted filesystem
* TLS support (TLSv1.3+ only)

### For extra security
    
* Only run the KMS server when starting talos nodes
* Encrypt the filesystem where the KMS server stores the encrypted lvm keys
* Use a firewall to only allow access to the KMS server from the Talos nodes

### Attack Vectors

#### Node Access
If an attacker can control either the node or a backup of the node, spin it up in the correct network with the correct IP address, they can retrieve the lvm key from the KMS server.

#### Man in the middle
If an attacker can intercept the communication between the Talos node and the KMS server, using a by talos node trusted certificate, they can retrieve the lvm key.
The attacker would then need to get access to the Talos node or a backup of it to use the lvm key.

#### Backup of the KMS server
If an attacker can get access to a backup of the unencrypted filesystem of the KMS server, or the database file but can't facilitate a man in the middle attack, they would have to brute force all the individual AES-256-GCM keys to decrypt the lvm keys.


## Requirements

* Static IP addresses of the talos nodes
    * Or decryption will fail
* TLS certificate trusted by the talos nodes
    * Self-signed will not work, ideally use a certificate from a trusted CA or use ACME
        * See: [Disk Encryption](https://www.talos.dev/v1.11/talos-guides/configuration/disk-encryption/)
    * A matching DNS name for the KMS server
* Network access from the talos nodes to the KMS server
* A linux system capable of installing .deb files
  * deb files for amd64 and arm64 are provided
  * Systemd configuration is provided


## Installation

1. Download the latest .deb file from the [releases](https://github.com/flip-flop-foundry/talos-kms-sever/releases)
   * Make sure to pick the correct architecture
2. Install the .deb file using `sudo dpkg -i talos-kms-server-XXXX.deb`
* Add Acme documentation