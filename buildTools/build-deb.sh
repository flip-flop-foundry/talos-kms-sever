#!/bin/bash



# Color definitions
RED='\033[1;31m'
GREEN='\033[1;32m'
CYAN='\033[1;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}Starting build of .deb packages...${NC}"

MAIN_CLASS="dev.flipflopfoundy.taloskms.KMSServer"

DEB_BUILD_DIR="target/deb-work"
BUILD_TOOLS_DIR="buildTools/"
INSTALL_DIR="/opt/$APP_NAME"
CONFIG_DIR="/etc/$APP_NAME"
WORK_DIR="$INSTALL_DIR"

# Clean and create working directory
rm -rf "$DEB_BUILD_DIR"
mkdir -p "$DEB_BUILD_DIR"
mkdir -p "$DEB_BUILD_DIR/input"
mkdir -p "$DEB_BUILD_DIR/resources"
mkdir -p "$DEB_BUILD_DIR/output"