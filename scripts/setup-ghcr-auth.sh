#!/bin/bash
# Setup GitHub Container Registry authentication
# Run this script on your server to authenticate with ghcr.io

echo "Enter your GitHub Personal Access Token (with read:packages scope):"
read -s GITHUB_TOKEN
echo ""

if [ -z "$GITHUB_TOKEN" ]; then
    echo "Error: Token cannot be empty"
    exit 1
fi

echo "$GITHUB_TOKEN" | docker login ghcr.io -u viewrr --password-stdin

if [ $? -eq 0 ]; then
    echo "Successfully authenticated with ghcr.io"
else
    echo "Failed to authenticate. Please check your token."
    exit 1
fi
