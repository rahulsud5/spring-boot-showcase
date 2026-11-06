# 15 · AWS: Spring Cloud AWS and deployment architecture

## 1. Spring Cloud AWS 4 in notification-service

[Spring Cloud AWS](https://awspring.io) wraps the AWS SDK v2 in Spring idioms: auto-configured clients,
templates, listener annotations and property sources. Version **4.x** targets Spring Boot 4.

| AWS service | Spring abstraction | Class | What it does here |
|---|---|---|---|
| **S3** | `S3Template` | `ReceiptArchiver` | Stores a JSON receipt per order (`store(bucket, key, object)`) and issues a 15-minute **pre-signed URL** (`createSignedGetURL`) |
| **SES** | Spring's `MailSender` (backed by SES) | `EmailSender` | Sends emails; swapping to SMTP needs no code change |
| **SQS** | `@SqsListener` | `ReturnRequestListener` | Consumes `return-requests` (redrive to a DLQ after 3 receives) |
| **SNS** | `SnsTemplate` | `DeadLetterAlerts` | Publishes an ops alert when a RabbitMQ message is dead-lettered |
| **Secrets Manager** | `spring.config.import=optional:aws-secretsmanager:/showcase/notification` | config | Secret JSON keys become Spring properties at startup |

Starters: `spring-cloud-aws-starter-s3`, `-ses`, `-sqs`, `-sns`, `-secrets-manager` (versions from the
`spring-cloud-aws-dependencies` BOM in the root pom).

## 2. Local development with LocalStack

`docker compose up` starts LocalStack 4 and runs `docker/localstack/init-aws.sh`:

```bash
awslocal s3 mb s3://showcase-receipts                          # + versioning
awslocal sqs create-queue --queue-name return-requests-dlq
awslocal sqs create-queue --queue-name return-requests --attributes RedrivePolicy=...maxReceiveCount=3
awslocal sns create-topic --name ops-alerts
awslocal ses verify-email-identity --email-address no-reply@showcase.example
awslocal secretsmanager create-secret --name /showcase/notification --secret-string '{...}'
```

The application points every client to LocalStack with `spring.cloud.aws.endpoint` and dummy
credentials. **On AWS, delete those properties** and let the default credential provider chain find
the pod's IAM role. Try it:

```bash
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/return-requests \
  --message-body '{"orderId":"...","reason":"too small"}'
awslocal s3 ls s3://showcase-receipts --recursive
```

## 3. Target AWS architecture

```
Route 53 ─▶ CloudFront + AWS WAF ─▶ ALB (TLS, ACM cert) ─▶ EKS (private subnets, 3 AZs)
                                                            ├─ api-gateway        (HPA)
                                                            ├─ order-service      ─▶ Aurora PostgreSQL / RDS (Multi-AZ) via RDS Proxy
                                                            ├─ inventory-service  ─▶ MongoDB Atlas (PrivateLink) or DocumentDB*
                                                            ├─ notification-service ─▶ SES, S3, SQS, SNS
                                                            └─ auth-server        (or Amazon Cognito / Okta / Keycloak)
   Amazon MSK (Kafka)   Amazon MQ for RabbitMQ   ElastiCache for Redis (Valkey)
   Secrets Manager + KMS   ECR (images, scan on push)   CloudWatch / Amazon Managed Prometheus + Grafana, X-Ray via ADOT
```
\* DocumentDB is MongoDB-*compatible*, not MongoDB: check the features you use (transactions, aggregation
stages, TTL) against its compatibility list, or use MongoDB Atlas on AWS.

| Concern | Managed service | Spring-side setting |
|---|---|---|
| Postgres | RDS / Aurora + RDS Proxy | JDBC URL, IAM auth or Secrets Manager rotation; Hikari max-lifetime < proxy idle timeout |
| Kafka | MSK (IAM auth) | `aws-msk-iam-auth` SASL config in `spring.kafka.properties` |
| RabbitMQ | Amazon MQ | `spring.rabbitmq.addresses=amqps://...` |
| Redis | ElastiCache (cluster mode, TLS) | `spring.data.redis.cluster.nodes`, `ssl.enabled` |
| Secrets | Secrets Manager / Parameter Store | `spring.config.import=aws-secretsmanager:` / `aws-parameterstore:` |
| Telemetry | ADOT collector → X-Ray / CloudWatch / AMP | `OTEL_EXPORTER_OTLP_ENDPOINT` |
| Images | ECR | GitLab pushes with OIDC ([docs/18](18-gitlab-ci.md)) |

## 4. Identity: no long-lived keys anywhere

* **Pods → AWS:** IRSA (IAM Roles for Service Accounts) or EKS Pod Identity. The ServiceAccount carries
  `eks.amazonaws.com/role-arn` (`deploy/k8s/base/order-service.yaml`), and the SDK picks up
  web-identity credentials automatically.
* **CI → AWS:** GitLab OIDC `id_tokens` exchanged for short-lived STS credentials
  (`AssumeRoleWithWebIdentity`). No AWS keys stored in CI variables.
* **Least privilege per service:** notification-service's role may `s3:PutObject` on
  `showcase-receipts/*`, `ses:SendEmail` from one identity, `sqs:ReceiveMessage/DeleteMessage` on one
  queue, `sns:Publish` on one topic, `secretsmanager:GetSecretValue` on one secret. Nothing else.

## 5. Compute options for Spring Boot on AWS

| Option | Good for | Notes |
|---|---|---|
| **EKS** (this repo's manifests) | Many services, platform team, portability | Most control, most operational work |
| ECS Fargate | Containers without managing Kubernetes | Simpler; the same image works |
| App Runner / Elastic Beanstalk | Single services, small teams | Least ops |
| Lambda (+ SnapStart, or GraalVM native) | Spiky, event-driven workloads | SnapStart cuts Java cold starts; Spring Cloud Function adapts handlers |

## 6. Cost and reliability notes

* Multi-AZ everything stateful; spread pods across AZs (`topologySpreadConstraints`).
* Use Graviton (arm64) instances: the Temurin and Maven base images are multi-arch, so adding `--platform linux/amd64,linux/arm64` to the CI `docker buildx build` command is enough.
* S3 lifecycle rules for receipts (to Glacier after 90 days); SQS DLQs with alarms on `ApproximateNumberOfMessagesVisible`.
* Right-size with metrics before buying Savings Plans; scale non-production to zero at night.
