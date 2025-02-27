variable "aws_region" {
  default = "eu-west-2"
}

variable "deployer_role_arn" {
  default     = ""
  description = "The name of the AWS role to assume, leave blank when running locally"
  type        = string
}

variable "environment" {
  type = string
}

variable "aws_endpoint" {
  type    = string
  default = null
}

variable "aws_dynamodb_endpoint" {
  type    = string
  default = null
}

variable "use_localstack" {
  type    = bool
  default = false
}

variable "allow_bulk_test_users" {
  type    = bool
  default = false
}

variable "utils_release_zip_file" {
  default     = "../../../utils/build/distributions/utils.zip"
  description = "Location of the Utils distribution ZIP file"
  type        = string
}

variable "shared_state_bucket" {
  type = string
}

variable "logging_endpoint_arn" {
  type        = string
  default     = ""
  description = "Amazon Resource Name (ARN) for the endpoint to ship logs to"
}

variable "logging_endpoint_arns" {
  type        = list(string)
  default     = []
  description = "Amazon Resource Name (ARN) for the CSLS endpoints to ship logs to"
}

variable "cloudwatch_log_retention" {
  default     = 1
  type        = number
  description = "The number of day to retain Cloudwatch logs for"
}

variable "terms_and_conditions" {
  type        = string
  default     = "1.1"
  description = "The latest Terms and Conditions version number"
}
