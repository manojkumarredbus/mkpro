#!/bin/bash

# Resolve symlink so running via /usr/local/bin/mkpro still points to project dir
SOURCE="${BASH_SOURCE[0]}"
while [ -L "$SOURCE" ]; do
  SCRIPT_DIR="$( cd -P "$( dirname "$SOURCE" )" &> /dev/null && pwd )"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$SCRIPT_DIR/$SOURCE"
done
SCRIPT_DIR="$( cd -P "$( dirname "$SOURCE" )" &> /dev/null && pwd )"

# Source env vars from .env if present (e.g. GOOGLE_API_KEY, AWS_BEARER_TOKEN_BEDROCK, BEDROCK_URL)
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    source "$SCRIPT_DIR/.env"
    set +a
fi

# Define the path to the shaded JAR

export JDK_JAVA_OPTIONS="--add-modules jdk.incubator.vector --enable-preview"
JAR_PATH="$SCRIPT_DIR/target/mkpro-4.5.0.jar"

# Check if the JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: mkpro JAR not found at $JAR_PATH"
    echo "Please run 'mvn package -DskipTests' first."
    exit 1
fi

# Run the application
java -Dmkpro.db.name=$(basename "$0" .sh) -jar "$JAR_PATH" "$@"
