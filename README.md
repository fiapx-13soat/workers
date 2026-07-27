# fiapx-workers

**O motor de processamento do FIAP X.** Serviço em Spring Boot/Java que consome os pedidos de
processamento, extrai os frames do vídeo com **ffmpeg**, empacota tudo num ZIP no S3 e publica o
resultado. É um serviço **interno**: não tem API pública — expõe só `/health`, `/ready` e
`/metrics` para o orquestrador. Escala na horizontal (`--scale`), e cada réplica pega um job por
vez da fila.

> Faz parte do sistema **FIAP X**. O ambiente completo (RabbitMQ com a topologia, S3 emulado, core,
> notification) sobe pela bancada única do **[fiapx-infra](https://github.com/fiapx-13soat/infra)**
> — `make up-dev`. É lá que este serviço é integrado e testado ponta a ponta.

## O fluxo

```
q.workers.jobs ──▶ [workers] ──▶ frames (ffmpeg) ──▶ ZIP no S3 ──▶ eventos de resultado
job.requested                                                      job.started · archive.ready
                                                                   job.completed · job.failed
```

Falha? A mensagem vai para a fila de retry com backoff (1s, 5s, 30s, 2min) e, esgotadas as
tentativas, para a **DLQ** — nada se perde. O ack é **manual**: a mensagem só sai da fila depois do
job concluído.

## Rodar só este serviço

```bash
cp .env.example .env      # ajuste se precisar
mvn spring-boot:run       # precisa de RabbitMQ + S3 no ar (suba pelo infra)
mvn verify                # unitários + integração (Testcontainers + ffmpeg real; requer Docker)
```

Requisitos: **JDK 21**, Maven e ffmpeg no PATH. `mvn verify` roda spotless (formatação) e jacoco
(cobertura).

## Configuração

Tipada e validada no boot (`WorkersProperties`) — env obrigatória ausente derruba a aplicação com
mensagem clara, em vez de um default silencioso.

| Variável | Default | Para quê |
|---|---|---|
| `AMQP_URL` | `amqp://guest:guest@localhost:5672/` | Broker RabbitMQ |
| `AWS_REGION` | `us-east-1` | Região do S3 |
| `AWS_ENDPOINT_URL` | — | S3 emulado (Floci) local; **vazio na AWS** |
| `S3_BUCKET_VIDEOS` / `S3_BUCKET_ARCHIVES` | `fiapx-videos` / `fiapx-archives` | Buckets de entrada e saída |
| `DEFAULT_FPS` | `1` | Frames por segundo extraídos |
| `WORKER_PREFETCH` | `1` | Jobs simultâneos por réplica |
| `RABBIT_MISSING_QUEUES_FATAL` | `false` | Falhar no boot se a fila não existir |

## Contrato

A topologia RabbitMQ e o schema dos eventos são **fonte única do infra**
(`local/rabbitmq/definitions.json`, `docs/contracts/`). Este serviço consome e publica essa
topologia; não a redeclara. Mudança em argumento de fila ou payload exige PR conjunto no infra.

## CI/CD

[`.github/workflows/ci-cd.yml`](.github/workflows/ci-cd.yml): a cada PR, **build + testes**
(`mvn verify`, com ffmpeg e Testcontainers no runner). No merge para `main` (ou tag `v*`), builda e
publica a imagem no **ECR** (`fiapx-workers`, tags `sha`/versão e `latest`). Autenticação por
credencial de sessão do AWS Academy Learner Lab — fluxo de deploy no
[runbook do infra](https://github.com/fiapx-13soat/infra/blob/main/terraform/README.md).
