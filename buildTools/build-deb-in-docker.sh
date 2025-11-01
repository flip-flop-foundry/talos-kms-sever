#!/bin/bash

# Builds the main jar, builds a shaded version of the jar
# Uses shaded jar to build two deb package versions (amd64 and arm64) in target/deb-work/output

set -e

echo "Starting build of .deb packages..."

# Color definitions
RED='\033[1;31m'
GREEN='\033[1;32m'
CYAN='\033[1;36m'
NC='\033[0m' # No Color


BUILD_FOR_ARCHS=("linux/amd64" "linux/arm64")

MAIN_CLASS="dev.flipflopfoundy.taloskms.KMSServer"

echo -e "${CYAN}Building $APP_NAME version: $APP_VERSION${NC}"

# Directory structure
BUILD_DIR="target/deb-work"
INSTALL_DIR="/opt/$APP_NAME"
CONFIG_DIR="/etc/$APP_NAME"
WORK_DIR="$INSTALL_DIR"


echo -e "${CYAN}Building fat JAR...${NC}"
cd ../
mvn clean package


# Build the fat JAR
echo -e "${CYAN}Building fat JAR...${NC}"
cd ../
mvn clean package


# Clean and create working directory
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$BUILD_DIR/input"
mkdir -p "$BUILD_DIR/resources"
mkdir -p "$BUILD_DIR/output"


# Copy JAR to input directory
JAR_NAME="$APP_NAME-$MAVEN_VERSION.jar"
if [ ! -f "target/$JAR_NAME" ]; then
    echo "Error: JAR file not found at target/$JAR_NAME" >&2
    exit 1
fi
cp target/"$JAR_NAME" "$BUILD_DIR/input/"


#cp -r buildTools/debBuildResources/* "$WORK_DIR/resources/"

# Create systemd service file
cat > "$BUILD_DIR/resources/$APP_NAME.service" << EOF
[Unit]
Description=Talos KMS Server
After=network.target

[Service]
Type=simple
User=talos-kms
Group=talos-kms
ExecStart=$INSTALL_DIR/bin/$APP_NAME --config $CONFIG_DIR/config.yaml
Restart=always
WorkingDirectory=$WORK_DIR

[Install]
WantedBy=multi-user.target
EOF

# Create post-install script
cat > "$BUILD_DIR/resources/postinst" << EOF
#!/bin/sh
set -e

# Create user and group
if ! getent group talos-kms >/dev/null; then
    groupadd -r talos-kms
fi
if ! getent passwd talos-kms >/dev/null; then
    useradd -r -g talos-kms -d /opt/talos-kms-server -s /sbin/nologin talos-kms
fi

chown -R talos-kms:talos-kms "$INSTALL_DIR"

mkdir -p "$CONFIG_DIR"
chown -R talos-kms:talos-kms "$CONFIG_DIR"
chmod -R 750 "$CONFIG_DIR"


# Check if systemctl is available
if which systemctl >/dev/null 2>&1; then
    # System uses systemd
    echo "Configuring systemd service..."
    cp /opt/$APP_NAME/lib/$APP_NAME.service /etc/systemd/system/$APP_NAME.service
    systemctl daemon-reload
    systemctl enable $APP_NAME
    systemctl start $APP_NAME || echo "Warning: Could not start $APP_NAME service automatically"
else
    echo "System does not use systemd (systemctl not found). Please start the service manually."
fi

exit 0
EOF

chmod +x "$BUILD_DIR/resources/postinst"

# Create pre-uninstall script
cat > "$BUILD_DIR/resources/prerm" << EOF
#!/bin/sh
set -e

# Check if systemctl is available
if which systemctl >/dev/null 2>&1; then
    # System uses systemd
    echo "Stopping and disabling systemd service..."
    systemctl stop $APP_NAME || true
    systemctl disable $APP_NAME || true
else
    echo "System does not use systemd (systemctl not found). No service configuration to remove."
fi

exit 0
EOF

chmod +x "$BUILD_DIR/resources/prerm"


# Copy acmeReload.sh to resources and make it executable
cp "$BUILD_TOOLS_DIR/debBuildResources/acmeReload.sh" "$BUILD_DIR/resources/"
chmod +x "$BUILD_DIR/resources/acmeReload.sh"

# Create the .deb package
cat > "$BUILD_DIR/jpackage.sh" << EOF
echo "Creating .deb package..."
set -x
jpackage \
  --type deb \
  --name "${APP_NAME}" \
  --app-version "$APP_VERSION" \
  --vendor "$VENDOR" \
  --description "$DESCRIPTION" \
  --input "/build/input" \
  --dest "/build/output"  \
  --main-jar "/build/input/${JAR_NAME}" \
  --main-class "$MAIN_CLASS" \
  --resource-dir /build/resources \
  --linux-app-release 1 \
  --linux-deb-maintainer "$MAINTAINER_EMAIL" \
  --linux-package-name "$APP_NAME" \
  --install-dir "/opt/" \
  --icon "" \
  --app-content "/build/resources/$APP_NAME.service" \
  --app-content "/build/resources/acmeReload.sh" \
  --verbose

EOF
#   --launcher-as-service \
#   --add-launcher "${APP_NAME}_bengt=/opt/$APP_NAME/bin/$APP_NAME.properties" \

chmod +x "$BUILD_DIR/jpackage.sh"



echo -e "${CYAN}Building .deb packages in Docker...${NC}"

#Track and then log how long time it takes to build all architectures
START_TIME=$(date +%s)
for arch in "${BUILD_FOR_ARCHS[@]}"; do
    echo -e "${CYAN}   Building for architecture: $arch${NC}"

    docker run --rm \
        -v "./$BUILD_DIR:/build" \
        --platform "$arch" \
        "$DOCKER_BUILD_IMAGE" \
        /bin/bash -c "cd /build && bash jpackage.sh"
done

END_TIME=$(date +%s)
ELAPSED_TIME=$((END_TIME - START_TIME))
echo -e "${GREEN}Build completed in $ELAPSED_TIME seconds.${NC}"

echo -e "${GREEN}All done! .deb packages are in $BUILD_DIR/output${NC}"
ls -lh "$BUILD_DIR/output"
echo -e "${NC}"
exit 0
