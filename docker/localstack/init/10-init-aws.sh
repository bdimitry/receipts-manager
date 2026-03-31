#!/bin/sh
set -eu

region="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-central-1}}"
bucket_name="${AWS_S3_BUCKET:-home-budget-files}"
report_queue_name="${AWS_SQS_QUEUE:-report-generation-queue}"
receipt_ocr_queue_name="${AWS_SQS_RECEIPT_OCR_QUEUE:-receipt-ocr-queue}"

retry() {
  attempts="$1"
  shift

  count=1
  while ! "$@"; do
    if [ "${count}" -ge "${attempts}" ]; then
      return 1
    fi

    count=$((count + 1))
    sleep 1
  done
}

echo "Waiting for LocalStack S3 in region ${region}"
retry 20 sh -c 'awslocal s3api list-buckets >/dev/null 2>&1'

echo "Waiting for LocalStack SQS in region ${region}"
retry 20 sh -c 'awslocal sqs list-queues >/dev/null 2>&1'

echo "Ensuring LocalStack S3 bucket exists: ${bucket_name}"
if ! awslocal s3api head-bucket --bucket "${bucket_name}" >/dev/null 2>&1; then
  retry 10 awslocal s3 mb "s3://${bucket_name}" >/dev/null
fi

for queue_name in "${report_queue_name}" "${receipt_ocr_queue_name}"; do
  echo "Ensuring LocalStack SQS queue exists: ${queue_name}"
  if ! awslocal sqs get-queue-url --queue-name "${queue_name}" >/dev/null 2>&1; then
    retry 10 awslocal sqs create-queue --queue-name "${queue_name}" >/dev/null
  fi
done
