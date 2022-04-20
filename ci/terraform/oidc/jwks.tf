module "oidc_jwks_role" {
  source      = "../modules/lambda-role"
  environment = var.environment
  role_name   = "oidc-jwks-role"
  vpc_arn     = local.authentication_vpc_arn

  policies_to_attach = [
    aws_iam_policy.oidc_default_id_token_public_key_kms_policy.arn,
  ]
}

module "jwks" {
  source = "../modules/endpoint-module"

  endpoint_name   = "jwks.json"
  path_part       = "jwks.json"
  endpoint_method = "GET"
  environment     = var.environment

  handler_environment_variables = {
    EVENTS_SNS_TOPIC_ARN     = aws_sns_topic.events.arn
    AUDIT_SIGNING_KEY_ALIAS  = local.audit_signing_key_alias_name
    LOCALSTACK_ENDPOINT      = var.use_localstack ? var.localstack_endpoint : null
    TOKEN_SIGNING_KEY_ALIAS  = local.id_token_signing_key_alias_name
    HEADERS_CASE_INSENSITIVE = var.use_localstack ? "true" : "false"
  }
  handler_function_name = "uk.gov.di.authentication.oidc.lambda.JwksHandler::handleRequest"

  rest_api_id      = aws_api_gateway_rest_api.di_authentication_api.id
  root_resource_id = aws_api_gateway_resource.wellknown_resource.id
  execution_arn    = aws_api_gateway_rest_api.di_authentication_api.execution_arn
  memory_size      = var.endpoint_memory_size

  source_bucket                  = aws_s3_bucket.source_bucket.bucket
  lambda_zip_file                = aws_s3_bucket_object.oidc_api_release_zip.key
  lambda_zip_file_version        = aws_s3_bucket_object.oidc_api_release_zip.version_id
  warmer_lambda_zip_file         = aws_s3_bucket_object.warmer_release_zip.key
  warmer_lambda_zip_file_version = aws_s3_bucket_object.warmer_release_zip.version_id
  code_signing_config_arn        = local.lambda_code_signing_configuration_arn

  authentication_vpc_arn                 = local.authentication_vpc_arn
  security_group_ids                     = [local.authentication_security_group_id]
  subnet_id                              = local.authentication_subnet_ids
  lambda_role_arn                        = module.oidc_jwks_role.arn
  logging_endpoint_arns                  = var.logging_endpoint_arns
  cloudwatch_key_arn                     = data.terraform_remote_state.shared.outputs.cloudwatch_encryption_key_arn
  cloudwatch_log_retention               = var.cloudwatch_log_retention
  lambda_env_vars_encryption_kms_key_arn = local.lambda_env_vars_encryption_kms_key_arn
  default_tags                           = local.default_tags

  keep_lambda_warm             = var.keep_lambdas_warm
  warmer_handler_function_name = "uk.gov.di.lambdawarmer.lambda.LambdaWarmerHandler::handleRequest"
  warmer_security_group_ids    = [local.authentication_security_group_id]
  warmer_handler_environment_variables = {
    LAMBDA_MIN_CONCURRENCY = var.lambda_min_concurrency
  }

  use_localstack = var.use_localstack

  depends_on = [
    aws_api_gateway_rest_api.di_authentication_api,
    aws_api_gateway_resource.connect_resource,
    aws_api_gateway_resource.wellknown_resource,
  ]
}
