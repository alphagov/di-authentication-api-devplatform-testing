module "common_passwords_update_lambda_role" {
  source = "../modules/lambda-role"

  environment = var.environment
  role_name   = "common_passwords_update_lambda_role"

  policies_to_attach = [
    aws_iam_policy.common_passwords_s3_read_access.arn,
    aws_iam_policy.common_passwords_dynamo_full_access.arn,
  ]
}

resource "aws_lambda_function" "common_passwords_dynamo_update_lambda" {
  function_name = "${var.environment}-common-passwords-dynamo-update-lambda"
  role          = module.common_passwords_update_lambda_role.arn
  handler       = "uk.gov.di.authentication.utils.lambda.S3ToDynamoDbHandler::handleRequest"
  timeout       = 900
  memory_size   = 4096
  runtime       = "java11"
  publish       = true

  s3_bucket         = aws_s3_object.utils_release_zip.bucket
  s3_key            = aws_s3_object.utils_release_zip.key
  s3_object_version = aws_s3_object.utils_release_zip.version_id

  environment {
    variables = merge({
      ENVIRONMENT = var.environment
    })
  }

  tags = local.default_tags
}

resource "aws_s3_bucket_notification" "common_passwords_dynamo_update_lambda_trigger" {
  bucket = aws_s3_bucket.common_passwords.id
  lambda_function {
    lambda_function_arn = aws_lambda_function.common_passwords_dynamo_update_lambda.arn
    events              = ["s3:ObjectCreated:*"]

  }
}

resource "aws_lambda_permission" "common_passwords_dynamo_update_lambda_invoke_permission" {
  statement_id  = "AllowS3Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.common_passwords_dynamo_update_lambda.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = "arn:aws:s3:::${aws_s3_bucket.common_passwords.id}"
}

resource "aws_cloudwatch_log_group" "common_passwords_update_lambda_log_group" {
  count = var.use_localstack ? 0 : 1

  name              = "/aws/lambda/${aws_lambda_function.common_passwords_dynamo_update_lambda.function_name}"
  kms_key_id        = local.cloudwatch_encryption_key_arn
  retention_in_days = var.cloudwatch_log_retention

  tags = local.default_tags

  depends_on = [
    aws_lambda_function.common_passwords_dynamo_update_lambda
  ]
}

resource "aws_cloudwatch_log_subscription_filter" "common_passwords_update_lambda_log_subscription" {
  count           = length(var.logging_endpoint_arns)
  name            = "${aws_lambda_function.common_passwords_dynamo_update_lambda.function_name}-log-subscription-${count.index}"
  log_group_name  = aws_cloudwatch_log_group.common_passwords_update_lambda_log_group[0].name
  filter_pattern  = ""
  destination_arn = var.logging_endpoint_arns[count.index]

  lifecycle {
    create_before_destroy = false
  }
}
