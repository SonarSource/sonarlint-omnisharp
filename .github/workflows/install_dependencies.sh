#!/bin/bash
set -euo pipefail

sudo apt-get update && \
  sudo apt-get install -y ca-certificates gnupg && \
  gpg --homedir /tmp --no-default-keyring --keyring /usr/share/keyrings/mono-official-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys 3FA7E0328081BFF6A14DA29AA6A19B38D3D831EF && \
  sudo sh -c 'echo "deb [signed-by=/usr/share/keyrings/mono-official-archive-keyring.gpg] https://download.mono-project.com/repo/ubuntu stable-focal main" > /etc/apt/sources.list.d/mono-official-stable.list' && \
  sudo apt-get update && \
  sudo apt-get install -y \
    mono-complete \
    mono-roslyn \
    msbuild
