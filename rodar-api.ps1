# Sobe a API em http://localhost:8080 (documentação interativa: /swagger-ui.html).
# Banco H2 e cópias dos PDFs ficam em .\dados\ desta pasta.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
. (Join-Path $PSScriptRoot "java-home.ps1")
if (-not (Test-Path "target\concursos-tecnicos.jar")) {
    .\mvnw.cmd -q -B -DskipTests package
}
& $java -jar target\concursos-tecnicos.jar @args
