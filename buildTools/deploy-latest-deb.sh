#!/bin/bash
set -e

# Intended for manual testing of install on linux
# Usage: ./deploy-latest-deb.sh <remote_user> <remote_host> <ssh_key_file> [remote_path]
# Example: ./deploy-latest-deb.sh ubuntu 192.168.1.100 ~/.ssh/id_rsa


REMOTE_USER="$1"
REMOTE_HOST="$2"
SSH_KEY_FILE="$3"
REMOTE_PATH="${4:-/tmp}"


#REMOTE_USER="$(whoami)"
#REMOTE_HOST=""
#SSH_KEY_FILE="~/.ssh/talos-kms-dev"

if [ -z "$REMOTE_USER" ] || [ -z "$REMOTE_HOST" ] || [ -z "$SSH_KEY_FILE" ]; then
    echo "Usage: $0 <remote_user> <remote_host> <ssh_key_file> [remote_path]" >&2
    exit 1
fi

OUTPUT_DIR="../target/deb-work/output"
LATEST_DEB=$(ls -t "$OUTPUT_DIR"/*_amd64.deb 2>/dev/null | head -n1)

if [ ! -f "$LATEST_DEB" ]; then
    echo "Error: No amd64 .deb file found in $OUTPUT_DIR" >&2
    exit 1
fi

DEB_BASENAME=$(basename "$LATEST_DEB")

# Copy the .deb file to the remote server
scp -i "$SSH_KEY_FILE" "$LATEST_DEB" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_PATH/"



# SSH into the server to uninstall and install the package
ssh -i "$SSH_KEY_FILE" "$REMOTE_USER@$REMOTE_HOST" bash -c "'
set -e
set -x
cd $REMOTE_PATH


# Extract package name from the .deb file
PKG_NAME=\$(dpkg-deb -f "$REMOTE_PATH/$DEB_BASENAME" Package)

if [ -z "\$PKG_NAME" ]; then
    echo "Error: Could not determine package name from $REMOTE_PATH/$DEB_BASENAME" >&2
    exit 1
else
    echo "Package name determined: \$PKG_NAME"
fi

if dpkg -l | grep -q "^ii  \$PKG_NAME "; then
    echo "Uninstalling existing package: \$PKG_NAME"
    sudo dpkg -r \$PKG_NAME

fi

sudo rm -rf /opt/talos-kms-server
sudo mkdir -p /opt/talos-kms-server
cd /opt/talos-kms-server

# Generate a private key (PKCS#1)
sudo openssl genrsa -out kms.flipflopforge.dev.key 2048

# Generate a certificate signing request (CSR)
sudo openssl req -new -key kms.flipflopforge.dev.key -out kms.flipflopforge.dev.csr -subj \"/CN=kms.flipflopforge.dev\"

# Generate a self-signed certificate
sudo openssl x509 -req -days 365 -in kms.flipflopforge.dev.csr -signkey kms.flipflopforge.dev.key -out server.crt

# Convert the private key to PKCS#8 format, encrypted with password 'changeit'
sudo openssl pkcs8 -topk8 -inform PEM -outform PEM -in kms.flipflopforge.dev.key -out server.key -passout pass:changeit
sudo chown -R talos-kms:talos-kms /opt/talos-kms-server

cd $REMOTE_PATH

echo "Installing new package: $DEB_BASENAME"
sudo dpkg -i $DEB_BASENAME
'"

echo "Deployment complete."

