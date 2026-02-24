#!/bin/bash

ES_VERSION="${ELASTICSEARCH_VERSION:-8.19.8}"
DATASHARE_HOME="${DATASHARE_HOME:-$HOME/.local/share/datashare}"
ES_HOME="$DATASHARE_HOME/elasticsearch"

# from https://github.com/ICIJ/datashare/issues/2017#issuecomment-3952758808
ES_MODULES="
aggregations
analysis-common
apm
constant-keyword
ingest-attachment
ingest-common
ingest-geoip
ingest-user-agent
lang-painless
parent-join
reindex
rest-root
transport-netty4
x-pack-core
x-pack-geoip-enterprise-downloader
x-pack-security
"

function detect_platform() {
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m)

    if [ "$OS" = "linux" ]; then
        ES_OS="linux"
    elif [ "$OS" = "darwin" ]; then
        ES_OS="darwin"
    else
        echo "ERROR: Unsupported operating system: $OS"
        exit 1
    fi

    if [ "$ARCH" = "x86_64" ] || [ "$ARCH" = "amd64" ]; then
        ES_ARCH="x86_64"
    elif [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
        ES_ARCH="aarch64"
    else
        echo "ERROR: Unsupported architecture: $ARCH"
        exit 1
    fi

    ES_ARCHIVE="elasticsearch-${ES_VERSION}-${ES_OS}-${ES_ARCH}.tar.gz"
    ES_DOWNLOAD_URL="https://artifacts.elastic.co/downloads/elasticsearch/${ES_ARCHIVE}"
    echo "Detected platform: $ES_OS-$ES_ARCH"
}

function check_elasticsearch_installed() {
    if [ -d "$ES_HOME/elasticsearch-$ES_VERSION" ]; then
        return 0
    else
        return 1
    fi
}

download_elasticsearch() {
    echo "Downloading Elasticsearch $ES_VERSION from $ES_DOWNLOAD_URL..."
    mkdir -p "$ES_HOME"
    cd "$ES_HOME"

    if [ -f "$ES_ARCHIVE" ]; then
        echo "Archive already downloaded, skipping"
        return
    fi

    if command -v curl >/dev/null 2>&1; then
        if ! curl -s -L -o -f "$ES_ARCHIVE" "$ES_DOWNLOAD_URL"; then
          echo "Error during download elasticsearch with curl"
          exit 1
        fi
    elif command -v wget >/dev/null 2>&1; then
       if ! wget -q -O "$ES_ARCHIVE" "$ES_DOWNLOAD_URL"; then
         echo "Error during download elasticsearch with wget"
         exit 1
       fi
    else
        echo "ERROR: Neither curl nor wget found. Please install one."
        exit 1
    fi
    echo "Download complete"
}

extract_elasticsearch() {
    echo "Extracting Elasticsearch..."
    cd "$ES_HOME"

    if [ -d "elasticsearch-$ES_VERSION" ]; then
        echo "Removing old installation..."
        rm -rf "elasticsearch-$ES_VERSION"
    fi

    tar -xzf "$ES_ARCHIVE"
    ln -sfn "elasticsearch-$ES_VERSION" "$ES_HOME/current"
    echo "Extraction complete"
    rm -f "$ES_ARCHIVE"
}

setup_elasticsearch() {
    detect_platform
    if check_elasticsearch_installed; then
        echo "Elasticsearch $ES_VERSION already installed at $ES_HOME"
        echo "Skipping download and extraction"
        return
    fi
    download_elasticsearch
    extract_elasticsearch
    echo "Removing Elasticsearch unnecessary modules in $ES_HOME/current/modules"
    for dir in $(echo $ES_MODULES| xargs printf -- '-I %s\n' | xargs ls $ES_HOME/current/modules) ;
    do
      echo "Removing $ES_HOME/current/modules/$dir"
      rm -rf "$ES_HOME/current/modules/$dir"
    done
    echo "Elasticsearch $ES_VERSION installed successfully at $ES_HOME"
    echo "Installation complete"
    echo "Note: Configuration will be handled by Datashare at runtime"
}

main() {
    CMD="${1:-install}"
    if [ "$CMD" = "install" ]; then
        setup_elasticsearch
    elif [ "$CMD" = "check" ]; then
        detect_platform
        if check_elasticsearch_installed; then
            echo "Elasticsearch $ES_VERSION is installed at $ES_HOME"
            exit 0
        else
            echo "Elasticsearch $ES_VERSION is not installed"
            exit 1
        fi
    else
        echo "Usage: $0 {install|check}"
        echo ""
        echo "Commands:"
        echo "  install - Download and extract Elasticsearch"
        echo "  check   - Check if Elasticsearch is installed"
        exit 1
    fi
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    main "$@"
fi
