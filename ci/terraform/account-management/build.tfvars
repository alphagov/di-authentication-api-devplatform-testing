notify_api_key = #${NOTIFY_API_KEY}
logging_endpoint_arn = #"arn:aws:logs:eu-west-2:885513274347:destination:csls_cw_logs_destination_prod"
logging_endpoint_arns = #["arn:aws:logs:eu-west-2:885513274347:destination:csls_cw_logs_destination_prod"]' \
lambda_zip_file = #"(ls -1 ../../../../api-release/*.zip)"
common_state_bucket = #"${STATE_BUCKET}"
txma_account_id = #"${TXMA_ACCOUNT_ID}"
test_client_verify_email_otp = #"${TEST_CLIENT_VERIFY_EMAIL_OTP}"
test_client_verify_phone_number_otp = #"${TEST_CLIENT_VERIFY_PHONE_NUMBER_OTP}" \
test_clients_enabled = #"${TEST_CLIENTS_ENABLED}"
lock = #"${ENABLE_STATE_LOCKING}"