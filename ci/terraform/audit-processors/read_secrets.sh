#!/usr/bin/env bash

set -eu

# AWS CLI command to export and load secrets
export TF_VAR_shared_state_bucket="$(aws secretsmanager get-secret-value --secret-id "testing-secrets" --query SecretString --output text)"
