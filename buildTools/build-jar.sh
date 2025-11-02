
set -e

echo "Starting build of .deb packages..."

# Color definitions
CYAN='\033[1;36m'
NC='\033[0m' # No Color


DOCKER_BUILD_IMAGE="ghcr.io/flip-flop-foundry/talos-kms-builder:latest"



echo -e "${CYAN}Building $APP_NAME version: $MAVEN_VERSION${NC}"

# Directory structure
JAR_BUILD_DIR="target/jar-build/output"

# Build the fat JAR
echo -e "${CYAN}Building fat JAR...${NC}"
echo "In current dir: $(pwd)"
ls -l
#cd ../

docker run --rm \
        -v "$PWD":/workspace -w /workspace \
        -v "$HOME/.m2":/root/.m2 \
        "$DOCKER_BUILD_IMAGE" \
        /bin/bash -c "mvn clean package"

sudo chown -R "$(id -u):$(id -g)" .

JAR_NAME="$APP_NAME-$MAVEN_VERSION.jar"
if [ ! -f "target/$JAR_NAME" ]; then
    echo "Error: JAR file not found at target/$JAR_NAME" >&2
    exit 1
fi

mkdir -p "$JAR_BUILD_DIR"
cp target/"$JAR_NAME" "$JAR_BUILD_DIR/"

echo "jar_name=$JAR_NAME" >> "$GITHUB_OUTPUT"
echo "jar_path=$JAR_BUILD_DIR/$JAR_NAME" >> "$GITHUB_OUTPUT"

echo "Finished building JAR file: $JAR_NAME"
ls -lh "$JAR_BUILD_DIR/"