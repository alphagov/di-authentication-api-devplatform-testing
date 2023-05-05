utils_release_zip_file = ##"$(ls -1 ../../../../utils-release/*.zip)"
shared_state_bucket = #"${STATE_BUCKET}"
logging_endpoint_arn = ##"arn:aws:logs:eu-west-2:885513274347:destination:csls_cw_logs_destination_prod"
logging_endpoint_arns = #["arn:aws:logs:eu-west-2:885513274347:destination:csls_cw_logs_destination_prod"]
lock = #"${ENABLE_STATE_LOCKING}"