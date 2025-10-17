#!/bin/bash

#A script to be run after acme.sh has renewed the certificate
# It converts the new PEM key to PKCS#8 format and restarts the talos-kms-server service if it's running

set -e
# Path to config file (edit if needed)
CONFIG_FILE="/etc/talos-kms-server/config.yaml"
ACME_KEY_FILE="/etc/talos-kms-server/server.key.pem"
TALOS_USER="talos-kms"
TALOS_GROUP="talos-kms"

# Read values from YAML config
serverKeyFile=$(grep '^serverKeyFile:' "$CONFIG_FILE" | sed 's/serverKeyFile:[ ]*"\?//;s/"\?$//')
serverKeyPassword=$(grep '^serverKeyPassword:' "$CONFIG_FILE" | sed 's/serverKeyPassword:[ ]*"\?//;s/"\?$//')

if [ -z "$serverKeyFile" ]; then
  echo "Error: serverKeyFile not found in $CONFIG_FILE" >&2
  exit 1
fi
if [ -z "$serverKeyPassword" ]; then
  echo "Error: serverKeyPassword not found in $CONFIG_FILE" >&2
  exit 1
fi


#Delete existing serverKeyFile if it exists
rm -f "$serverKeyFile"
# Convert PEM key to PKCS#8 with password
echo "Converting $ACME_KEY_FILE to PKCS#8 format at $serverKeyFile..."
openssl pkcs8 -topk8 -inform PEM -outform PEM -in "$ACME_KEY_FILE" -out "$serverKeyFile" -passout pass:"$serverKeyPassword"
chown "$TALOS_USER:$TALOS_GROUP" "$serverKeyFile"
chmod 640 "$serverKeyFile"

# Check if talos-kms-server systemd service is running
if systemctl is-active --quiet talos-kms-server; then
  echo "talos-kms-server is running. Restarting service..."
  sudo systemctl restart talos-kms-server
else
  echo "talos-kms-server is not running. No restart performed."
fi

