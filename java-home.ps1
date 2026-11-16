# O projeto compila para Java 25. Usa JAVA_HOME se apontar para um JDK 25+;
# senão procura um JDK 25 em %LOCALAPPDATA%\jdks. Define $java e JAVA_HOME (usado pelo mvnw).
function Get-VersaoJava([string]$javaExe) {
    # "java -version" escreve no stderr; no PowerShell 5.1 isso vira erro se a preferência for Stop.
    $ErrorActionPreference = "Continue"
    $saida = & $javaExe -version 2>&1 | Select-Object -First 1
    if ("$saida" -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}

$candidatos = @()
if ($env:JAVA_HOME) { $candidatos += $env:JAVA_HOME }
$jdks = Join-Path $env:LOCALAPPDATA "jdks"
if (Test-Path $jdks) {
    $candidatos += Get-ChildItem $jdks -Directory -Filter "jdk-2*" | Sort-Object Name -Descending | ForEach-Object { $_.FullName }
}

$java = $null
foreach ($pastaJdk in $candidatos) {
    $exe = Join-Path $pastaJdk "bin\java.exe"
    if ((Test-Path $exe) -and (Get-VersaoJava $exe) -ge 25) {
        $env:JAVA_HOME = $pastaJdk
        $java = $exe
        break
    }
}
if (-not $java) {
    throw "JDK 25 não encontrado. Instale o JDK 25 ou defina JAVA_HOME apontando para ele."
}
