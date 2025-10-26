# Talos KMS Server

A simple KMS server for disk encryption in Talos clusters, intended to be run as Linux systemd service.

## Alternatives to KMS

Talos also supports storing the disk encryption keys in TPM or as STATIC.

Static keys are the least secure option, the keys for the STATE partition are stored in clear text both in the Talos machine config and on the META partition. So an attacker that has access to either the Talos machine config or the META partition can retrieve the keys.

TPM is in theory more secure, but if you aren't running TALOS on bare metal, you are likely running it in a VM, and the security of the TPM is then dependent on the hypervisor. If an attacker can get access to the hypervisor, they can retrieve the keys from the TPM. In my case I run Talos on Proxmox, and Proxmox doest not by default store the TPM data encrypted, so an attacker with access to the Proxmox host or a backup of the Talos VMS can potentially retrieve the keys.


## Features

* Supported by [Talos Disk Encryption](https://www.talos.dev/v1.11/talos-guides/configuration/disk-encryption/)
* Works for State, Ephemeral and [UserVolumes](https://www.talos.dev/v1.11/talos-guides/configuration/disk-management/user/)

## Security

* The lvm keys are encrypted using AES-256-GCM
* Each volume get an individual AES-256-GCM key
* The node UUID and IP address are used as additional authenticated data (AAD) during encryption
  * This helps ensure that the encrypted lvm key can only be decrypted by the same node
* The encrypted lvm keys are stored in a local json file, ideally on an encrypted filesystem
* Communication with the Talos node is done using TLSv1.3+ only

### For extra security

For extra security, be sure to follow these guidelines

* Only run the KMS server when starting talos nodes, then stop the service
* Encrypt the filesystem where the KMS server stores the encrypted lvm keys
* Use a firewall to only allow access to the KMS server from the Talos nodes


## Basic Operation

1. The Talos node boots for the first time, determines that a disk needs to be encrypted
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
5. The Talos node encrypts the disk using the lvm key, and "forgets" the key
6. When needed, the talos node sends an "unseal" request to the KMS server with its node UUID, AES-256-GCM key and nonce
7. The KMS server:
    * Looks up the encrypted lvm key in the local json file using the nonce
    * Configures GCM to use the node UUID and the request IP address as AAD
    * Decrypts the lvm key using AES-256-GCM with the provided key, nonce and AAD
        * If the lvm key or nonce is invalid, decryption will fail
        * If the connecting IP address does not match the original connecting IP address, decryption will fail
        * If the node UUID does not match the original connecting node UUID, decryption will fail
        * If decryption fails, the KMS server will return a random incorrect lvm key to the Talos node, this is to avoid leaking information about the failure
    * Returns the decrypted lvm key to the Talos node in the response
8. The Talos node receives the decrypted lvm key and uses it to unlock the disk



### Attack Vectors

#### Node Access
If an attacker can control either the node or a backup of the node, spin it up in the correct network with the correct IP address, they can retrieve the lvm key from the KMS server.

#### Man in the middle
If an attacker can intercept the communication between the Talos node and the KMS server, using a by talos node trusted certificate, they can retrieve the lvm key.
After that, an attacker would then need to get access to the Talos node or a backup of it to use the lvm key.

#### Backup of the KMS server
If an attacker can get access to a backup of the unencrypted filesystem of the KMS server, or the database file but can't facilitate a man in the middle attack, they would have to brute force all the individual AES-256-GCM keys to decrypt the lvm keys.


## Requirements

* Static IP addresses of the talos nodes
    * During the decryption, the requesting IP is verified
* TLS certificate trusted by the talos nodes
    * **NOTE Self-signed will not work**, ideally use a certificate from a trusted CA or use ACME
        * See: [Disk Encryption](https://www.talos.dev/v1.11/talos-guides/configuration/disk-encryption/)
    * A matching DNS name for the KMS server
* Network access from the talos nodes to the KMS server
* A linux system capable of installing .deb files
  * deb files for amd64 and arm64 are provided
  * Systemd configuration is provided and setup during installation


## Installation

1. Download the latest .deb file from the [releases](https://github.com/flip-flop-foundry/talos-kms-server/releases)
   * Make sure to pick the correct architecture
2. Install the .deb file using `sudo dpkg -i talos-kms-server-XXXX.deb`
3. If your system has systemd, a service has been installed and enabled
   * The service is called `talos-kms-server`
   * The service will start automatically on boot, but will fail if not configured
4. Configure the KMS server, see [Configuration](#configuration)

### Configuration

If your system has systemd, the configuration file is located at `/etc/talos-kms-server/config.yaml`, a default one will have been created during installation, and the service will fail to start until configured.

If you are not using systemd you can create your own configuration file, and start the server manually using `/opt/talos-kms-server/bin/talos-kms-server --config /etc/talos-kms-server/config.yaml`


Example configuration file:

```yaml
nodeDbFile: "/etc/talos-kms-server/nodeDbFile.json"
serverCertFile: "/etc/talos-kms-server/server.crt" #Must be a PEM encoded certificate, trusted by the talos nodes
serverKeyFile: "/etc/talos-kms-server/server.key" #Must be a pkcs8 key
serverKeyPassword: "changeit" # The password for decrypting the pkcs8 key
port: 50051 # The port that the KMS server will listen to
bindAddress: "0.0.0.0"
kmsLogLevel: "INFO" # Log level for KMS specific loggers
rootLogLevel: "WARNING" # Log level for all Loggers used by KMS
```

Once configured, start the service using `sudo systemctl restart talos-kms-server`


### Troubleshooting

* Check the output of `systemctl status talos-kms-server`
* Read the complete logs using `journalctl -u talos-kms-server`
* Follow the logs using `journalctl -u talos-kms-server -f`
* Note that you can change the log level in the config file to get more or less verbose logging

### Backup

Make sure to backup the `nodeDbFile` specified in the config file, as it contains all the encrypted lvm keys needed to unlock your talos nodes disks.

**If KMS is the only LVM key you have set up, losing this file means losing access to your disks.**

### Setup ACME Certs for TLS

If you want to use ACME/LetsEncrypt to get a trusted TLS certificate for your KMS server, you can use [acme.sh](https://github.com/acmesh-official/acme.sh) to obtain and renew the certificate. Using the DNS challenge is recommended, as the KMS server **SHOULD NEVER** be exposed to the internet.

The deb file comes with a script to reload the KMS server when the certificate is renewed, located at `/opt/talos-kms-server/lib/acmeReload.sh`. If you keep the default settings in the config file you don't need to edit it. But if you do edit it make sure to move it to another location, or it will be overwritten during upgrades, and update the --reloadcmd in the acme.sh command below.

Example using Cloudflare DNS challenge:

```shell
#Install Acme.sh
curl https://get.acme.sh | sh

export CF_Token="YOUR_CLOUDFLARE_API_TOKEN"
export CF_Zone_ID="YOUR_CLOUDFLARE_ZONE_ID"
export YOUR_DOMAIN="kms.example.com"
acme.sh --register-account -m your@mail.com

acme.sh --issue --dns dns_cf -d "$YOUR_DOMAIN"

acme.sh --install-cert -d "$YOUR_DOMAIN" \
  --fullchain-file  /etc/talos-kms-server/server.crt \
  --key-file /etc/talos-kms-server/server.key.pem \
  --reloadcmd "/opt/talos-kms-server/lib/acmeReload.sh"


```
## Setting Up Talos To Use KMS

Always refer to Talos own documentation, this is just example config

```yaml
machine:
    systemDiskEncryption:
        # State partition encryption.
        state:
            provider: luks2 # Encryption provider to use for the encryption.
            # Defines the encryption keys generation and storage method.
            keys:
                - # KMS managed encryption key.
                  kms:
                    endpoint: https://kms.exmaple.com:50051 # KMS endpoint to Seal/Unseal the key.
                  slot: 0 # Key slot number for LUKS2 encryption.
        ephemeral:
          provider: luks2 # Encryption provider to use for the encryption.
          # Defines the encryption keys generation and storage method.
          keys:
            - # KMS managed encryption key.
              kms:
                endpoint: https://kms.exmaple.com:50051 # KMS endpoint to Seal/Unseal the key.
              slot: 0 # Key slot number for LUKS2 encryption.


#Add to end of controlplane.yaml/worker.yaml
---
apiVersion: v1alpha1
kind: UserVolumeConfig
name: hddVolume

provisioning:
  diskSelector:
    match: disk.dev_path == "/dev/sdb" && disk.rotational == true
  grow: true
  minSize: 10Gi
encryption:
  provider: luks2
  keys:
    - slot: 0
      kms:
        endpoint: https://kms.example.com:50051


```

## nodeDbFile.json explained

The database file used by the kms server contains an array of JSON objects that look like this:

```json

{
  "nodeUuid" : "213faf71-17a0-43d7-a88b-0afd96094178",
  "ipAddress" : "10.0.0.201",
  "cipherTextB64" : "CZVlmwwkZcUjNd0JBcqWqR6BxGh3iGerVwYg6B6gYPpIDQvoIpxHj0nnHiEA0X8n",
  "nonceB64" : "Lh1oTReoyx4mI3Y8",
  "lastAccess" : "2025-10-11 20:22:24",
  "createdAt" : "2025-10-11 20:22:17"
}

```

* **nodeUuid:** A unique ID that each talos node generates
* **ipAddress:** The ip address used during the initial seal request
  * This is here mainly to make the JSON more human friendly, this IP is **NOT** used to validate that the unseal request is from the original host, that is baked in to the encryption key. Changing this IP will **NOT** fix decryption problems if your node has a new IP. 
* **cipherTextB64:** A base64 representation of the encrypted lvm key, ip and UUID
* **nonceB64:** A base64 representation of the nonce used during the encryption
* **lastAccess:** The last time this secret was accessed, regardless if it was a successful unseal or not
  * Intended to help you clean up old and stale keys you don't need anymore
* **created:** Timestamp of when the key/seal was created