# resource "aws_s3_bucket" "source_bucket" {
#   bucket_prefix = "${var.environment}-audit-lambda-source-"

#   versioning {
#     enabled = true
#   }
# }

# resource "aws_s3_bucket_object" "audit_processor_release_zip" {
#   bucket = aws_s3_bucket.source_bucket.bucket
#   key    = "audit-processor-release.zip"

#   server_side_encryption = "AES256"
#   source                 = var.lambda_zip_file
#   source_hash            = filemd5(var.lambda_zip_file)
# }

# # Terraform source bucket for concourse migration
# resource "aws_s3_bucket" "terraform_source_bucket" {
#   bucket_prefix = "${var.environment}-audit-lambda-terraform-source-"

#   versioning {
#     enabled = true
#   }
# }

# resource "aws_s3_bucket_versioning" "terraform_source_bucket_versioning" {
#   bucket = aws_s3_bucket.terraform_source_bucket.id
#   versioning_configuration {
#     status = "Enabled"
#   }
# }

# resource "aws_s3_object" "audit_processor_release_zip" {
#   bucket = aws_s3_bucket_versioning.terraform_source_bucket_versioning.id
#   key    = "audit-processor-release.zip"

#   server_side_encryption = "AES256"
#   source                 = var.lambda_zip_file
#   source_hash            = filemd5(var.lambda_zip_file)
# }