# Analisa um edital sem subir a API.
#   .\analisar.ps1 C:\editais\edital.pdf
#   .\analisar.ps1 https://site-oficial/edital.pdf --saida relatorio.md
#   .\analisar.ps1 edital.pdf --oficial --json
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "java-home.ps1")
$jar = Join-Path $PSScriptRoot "target\concursos-tecnicos.jar"
if (-not (Test-Path $jar)) {
    Push-Location $PSScriptRoot
    .\mvnw.cmd -q -B -DskipTests package
    Pop-Location
}
& $java -jar $jar analisar @args
