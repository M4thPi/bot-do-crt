# O projeto compila para Java 25 (Mac e Linux). Usa JAVA_HOME se apontar para um JDK 25+; no Mac,
# procura com /usr/libexec/java_home. Define $JAVA e exporta JAVA_HOME (usado pelo mvnw).
# Uso: . ./java-home.sh

if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 25+ 2>/dev/null || true)
fi
if [ -n "$JAVA_HOME" ]; then
    export JAVA_HOME
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi

# "java -version" escreve no stderr: 'openjdk version "25.0.2" ...'
versao=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
if [ -z "$versao" ] || [ "$versao" -lt 25 ]; then
    echo "JDK 25 não encontrado. Instale o JDK 25 (https://adoptium.net) ou defina JAVA_HOME apontando para ele." >&2
    exit 1
fi
