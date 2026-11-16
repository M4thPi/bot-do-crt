#!/bin/sh
# Sobe a API em http://localhost:8080 (Mac e Linux; no Windows, use rodar-api.ps1).
# Banco H2 e cópias dos PDFs ficam em ./dados/ desta pasta.
set -e
cd "$(dirname "$0")"
. ./java-home.sh
if [ ! -f target/concursos-tecnicos.jar ]; then
    ./mvnw -q -B -DskipTests package
fi
exec "$JAVA" -jar target/concursos-tecnicos.jar "$@"
