logging_endpoint_arn = "arn:aws:logs:eu-west-2:885513274347:destination:csls_cw_logs_destination_prod"
logging_endpoint_arns = ["arn:aws:logs:eu-west-2:885513274347:destination:csls_cw_logs_destination_prod"]
lambda_zip_file = "$(ls -1 ../../../../delivery-receipts-api-release/*.zip)"
common_state_bucket = "${STATE_BUCKET}"
lock = "${ENABLE_STATE_LOCKING}"