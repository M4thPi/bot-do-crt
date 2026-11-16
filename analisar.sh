#!/bin/sh
# Analisa um edital sem subir a API (Mac e Linux; no Windows, use analisar.ps1).
#   ./analisar.sh ~/editais/edital.pdf
#   ./analisar.sh https://site-oficial/edital.pdf --saida relatorio.md
#   ./analisar.sh edital.pdf --oficial --json
set -e
pasta="$(cd "$(dirname "$0")" && pwd)"
. "$pasta/java-home.sh"
if [ ! -f "$pasta/target/concursos-tecnicos.jar" ]; then
    (cd "$pasta" && ./mvnw -q -B -DskipTests package)
fi
exec "$JAVA" -jar "$pasta/target/concursos-tecnicos.jar" analisar "$@"
