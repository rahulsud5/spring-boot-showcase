#!/bin/bash
# =============================================================================
# LocalStack "ready" hook: creates the AWS resources the services expect.
# In real AWS these come from Terraform/CDK (infrastructure as code), never from application code.
# =============================================================================
set -euo pipefail

echo "Creating S3 bucket for order receipts"
awslocal s3 mb s3://showcase-receipts
awslocal s3api put-bucket-versioning --bucket showcase-receipts --versioning-configuration Status=Enabled

echo "Creating SQS queues (with a dead-letter queue + redrive policy)"
awslocal sqs create-queue --queue-name return-requests-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url http://localhost:4566/000000000000/return-requests-dlq \
          --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name return-requests \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\",\"VisibilityTimeout\":\"30\"}"

echo "Creating SNS topic for operational alerts"
awslocal sns create-topic --name ops-alerts

echo "Verifying SES sender identity"
awslocal ses verify-email-identity --email-address no-reply@showcase.example

echo "Storing a secret in Secrets Manager (read via spring.config.import=aws-secretsmanager:...)"
awslocal secretsmanager create-secret --name /showcase/notification \
  --secret-string '{"notification.sender-address":"no-reply@showcase.example"}'

echo "LocalStack resources ready"
