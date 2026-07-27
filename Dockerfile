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

# ffmpeg: dependência de runtime do worker (extração de frames)
# curl: healthcheck do compose/ECS — a base jre-jammy não traz nenhum cliente HTTP
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/*

# Usuário não-root
RUN groupadd -r app && useradd -r -g app -d /app app
WORKDIR /app

COPY --from=build /build/target/app.jar /app/app.jar

# OTel Java agent (tracing distribuído). Auto-instrumenta Spring AMQP + JDBC + HTTP e propaga o
# W3C trace context nos headers das mensagens. Fica DESLIGADO por padrão (OTEL_SDK_DISABLED=true):
# o compose da bancada liga passando o endpoint do Jaeger; na AWS/CI fica inerte.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar /app/otel-agent.jar

RUN chown -R app:app /app
USER app
ENV OTEL_SDK_DISABLED=true

EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/otel-agent.jar", "-jar", "/app/app.jar"]
