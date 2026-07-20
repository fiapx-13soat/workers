# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache de dependências: só o pom primeiro
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Código e empacotamento (testes rodam no CI, não na imagem: exigem ffmpeg + Docker)
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-jammy AS runtime

# ffmpeg é dependência de runtime do worker (extração de frames)
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Usuário não-root
RUN groupadd -r app && useradd -r -g app -d /app app
WORKDIR /app

COPY --from=build /build/target/app.jar /app/app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
