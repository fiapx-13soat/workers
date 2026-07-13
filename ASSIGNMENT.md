# ASSIGNMENT — `fiapx-workers`

> Documentos de referência na org: `FIAP_X_Documentacao_Arquitetura.docx` e `ESPECIFICACAO_TECNICA_DEVS.md`.

## 1. Contexto (2 minutos de leitura)

O sistema tem 3 microsserviços: **Core** (API síncrona: upload, status, download), **Workers** (este repo) e **Notification** (e-mail). O Core recebe o upload, grava o vídeo no S3 e publica `ProcessingRequested` no RabbitMQ. **Este serviço consome esse evento, extrai os frames com ffmpeg, gera o ZIP no S3 e publica o resultado.** É o único serviço CPU-bound do sistema — escala pela profundidade da fila, independente da API.

## 2. Escopo deste repo

1. Consumir `ProcessingRequested` da fila `q.workers.jobs` (prefetch=1).
2. Baixar o vídeo do S3 (`videoStorageKey` do payload).
3. Extrair frames com **ffmpeg** conforme `parameters` (default: 1 frame/segundo).
4. Gerar o `.zip` com os frames e subir para o bucket de archives.
5. Publicar a sequência de eventos de resultado (ver contratos).
6. Respeitar **cancelamento**: checar sinal de `ProcessingCancelled` em pontos seguros; abortar limpando artefatos parciais.
7. **Graceful shutdown**: em SIGTERM (deploy/scale-down), terminar o Job atual com timeout; estourou → `nack` para redelivery.

**Sem banco próprio.** O estado permanente é do Core; este serviço só lê/escreve S3 e publica eventos. **Sem API pública** — apenas `/health`, `/ready`, `/metrics`.

**Fora de escopo:** API HTTP de negócio, e-mail, atualização de status no banco (o Core faz isso consumindo seus eventos).

## 3. Contratos de integração

### Eventos (exchange `video.processing`, topic, RabbitMQ)

| Direção | Evento | Routing key | Quando / payload |
|---|---|---|---|
| **Consome** (`q.workers.jobs`) | `ProcessingRequested` | `job.requested` | `jobId`, `videoStorageKey`, `parameters`, `ownerId` |
| **Consome** | `ProcessingCancelled` | `job.cancelled` | `jobId` — marca flag e aborta se em execução |
| **Publica** | `ProcessingStarted` | `job.started` | **antes** de iniciar a extração (`jobId`) |
| **Publica** | `ProcessingCompleted` | `job.completed` | `jobId`, `frameCount` |
| **Publica** | `ArchiveReady` | `archive.ready` | `jobId`, `archiveStorageKey`, `sizeBytes` |
| **Publica** | `ProcessingFailed` | `job.failed` | `jobId`, `errorCode`, `errorMessage` (amigável), `transient: false` |

Envelope padrão: `{eventType, schemaVersion, eventId, occurredAt, correlationId, payload}` — propagar o `correlationId` recebido.

### Configuração (12-factor — só env vars)
`AMQP_URL`, `AWS_ENDPOINT_URL` (vazio = AWS real; local = Floci), `AWS_REGION`, `S3_BUCKET_VIDEOS`, `S3_BUCKET_ARCHIVES`, `WORKER_SHUTDOWN_TIMEOUT` (default 120s), `DEFAULT_FPS` (default 1).

## 4. Convenções obrigatórias (valem para os 3 serviços)

- **Idempotência por `jobId`**: antes de processar, verificar se o Job já foi concluído (ex.: marker no S3); se sim, `ack` sem reprocessar — redelivery não pode gerar segundo ZIP.
- **Retry/DLQ**: falha transitória (timeout de S3, rede) → backoff 1s/5s/30s/2min, máx. 4 tentativas → `q.workers.jobs.dlq`. Falha determinística (vídeo corrompido) → DLQ + `ProcessingFailed`, **sem travar a fila**.
- Logs JSON com `correlationId`, `jobId`.
- `/health`, `/ready`, `/metrics` (expor `queue_depth` consumida via broker, `jobs_processing`, `job_duration_seconds`).

## 5. Critérios de aceitação

- [ ] **CA-W01** — `ProcessingStarted` publicado **antes** da extração (usuário vê `PROCESSING`).
- [ ] **CA-W02** — Vídeo válido → frames conforme `parameters.fps` (default 1), ZIP no S3, `ProcessingCompleted` + `ArchiveReady`.
- [ ] **CA-W03** — Mesmo `ProcessingRequested` entregue 2x → segundo consumo detecta Job concluído e faz `ack` sem reprocessar (idempotência).
- [ ] **CA-W04** — Erro transitório → retry com backoff (1s/5s/30s/2min), até 4 tentativas.
- [ ] **CA-W05** — Vídeo corrompido → após tentativas, mensagem na DLQ + `ProcessingFailed` com `errorCode` e mensagem amigável; fila principal segue fluindo.
- [ ] **CA-W06** — `ProcessingCancelled` durante execução → aborta em ponto seguro, remove artefatos parciais do S3, **não** publica `ProcessingCompleted`.
- [ ] **CA-W07** — N réplicas → N Jobs distintos em paralelo, sem Job processado por dois Workers (prefetch=1).
- [ ] **CA-W08** — `kill -9` no meio de um Job → mensagem não-ackeada volta à fila e outro Worker processa; nenhum Job "some".
- [ ] **CA-W09** — Métricas expostas em `/metrics`: profundidade de fila, jobs em execução, duração.
- [ ] **CA-W10** — SIGTERM → termina o Job atual (graceful, com timeout); estourou → `nack` para redelivery.

## 6. Definition of Done

- [ ] Testes unitários (parsing de parameters, máquina de estados local, idempotência) + integração (Testcontainers: RabbitMQ + MinIO/S3 fake; ffmpeg real com vídeo pequeno de fixture).
- [ ] Dockerfile multi-stage **com ffmpeg** na imagem final (atenção ao tamanho: usar base slim + apenas o ffmpeg necessário).
- [ ] GitHub Actions: build → testes → push da imagem no **ECR** (`fiapx-workers`) via **OIDC**.
- [ ] Roda no ambiente local do `fiapx-infra` (`make up-dev SERVICE=workers`, réplicas=2) e passa no `make smoke`.
- [ ] README: como rodar local, env vars, como adicionar um vídeo de teste.

## 7. Dependências / com quem falar

- **fiapx-core**: schema de `ProcessingRequested` (entrada) e dos eventos de resultado (saída) — mudanças só com PR conjunto e bump de `schemaVersion`.
- **fiapx-infra**: filas/DLQs no `definitions.json`, buckets S3, autoscaling por fila (KEDA/App Auto Scaling) — informe qual métrica você expõe.
- **fiapx-notification**: não há integração direta (ele consome os mesmos eventos de resultado do broker).
