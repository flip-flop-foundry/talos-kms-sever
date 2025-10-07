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

### Alternatives to KMS

Talos also supports storing the disk encryption keys in TPM or as STATIC. 

Static keys are the least secure option, the keys for the STATE partition are stored in clear text both in the Talos config and on the META partition. So an atacker that has access to either the Talos config or the META partition can retrieve the keys.

TPM is in theory more secure, but if you aren't running TALOS on bare metal, you are likely running it in a VM, and the security of the TPM is then dependent on the hypervisor. If an attacker can get access to the hypervisor, they can retrieve the keys from the TPM. In my case I run Talos on Proxmox, and Proxmox doest not by default store the TPM data encrypted, so an attacker with access to the Proxmox host can potentially retrieve the keys.

## Requirements

* Static IP addresses of the talos nodes
    * Or decryption will fail
* TLS certificate trusted by the talos nodes
    * **NOTE Self-signed will not work**, ideally use a certificate from a trusted CA or use ACME
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
3. If your system has systemd, a service has been installed and enabled
   * The service is called `talos-kms-server`
   * The service will start automatically on boot, but will fail if not configured
4. Configure the KMS server, see [Configuration](#configuration)

### Configuration

If your system has systemd, the configuration file is located at `/opt/talos-kms-server/config.yaml`, a default one will have been created during installation, and the service will fail to start until configured.

If you are not using systemd you can create your own configuration file, and start the server manually using `/opt/talos-kms-server/bin/talos-kms-server --config /opt/talos-kms-server/config.yaml`


Example configuration file:

```yaml
nodeDbFile: "/opt/talos-kms-server/nodeDbFile.json"
serverCertFile: "/opt/talos-kms-server/server.crt" #Must be a PEM encoded certificate, trusted by the talos nodes
serverKeyFile: "/opt/talos-kms-server/server.key" #Must be a pkcs8 key
serverKeyPassword: "changeit"
port: 50051
bindAddress: "0.0.0.0"
kmsLogLevel: "INFO"
rootLogLevel: "WARNING"
```

Once configured, start the service using `sudo systemctl restart talos-kms-server`


### Troubleshooting

* Check the output of `systemctl status talos-kms-server`
* Read the complete logs using `journalctl -u talos-kms-server`
* Follow the logs using `journalctl -u talos-kms-server -f`



### Setup ACME Certs for TLS

If you want to use ACME/LetsEncrypt to get a trusted TLS certificate, you can use [acme.sh](https://github.com/acmesh-official/acme.sh) to obtain and renew the certificate. Using the DNS challenge is recommended, as the KMS server SHOULD NEVER be exposed to the internet.

The deb file comes with a script to reload the KMS server when the certificate is renewed, located at `/opt/talos-kms-server/lib/acmeReload.sh`. If you keep the default settings in the config file you dont need to edit it. But if you do edit it make sure to move it to another location, or it will be overwritten during upgrades, and update the --reloadcmd in the acme.sh command below.

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
  --cert-file /opt/talos-kms-server/server.crt \
  --key-file /opt/talos-kms-server/server.key.pem \
  --reloadcmd "/opt/talos-kms-server/lib/acmeReload.sh"


```

TPE, STATIC VS KMS