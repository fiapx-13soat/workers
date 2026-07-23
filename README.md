# fiapx-workers

Worker de processamento de vídeo do FIAP X: consome `job.requested` de `q.workers.jobs`, extrai
frames com ffmpeg, empacota num ZIP no S3 e publica os eventos de resultado (`job.started`,
`archive.ready`, `job.completed`, `job.failed`). Serviço **interno** — sem API pública; expõe só
`/health`, `/ready` e `/metrics` para o orquestrador.

## Rodar local

O sistema completo (RabbitMQ com a topologia, S3 emulado, Core, Notification) sobe pela bancada
única do **fiapx-infra** — `make up-dev`. É lá que este serviço é integrado e testado ponta a ponta.

Para mexer só neste serviço:

```bash
cp .env.example .env      # ajuste se precisar
mvn spring-boot:run       # precisa de RabbitMQ + S3 no ar (suba pelo infra)
mvn test                  # unitários + integração (Testcontainers; requer Docker)
```

## Contrato

A topologia RabbitMQ e o schema dos eventos são fonte única do **fiapx-infra**
(`local/rabbitmq/definitions.json`, `docs/contracts/`). Este serviço consome e publica essa
topologia; não a redeclara. Mudança em argumento de fila ou payload exige PR conjunto no infra.
