# Imagem para servidor (Linux): monta o .jar com Java 25 e roda a API e o robô de coleta.
#   docker build -t concursos-tecnicos .
#   docker run -d --name concursos -p 8080:8080 -v concursos-dados:/app/dados \
#       -e CONCURSOS_API_CHAVE="troque-por-uma-chave-longa-e-aleatoria" concursos-tecnicos
# Detalhes (Postgres, HTTPS, variáveis): docs/IMPLANTACAO.md

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --create-home robo && mkdir -p /app/dados && chown robo /app/dados
COPY --from=build /src/target/concursos-tecnicos.jar app.jar
USER robo
# banco H2 (se não usar Postgres), cópias dos PDFs e a chave da API gerada
VOLUME /app/dados
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
