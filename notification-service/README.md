# notification-service (port 8083)

Consumes notification **commands** from RabbitMQ and integrates with **AWS** through Spring Cloud AWS.

| Area | Where to look | Guide |
|---|---|---|
| RabbitMQ topology: topic exchange, quorum queue, DLX/DLQ (`Declarables`) | `config/RabbitTopologyConfig` | [07](../docs/07-messaging-kafka-vs-rabbitmq.md) |
| Listener with retries, dedupe and PII masking in logs | `messaging/NotificationListener` | [07](../docs/07-messaging-kafka-vs-rabbitmq.md) |
| Dead-letter alerts to SNS | `messaging/DeadLetterAlerts` | [15](../docs/15-aws.md) |
| Email via SES (`MailSender`), templates | `email/` | [15](../docs/15-aws.md) |
| Receipts on S3 + pre-signed URLs (`S3Template`) | `archive/ReceiptArchiver` | [15](../docs/15-aws.md) |
| SQS consumer (`@SqsListener`) | `returns/ReturnRequestListener` | [15](../docs/15-aws.md) |
| Secrets Manager as a property source | `application.yml` (`spring.config.import`) | [15](../docs/15-aws.md) |

## Run

`docker compose up -d` starts RabbitMQ and LocalStack; `docker/localstack/init-aws.sh` creates the
bucket, queues, topic, SES identity and secret.

```bash
mvn -pl notification-service -am spring-boot:run
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/return-requests \
  --message-body '{"orderId":"0199...","reason":"too small"}'
```

**On AWS:** remove `spring.cloud.aws.endpoint` and the static credentials; the IAM role of the pod
(IRSA / Pod Identity) provides credentials.
