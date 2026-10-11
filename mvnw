#!/bin/sh
set -eu

MAVEN_VERSION="3.9.11"
WRAPPER_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/.mvn/wrapper"
MAVEN_HOME="$WRAPPER_DIR/apache-maven-$MAVEN_VERSION"
MAVEN_ARCHIVE="$WRAPPER_DIR/apache-maven-$MAVEN_VERSION-bin.tar.gz"
MAVEN_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.tar.gz"

if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$WRAPPER_DIR"
  if [ ! -f "$MAVEN_ARCHIVE" ]; then
    curl --fail --location --silent --show-error "$MAVEN_URL" --output "$MAVEN_ARCHIVE"
  fi
  tar -xzf "$MAVEN_ARCHIVE" -C "$WRAPPER_DIR"
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
